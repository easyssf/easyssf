package org.easyssf.conformance.receiver;

import java.util.Map;

/**
 * SSF 1.0 with the CAEP interop profile 1.0, push delivery.
 */
class CaepInterop10PushReceiverConformanceTest extends AbstractReceiverConformanceTest {

    @Override
    protected String planName() {
        return "openid-ssf-receiver-caep-test-plan";
    }

    @Override
    protected Map<String, String> planVariant() {
        return Map.of("ssf_delivery_mode", "push", "ssf_auth_mode", "dynamic", "client_auth_type",
                "client_secret_basic");
    }

    @Override
    protected String suiteConfigFile() {
        return "receiver-ssf1.0-caepiop1.0-push.json";
    }

}
