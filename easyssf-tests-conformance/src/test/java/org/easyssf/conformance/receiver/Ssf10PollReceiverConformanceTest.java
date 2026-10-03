package org.easyssf.conformance.receiver;

import java.util.Map;

/**
 * SSF 1.0, default profile, poll delivery.
 */
class Ssf10PollReceiverConformanceTest extends AbstractReceiverConformanceTest {

    @Override
    protected String planName() {
        return "openid-ssf-receiver-test-plan";
    }

    @Override
    protected Map<String, String> planVariant() {
        return Map.of("ssf_delivery_mode", "poll", "ssf_auth_mode", "dynamic", "client_auth_type",
                "client_secret_basic");
    }

    @Override
    protected String suiteConfigFile() {
        return "receiver-ssf1.0-poll.json";
    }

}
