package org.easyssf.test.conformance.receiver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.stream.Stream;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.test.conformance.ConformanceApiClient.Plan;
import org.easyssf.test.conformance.ConformanceApiClient.PlanModule;
import org.easyssf.test.conformance.ConformanceModuleResult;
import org.easyssf.test.conformance.ConformanceSettings;
import org.easyssf.test.conformance.ConformanceSuite;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

/**
 * Runs a {@link ConformancePlan plan} of the OpenID conformance suite against a receiver
 * under test: creates the plan in the suite, which Testcontainers starts, and runs every
 * module of it as one test, playing the {@link ConformanceScenario scenario} the module
 * expects with the {@link ConformanceRunner} of the receiver under test.
 *
 * <p>
 * A framework's test class extends it, starts its receiver application on
 * {@link #receiverPort()} with TLS from {@link #RECEIVER_CERTIFICATE} and
 * {@link #RECEIVER_PRIVATE_KEY}, trusting {@link ConformanceSuite#certificateFile()},
 * reachable by the suite at {@link #receiverPushUrl(ConformanceSuite)}, and returns its
 * {@link #runner() runner} and the {@link #plan() plan} to run.
 */
@Tag("conformance")
@TestInstance(Lifecycle.PER_CLASS)
public abstract class AbstractReceiverConformanceTest {

    /**
     * The TLS certificate of the receiver under test, on the class path.
     */
    public static final String RECEIVER_CERTIFICATE = "tls/receiver.pem";

    /**
     * The TLS private key of the receiver under test, on the class path.
     */
    public static final String RECEIVER_PRIVATE_KEY = "tls/receiver-key.pem";

    private static final Logger logger = LoggerFactory.getLogger(AbstractReceiverConformanceTest.class);

    private static final String DELIVERY_MODE_VARIANT = "ssf_delivery_mode";

    private static final Duration MODULE_TIMEOUT = Duration.ofMinutes(8);

    private ConformanceSuite suite;

    private Plan plan;

    private String alias;

    /**
     * @return the plan to run
     */
    protected abstract ConformancePlan plan();

    /**
     * @return the runner of the receiver under test
     */
    protected abstract ConformanceRunner runner();

    /**
     * @return the port the receiver under test listens on
     */
    public static int receiverPort() {
        return ConformanceSettings.receiverPort();
    }

    /**
     * @return the URL under which the suite reaches the push endpoint of the receiver
     * under test
     */
    public static URI receiverPushUrl(ConformanceSuite suite) {
        return ConformanceSettings.receiverPushUrl()
            .orElseGet(() -> URI.create("https://" + suite.systemUnderTestHost() + ":" + receiverPort() + "/ssf/push"));
    }

    @BeforeAll
    void createPlan() {
        this.suite = ConformanceSuite.instance();
        JsonNode config = suiteConfig();
        this.alias = config.path("alias").asString();
        assertThat(this.alias).as("alias in " + plan().configFile()).isNotBlank();
        this.plan = this.suite.client().createPlan(plan().planName(), plan().variant(), config);
        logger.info("Created plan {} {} with {} modules: {}", plan().planName(), plan().variant(),
                this.plan.modules().size(), this.plan.url());
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
        ConformanceRun run = runner().start(expectation.scenario(), issuer(), deliveryMethod(),
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
        String mode = plan().variant().get(DELIVERY_MODE_VARIANT);
        assertThat(mode).as(DELIVERY_MODE_VARIANT + " in the plan variant").isNotNull();
        return SsfDeliveryMethod.valueOf(mode.toUpperCase(Locale.ROOT));
    }

    private JsonNode suiteConfig() {
        String resource = "suite-config/" + plan().configFile();
        try (InputStream in = AbstractReceiverConformanceTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("No suite configuration " + resource + " on the class path");
            }
            return JsonMapper.builder().build().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        catch (IOException ex) {
            throw new IllegalStateException("Could not read " + resource, ex);
        }
    }

    private static String receiverLog(ConformanceRun run) {
        StringBuilder log = new StringBuilder();
        run.getLog()
            .forEach((entry) -> log.append("  ").append(entry.time()).append(' ').append(entry.message()).append('\n'));
        return log.toString();
    }

}
