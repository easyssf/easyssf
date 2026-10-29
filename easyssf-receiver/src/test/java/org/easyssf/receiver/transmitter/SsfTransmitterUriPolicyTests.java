package org.easyssf.receiver.transmitter;

import java.net.URI;
import java.util.List;

import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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

    /**
     * The same cases as {@code TestURIPolicyRequiresHTTPSExceptOnLoopback} of the Go
     * library, so that the libraries agree on what a loopback address is.
     */
    @Test
    void loopbackIsLocalhostTheIpv4LoopbackNetworkAndTheIpv6LoopbackAddress() {
        for (String issuer : List.of("http://localhost:8080/realms/demo", "http://LocalHost/x",
                "http://127.0.0.1:8080/x", "http://127.255.255.254/x", "http://127.1.2.3/x", "http://[::1]:8080/x",
                "http://[0:0:0:0:0:0:0:1]/x", "http://[::ffff:127.0.0.1]/x", "http://app.localhost/x")) {
            assertThatNoException().as(issuer).isThrownBy(() -> this.policy.checkIssuer(issuer));
            assertThat(SsfTransmitterUriPolicy.isLoopback(URI.create(issuer))).as(issuer).isTrue();
        }
    }

    @Test
    void hostNameThatMerelyLooksLikeALoopbackAddressIsNotLoopback() {
        // a host name that starts with 127. goes wherever its owner points it, and a
        // shorthand such as 127.1 is not an address either (java.net.URI refuses it as a
        // host, so the message differs)
        for (String issuer : List.of("http://127.attacker.example/x", "http://127.0.0.1.attacker.example/x",
                "http://127.1/x", "http://127/x", "http://127.0.0/x", "http://127.0.0.256/x",
                "http://localhost.attacker.example/x", "http://[::2]/x", "http://[::ffff:10.0.0.1]/x",
                "http://10.0.0.1/x")) {
            assertThatIllegalArgumentException().as(issuer).isThrownBy(() -> this.policy.checkIssuer(issuer));
            assertThat(SsfTransmitterUriPolicy.isLoopback(URI.create(issuer))).as(issuer).isFalse();
        }
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
