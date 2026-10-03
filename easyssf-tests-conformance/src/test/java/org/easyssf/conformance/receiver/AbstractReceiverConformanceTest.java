package org.easyssf.conformance.receiver;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.easyssf.conformance.ConformanceApiClient.Plan;
import org.easyssf.conformance.ConformanceApiClient.PlanModule;
import org.easyssf.conformance.ConformanceModuleResult;
import org.easyssf.conformance.ConformanceSettings;
import org.easyssf.conformance.ConformanceSuite;
import org.easyssf.core.SsfDeliveryMethod;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/**
 * Runs one of the suite's receiver test plans against the receiver under test
 * ({@link CtsApplication}): creates the plan from the suite configuration of the
 * subclass, and for every module of the plan starts the module, plays the receiver
 * choreography the module expects (see {@link ReceiverModules}) and asserts the result
 * the suite reports.
 *
 * <p>
 * The tests are tagged {@code conformance} and need Docker for the suite, see
 * {@link ConformanceSuite}.
 */
@Tag("conformance")
@TestInstance(Lifecycle.PER_CLASS)
@SpringBootTest(classes = CtsApplication.class, webEnvironment = WebEnvironment.DEFINED_PORT)
public abstract class AbstractReceiverConformanceTest {

    private static final Logger logger = LoggerFactory.getLogger(AbstractReceiverConformanceTest.class);

    private static final String DELIVERY_MODE_VARIANT = "ssf_delivery_mode";

    private static final Duration MODULE_TIMEOUT = Duration.ofMinutes(8);

    private static final List<String> SUITE_CONFIG_DIRECTORIES = List.of("suite-config",
            "easyssf-tests-conformance/suite-config");

    @Autowired
    private ConformanceRunner runner;

    private ConformanceSuite suite;

    private Plan plan;

    private String alias;

    @DynamicPropertySource
    static void receiverProperties(DynamicPropertyRegistry registry) {
        ConformanceSuite suite = ConformanceSuite.instance();
        int port = ConformanceSettings.receiverPort();
        registry.add("server.port", () -> port);
        registry.add("server.ssl.certificate", () -> "classpath:tls/receiver.pem");
        registry.add("server.ssl.certificate-private-key", () -> "classpath:tls/receiver-key.pem");
        registry.add("spring.ssl.bundle.pem.conformance-suite.truststore.certificate",
                () -> "file:" + suite.certificateFile());
        registry.add("cts.transmitter.ssl-bundle", () -> "conformance-suite");
        registry.add("cts.delivery.push-url", () -> ConformanceSettings.receiverPushUrl()
            .orElseGet(() -> URI.create("https://" + suite.systemUnderTestHost() + ":" + port + "/ssf/push")));
    }

    /**
     * The name of the test plan, e.g. {@code openid-ssf-receiver-caep-test-plan}.
     */
    protected abstract String planName();

    /**
     * The variant of the plan, e.g. the delivery and authentication modes.
     */
    protected abstract Map<String, String> planVariant();

    /**
     * The file name of the suite configuration in {@code suite-config/}.
     */
    protected abstract String suiteConfigFile();

    @BeforeAll
    void createPlan() {
        this.suite = ConformanceSuite.instance();
        JsonNode config = suiteConfig();
        this.alias = config.path("alias").asString();
        assertThat(this.alias).as("alias in " + suiteConfigFile()).isNotBlank();
        this.plan = this.suite.client().createPlan(planName(), planVariant(), config);
        logger.info("Created plan {} {} with {} modules: {}", planName(), planVariant(), this.plan.modules().size(),
                this.plan.url());
    }

    Stream<PlanModule> modules() {
        return this.plan.modules().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modules")
    void conformance(PlanModule module) {
        ReceiverModules.Expectation expectation = ReceiverModules.expectation(module.name());
        String moduleId = this.suite.client().startModule(this.plan, module);
        logger.info("Module {} {} is waiting for the receiver: {}", module, moduleId,
                this.suite.baseUri().resolve("/log-detail.html?log=" + moduleId));

        ConformanceRun run = this.runner.start(expectation.scenario(), issuer(), deliveryMethod(),
                expectation.idleTimeout());
        await().atMost(MODULE_TIMEOUT).pollInterval(Duration.ofMillis(500)).until(() -> !run.isActive());
        logger.info("Receiver run {} ended with {}:\n{}", run.getId(), run.getStatus(), receiverLog(run));

        ConformanceModuleResult result = this.suite.client().awaitResult(this.plan, module, moduleId, MODULE_TIMEOUT);
        if (!result.finishedWith("PASSED")) {
            logger.error("Full log of the failed module {}:\n{}", module, result.logs().toPrettyString());
            fail(result.summary() + "\nReceiver run " + run.getId() + " ended with " + run.getStatus() + ":\n"
                    + receiverLog(run));
        }
        assertThat(run.getStatus()).as("status of the receiver run for " + module).isEqualTo(expectation.runStatus());
    }

    private String issuer() {
        return this.suite.baseUri() + "/test/a/" + this.alias;
    }

    private SsfDeliveryMethod deliveryMethod() {
        String mode = planVariant().get(DELIVERY_MODE_VARIANT);
        assertThat(mode).as(DELIVERY_MODE_VARIANT + " in the plan variant").isNotNull();
        return SsfDeliveryMethod.valueOf(mode.toUpperCase(Locale.ROOT));
    }

    private JsonNode suiteConfig() {
        for (String directory : SUITE_CONFIG_DIRECTORIES) {
            Path file = Path.of(directory, suiteConfigFile());
            if (Files.isRegularFile(file)) {
                try {
                    return JsonMapper.builder().build().readTree(Files.readString(file));
                }
                catch (IOException ex) {
                    throw new IllegalStateException("Could not read " + file, ex);
                }
            }
        }
        throw new IllegalStateException("No suite configuration " + suiteConfigFile() + " in "
                + SUITE_CONFIG_DIRECTORIES + " (working directory " + Path.of("").toAbsolutePath() + ")");
    }

    private static String receiverLog(ConformanceRun run) {
        StringBuilder log = new StringBuilder();
        run.getLog()
            .forEach((entry) -> log.append("  ").append(entry.time()).append(' ').append(entry.message()).append('\n'));
        return log.toString();
    }

}
