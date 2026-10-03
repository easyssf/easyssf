package org.easyssf.conformance.receiver;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.http.SsfHttpRequest;
import org.easyssf.receiver.http.SsfHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Looks up the test modules running in the conformance suite, so that a run can be
 * started for the module that waits for the receiver without knowing its name. The suite
 * is the one the transmitter issuer belongs to: a test instance is
 * {@code <suite>/test/a/<alias>}. Its API ({@code /api/runner/running},
 * {@code /api/info/<id>}) needs no login with the suite's {@code dev} profile, as the
 * local suite runs it.
 */
@Component
public class ConformanceSuiteModules {

    /**
     * The status of a module that waits for the receiver to act.
     */
    static final String WAITING = "WAITING";

    private static final Pattern TEST_INSTANCE = Pattern.compile("^(?<suite>https?://.+?)/test/a/(?<alias>[^/?#]+)/?$");

    private final CtsProperties properties;

    private final SsfHttpClient httpClient;

    private final JsonMapper json = JsonMapper.builder().build();

    ConformanceSuiteModules(CtsProperties properties, SsfHttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    /**
     * A test module running in the suite.
     * @param id the id of the module in the suite
     * @param name the name of the test module, e.g.
     * {@code openid-ssf-receiver-stream-create-delete}
     * @param alias the alias of the test instance the module runs under
     * @param status the status reported by the suite; {@code WAITING} means it waits for
     * the receiver
     * @param deliveryMethod the delivery mode of the module's variant
     * @param issuer the transmitter issuer the receiver has to use for this module
     * @param scenario the scenario the receiver plays for the module
     * @param logUrl the suite's log page of the module
     */
    public record Module(String id, String name, String alias, String status, SsfDeliveryMethod deliveryMethod,
            String issuer, ConformanceScenario scenario, URI logUrl) {

        boolean isWaiting() {
            return WAITING.equals(this.status);
        }

    }

    /**
     * The suite and the alias a transmitter issuer refers to.
     */
    public record TestInstance(URI suite, String alias) {

        static TestInstance parse(String issuer) {
            Matcher matcher = TEST_INSTANCE.matcher(issuer.trim());
            Assert.isTrue(matcher.matches(), () -> "The transmitter issuer '" + issuer
                    + "' is not a test instance of the conformance suite (<suite>/test/a/<alias>)");
            return new TestInstance(URI.create(matcher.group("suite") + "/"), matcher.group("alias"));
        }

        String issuer(String alias) {
            return this.suite.resolve("test/a/" + alias).toString();
        }

    }

    /**
     * The test instance of the given issuer, or of the configured one
     * ({@code cts.transmitter.issuer}) if it is {@code null}.
     */
    public TestInstance testInstance(String issuer) {
        if (!StringUtils.hasText(issuer)) {
            issuer = this.properties.getTransmitter().getIssuer();
            Assert.hasText(issuer, "cts.transmitter.issuer must be set when no issuer is given");
        }
        return TestInstance.parse(issuer);
    }

    /**
     * All modules currently running in the suite, in the order the suite lists them.
     */
    public List<Module> running(TestInstance instance) {
        List<Module> modules = new ArrayList<>();
        for (JsonNode id : get(instance.suite().resolve("api/runner/running"))) {
            JsonNode info = get(instance.suite().resolve("api/info/" + id.asString()));
            modules.add(module(instance, id.asString(), info));
        }
        return modules;
    }

    /**
     * The modules waiting for the receiver that a run should be started for: the ones
     * running under the alias of the test instance, or, if there is none, all waiting
     * modules of the suite (another alias than configured may have been used to create
     * the plan).
     */
    public List<Module> waiting(TestInstance instance) {
        List<Module> waiting = running(instance).stream().filter(Module::isWaiting).toList();
        List<Module> ofInstance = waiting.stream().filter((module) -> instance.alias().equals(module.alias())).toList();
        return ofInstance.isEmpty() ? waiting : ofInstance;
    }

    private Module module(TestInstance instance, String id, JsonNode info) {
        String name = info.path("testName").asString("");
        String alias = info.path("alias").asString(instance.alias());
        String deliveryMode = info.path("variant").path("ssf_delivery_mode").asString(null);
        SsfDeliveryMethod deliveryMethod = (deliveryMode != null)
                ? SsfDeliveryMethod.valueOf(deliveryMode.toUpperCase(Locale.ROOT)) : null;
        return new Module(id, name, alias, info.path("status").asString(""), deliveryMethod, instance.issuer(alias),
                ConformanceScenario.forModule(name), instance.suite().resolve("log-detail.html?log=" + id));
    }

    private JsonNode get(URI uri) {
        SsfHttpResponse response;
        try {
            response = this.httpClient
                .execute(new SsfHttpRequest("GET", uri, Map.of("Accept", "application/json"), null));
        }
        catch (IOException ex) {
            throw new IllegalStateException("The conformance suite could not be reached at " + uri, ex);
        }
        if (response.status() != 200) {
            throw new IllegalStateException("The conformance suite API " + uri + " returned HTTP " + response.status()
                    + ": " + response.body());
        }
        return this.json.readTree(response.body());
    }

}
