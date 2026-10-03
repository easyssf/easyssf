package org.easyssf.test.conformance.receiver;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.http.SsfHttpResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ConformanceSuiteModulesTests {

    private static final String SUITE = "https://localhost.emobix.co.uk:8443/";

    private static final String ISSUER = SUITE + "test/a/easyssf-receiver-ssf1_0-caepiop1_0-push";

    private final ConformanceReceiverSettings properties = new ConformanceReceiverSettings();

    @Test
    void parsesTheTestInstanceOfTheIssuer() {
        ConformanceSuiteModules.TestInstance instance = ConformanceSuiteModules.TestInstance.parse(ISSUER + "/");
        assertThat(instance.suite()).isEqualTo(URI.create(SUITE));
        assertThat(instance.alias()).isEqualTo("easyssf-receiver-ssf1_0-caepiop1_0-push");
        assertThat(instance.issuer("other")).isEqualTo(SUITE + "test/a/other");

        assertThat(ConformanceSuiteModules.TestInstance.parse("https://suite.example.com/suite/test/a/alias").suite())
            .isEqualTo(URI.create("https://suite.example.com/suite/"));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> ConformanceSuiteModules.TestInstance.parse("https://suite.example.com/"));
    }

    @Test
    void usesTheConfiguredIssuerByDefault() {
        this.properties.getTransmitter().setIssuer(ISSUER);
        ConformanceSuiteModules modules = new ConformanceSuiteModules(this.properties, suite(Map.of()));
        assertThat(modules.testInstance(null).alias()).isEqualTo("easyssf-receiver-ssf1_0-caepiop1_0-push");
        assertThat(modules.testInstance(SUITE + "test/a/other").alias()).isEqualTo("other");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ConformanceSuiteModules(new ConformanceReceiverSettings(), suite(Map.of()))
                .testInstance(null));
    }

    @Test
    void listsTheRunningModulesWithTheirScenario() {
        ConformanceSuiteModules modules = new ConformanceSuiteModules(this.properties,
                suite(Map.of("api/runner/running", "[\"abc\",\"def\"]", "api/info/abc",
                        info("openid-ssf-receiver-stream-create-delete", "easyssf-receiver-ssf1_0-caepiop1_0-push",
                                "WAITING", "push"),
                        "api/info/def", info("openid-ssf-receiver-happypath", "other", "RUNNING", "poll"))));

        List<ConformanceSuiteModules.Module> running = modules
            .running(ConformanceSuiteModules.TestInstance.parse(ISSUER));
        assertThat(running).hasSize(2);
        ConformanceSuiteModules.Module first = running.get(0);
        assertThat(first.id()).isEqualTo("abc");
        assertThat(first.name()).isEqualTo("openid-ssf-receiver-stream-create-delete");
        assertThat(first.alias()).isEqualTo("easyssf-receiver-ssf1_0-caepiop1_0-push");
        assertThat(first.status()).isEqualTo("WAITING");
        assertThat(first.deliveryMethod()).isEqualTo(SsfDeliveryMethod.PUSH);
        assertThat(first.issuer()).isEqualTo(ISSUER);
        assertThat(first.scenario()).isEqualTo(ConformanceScenario.CREATE_DELETE);
        assertThat(first.logUrl()).isEqualTo(URI.create(SUITE + "log-detail.html?log=abc"));
        ConformanceSuiteModules.Module second = running.get(1);
        assertThat(second.deliveryMethod()).isEqualTo(SsfDeliveryMethod.POLL);
        assertThat(second.issuer()).isEqualTo(SUITE + "test/a/other");
        assertThat(second.scenario()).isEqualTo(ConformanceScenario.STREAM_MANAGEMENT);
    }

    @Test
    void prefersTheWaitingModulesOfTheConfiguredAlias() {
        ConformanceSuiteModules modules = new ConformanceSuiteModules(this.properties,
                suite(Map.of("api/runner/running", "[\"abc\",\"def\",\"ghi\"]", "api/info/abc",
                        info("openid-ssf-receiver-happypath", "other", "WAITING", "push"), "api/info/def",
                        info("openid-ssf-receiver-stream-create-delete", "easyssf-receiver-ssf1_0-caepiop1_0-push",
                                "WAITING", "push"),
                        "api/info/ghi", info("openid-ssf-receiver-removed-subject-event",
                                "easyssf-receiver-ssf1_0-caepiop1_0-push", "RUNNING", "push"))));
        ConformanceSuiteModules.TestInstance instance = ConformanceSuiteModules.TestInstance.parse(ISSUER);

        assertThat(modules.waiting(instance)).extracting(ConformanceSuiteModules.Module::id).containsExactly("def");
        // without a module under the configured alias, any waiting module will do
        assertThat(modules.waiting(new ConformanceSuiteModules.TestInstance(URI.create(SUITE), "unused")))
            .extracting(ConformanceSuiteModules.Module::id)
            .containsExactly("abc", "def");
    }

    @Test
    void reportsAnUnreachableSuite() {
        ConformanceSuiteModules modules = new ConformanceSuiteModules(this.properties, suite(Map.of()));
        assertThatIllegalStateException()
            .isThrownBy(() -> modules.running(ConformanceSuiteModules.TestInstance.parse(ISSUER)))
            .withMessageContaining("returned HTTP 404");
    }

    private static String info(String testName, String alias, String status, String deliveryMode) {
        return """
                {"testName":"%s","alias":"%s","status":"%s","variant":{"ssf_delivery_mode":"%s"}}
                """.formatted(testName, alias, status, deliveryMode);
    }

    /**
     * A suite API that answers the given paths (relative to the suite) with the given
     * bodies and everything else with 404.
     */
    private static SsfHttpClient suite(Map<String, String> responses) {
        return (request) -> {
            String path = URI.create(SUITE).relativize(request.uri()).toString();
            String body = responses.get(path);
            return (body != null) ? new SsfHttpResponse(200, Map.of(), body)
                    : new SsfHttpResponse(404, Map.of(), "not found: " + path);
        };
    }

}
