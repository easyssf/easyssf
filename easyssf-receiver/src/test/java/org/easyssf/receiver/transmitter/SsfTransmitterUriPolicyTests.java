package org.easyssf.receiver.transmitter;

import java.net.URI;

import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

class SsfTransmitterUriPolicyTests {

    private final SsfTransmitterUriPolicy policy = SsfTransmitterUriPolicy.DEFAULT;

    @Test
    void acceptsHttpsIssuerWithPath() {
        assertThatNoException().isThrownBy(() -> this.policy.checkIssuer("https://idp.example/realms/demo"));
        assertThatNoException().isThrownBy(() -> this.policy.checkIssuer("HTTPS://idp.example:8443"));
    }

    @Test
    void rejectsHttpIssuerExceptOnLoopback() {
        assertThatIllegalArgumentException().isThrownBy(() -> this.policy.checkIssuer("http://idp.example/realms/demo"))
            .withMessageContaining("must use https");
        assertThatNoException().isThrownBy(() -> this.policy.checkIssuer("http://localhost:8080/realms/demo"));
        assertThatNoException().isThrownBy(() -> this.policy.checkIssuer("http://127.0.0.1:8080/realms/demo"));
        assertThatNoException().isThrownBy(() -> this.policy.checkIssuer("http://[::1]:8080/realms/demo"));
        assertThatNoException().isThrownBy(() -> this.policy.checkIssuer("http://idp.localhost/realms/demo"));
    }

    @Test
    void insecurePolicyAcceptsHttpAnywhere() {
        assertThatNoException()
            .isThrownBy(() -> SsfTransmitterUriPolicy.INSECURE.checkIssuer("http://idp.example/realms/demo"));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SsfTransmitterUriPolicy.INSECURE.checkIssuer("file:///etc/passwd"));
    }

    @Test
    void rejectsIssuerWithQueryFragmentOrWithoutHost() {
        assertThatIllegalArgumentException().isThrownBy(() -> this.policy.checkIssuer("https://idp.example/x?y=z"))
            .withMessageContaining("query or fragment");
        assertThatIllegalArgumentException().isThrownBy(() -> this.policy.checkIssuer("https://idp.example/x#y"))
            .withMessageContaining("query or fragment");
        assertThatIllegalArgumentException().isThrownBy(() -> this.policy.checkIssuer("/realms/demo"));
        assertThatIllegalArgumentException().isThrownBy(() -> this.policy.checkIssuer("https:///x"));
        assertThatIllegalArgumentException().isThrownBy(() -> this.policy.checkIssuer("not a uri"));
    }

    @Test
    void endpointsFollowTheSameRulesButMayHaveAQuery() {
        assertThatNoException()
            .isThrownBy(() -> this.policy.checkEndpoint(URI.create("https://idp.example/streams?x=1"), "endpoint"));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> this.policy.checkEndpoint(URI.create("http://idp.example/jwks"), "jwks_uri"))
            .withMessageContaining("jwks_uri");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> this.policy.checkEndpoint(URI.create("https://idp.example/jwks#x"), "jwks_uri"));
        assertThatIllegalArgumentException().isThrownBy(() -> this.policy.checkEndpoint(null, "jwks_uri"));
    }

    @Test
    void resolverAppliesThePolicyToTheIssuerAndTheMetadataUrl() {
        JdkSsfHttpClient http = new JdkSsfHttpClient();
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new SsfTransmitterMetadataResolver("http://idp.example", null, http));
        assertThatIllegalArgumentException().isThrownBy(() -> new SsfTransmitterMetadataResolver("https://idp.example",
                URI.create("http://idp.example/.well-known/ssf-configuration"), http));
        assertThatNoException().isThrownBy(() -> new SsfTransmitterMetadataResolver("http://idp.example", null, http,
                SsfTransmitterUriPolicy.INSECURE));
    }

}
