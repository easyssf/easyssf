package org.easyssf.test.conformance.receiver;

import java.util.Map;

/**
 * The receiver test plans of the OpenID conformance suite, with the variant and the
 * configuration (in {@code suite-config/} on the class path) the tests create them with.
 * All of them use {@code ssf_auth_mode=dynamic} and
 * {@code client_auth_type=client_secret_basic}.
 */
public enum ConformancePlan {

    SSF_1_0_PUSH("openid-ssf-receiver-test-plan", "push", "receiver-ssf1.0-push.json"),

    SSF_1_0_POLL("openid-ssf-receiver-test-plan", "poll", "receiver-ssf1.0-poll.json"),

    CAEP_INTEROP_1_0_PUSH("openid-ssf-receiver-caep-test-plan", "push", "receiver-ssf1.0-caepiop1.0-push.json"),

    CAEP_INTEROP_1_0_POLL("openid-ssf-receiver-caep-test-plan", "poll", "receiver-ssf1.0-caepiop1.0-poll.json");

    private final String planName;

    private final Map<String, String> variant;

    private final String configFile;

    ConformancePlan(String planName, String deliveryMode, String configFile) {
        this.planName = planName;
        this.variant = Map.of("ssf_delivery_mode", deliveryMode, "ssf_auth_mode", "dynamic", "client_auth_type",
                "client_secret_basic");
        this.configFile = configFile;
    }

    public String planName() {
        return this.planName;
    }

    public Map<String, String> variant() {
        return this.variant;
    }

    /**
     * @return the name of the suite configuration file, in {@code suite-config/} on the
     * class path
     */
    public String configFile() {
        return this.configFile;
    }

}
