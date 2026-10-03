package org.easyssf.receiver.transmitter;

import java.util.List;

import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.push.SsfPushHandler;
import org.easyssf.receiver.push.SsfPushResponse;
import org.easyssf.receiver.set.InMemorySsfJtiDedupStore;
import org.easyssf.receiver.set.IssuerRoutingSsfSetVerifier;
import org.easyssf.receiver.set.NimbusSsfSetVerifier;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.set.SsfSetVerificationException;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import com.nimbusds.jwt.JWTClaimsSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

/**
 * A receiver with two transmitters, assembled by hand: SETs are verified by the
 * transmitter that issued them, and each transmitter authenticates with its own header.
 */
class SsfTransmittersTests {

    private static final TestTransmitter keycloak = new TestTransmitter();

    private static final TestTransmitter okta = new TestTransmitter();

    private final SsfHttpClient httpClient = new JdkSsfHttpClient();

    @AfterAll
    static void stopTransmitters() {
        keycloak.close();
        okta.close();
    }

    @Test
    void verifiesSetsOfEveryTransmitterByIssuer() {
        SsfTransmitters transmitters = transmitters();
        assertThat(transmitters.verifier().verify(set(keycloak)).iss()).isEqualTo(keycloak.issuer());
        assertThat(transmitters.verifier().verify(set(okta)).iss()).isEqualTo(okta.issuer());
        assertThat(transmitters.verifier()).isInstanceOf(IssuerRoutingSsfSetVerifier.class);
    }

    @Test
    void rejectsSetsOfUnknownIssuersAndGarbage() {
        SsfTransmitters transmitters = transmitters();
        TestTransmitter stranger = new TestTransmitter();
        try {
            assertThatThrownBy(() -> transmitters.verifier().verify(set(stranger)))
                .isInstanceOf(SsfSetVerificationException.class)
                .extracting("errorCode")
                .isEqualTo(SsfSetVerificationException.INVALID_ISSUER);
            assertThatThrownBy(() -> transmitters.verifier().verify("not a jwt"))
                .isInstanceOf(SsfSetVerificationException.class)
                .extracting("errorCode")
                .isEqualTo(SsfSetVerificationException.INVALID_REQUEST);
        }
        finally {
            stranger.close();
        }
    }

    @Test
    void pushAuthenticatesEveryTransmitterWithItsOwnHeader() {
        SsfTransmitters transmitters = transmitters();
        SsfSetProcessor processor = new SsfSetProcessor(transmitters.verifier(), new InMemorySsfJtiDedupStore(10),
                List.of((SsfEventHandler) (eventContext) -> {
                }));
        SsfPushHandler pushHandler = new SsfPushHandler(processor, transmitters::pushAuthorizationHeader);
        assertThat(pushHandler.handle("Bearer keycloak-secret", set(keycloak).getBytes()).status()).isEqualTo(202);
        assertThat(pushHandler.handle("Bearer okta-secret", set(keycloak).getBytes()).status()).isEqualTo(401);
        assertThat(pushHandler.handle(null, set(keycloak).getBytes()).status()).isEqualTo(401);
        // okta expects no header
        assertThat(pushHandler.handle(null, set(okta).getBytes()).status()).isEqualTo(202);
        SsfPushResponse garbage = pushHandler.handle(null, "not a jwt".getBytes());
        assertThat(garbage.status()).isEqualTo(400);
    }

    @Test
    void looksUpTransmittersByNameAndIssuer() {
        SsfTransmitters transmitters = transmitters();
        assertThat(transmitters.all()).extracting(SsfTransmitter::getName).containsExactly("keycloak", "okta");
        assertThat(transmitters.get("okta")).get().extracting(SsfTransmitter::getIssuer).isEqualTo(okta.issuer());
        assertThat(transmitters.byIssuer(keycloak.issuer())).get()
            .extracting(SsfTransmitter::getName)
            .isEqualTo("keycloak");
        assertThat(transmitters.get("auth0")).isEmpty();
        assertThat(transmitters.primary()).isEmpty();
        assertThat(transmitters.streamVerification(okta.issuer())).isNotNull();
        assertThat(transmitters.streamVerification("https://other.example")).isNull();
    }

    @Test
    void primaryIsTheOnlyOrTheDefaultTransmitter() {
        SsfTransmitter only = transmitter("okta", okta, null);
        assertThat(new SsfTransmitters(List.of(only)).primary()).contains(only);
        SsfTransmitter dflt = transmitter(SsfTransmitter.DEFAULT_NAME, keycloak, null);
        assertThat(new SsfTransmitters(List.of(only, dflt)).primary()).contains(dflt);
    }

    @Test
    void rejectsDuplicateNamesAndIssuers() {
        assertThatIllegalArgumentException()
            .isThrownBy(
                    () -> new SsfTransmitters(List.of(transmitter("a", keycloak, null), transmitter("a", okta, null))))
            .withMessageContaining("named 'a'");
        assertThatIllegalArgumentException().isThrownBy(
                () -> new SsfTransmitters(List.of(transmitter("a", keycloak, null), transmitter("b", keycloak, null))))
            .withMessageContaining("same issuer");
        assertThatIllegalArgumentException().isThrownBy(() -> new SsfTransmitters(List.of()));
    }

    private SsfTransmitters transmitters() {
        return new SsfTransmitters(
                List.of(transmitter("keycloak", keycloak, "Bearer keycloak-secret"), transmitter("okta", okta, null)));
    }

    private SsfTransmitter transmitter(String name, TestTransmitter transmitter, String pushHeader) {
        SsfTransmitterMetadataResolver metadataResolver = new SsfTransmitterMetadataResolver(transmitter.issuer(), null,
                this.httpClient);
        NimbusSsfSetVerifier verifier = new NimbusSsfSetVerifier(transmitter.issuer(),
                () -> metadataResolver.resolve().jwksUri().toString(), this.httpClient);
        verifier.setExpectedAudience(TestTransmitter.AUDIENCE);
        return SsfTransmitter.builder(name, transmitter.issuer())
            .metadataResolver(metadataResolver)
            .verifier(verifier)
            .pushAuthorizationHeader(pushHeader)
            .build();
    }

    private static String set(TestTransmitter transmitter) {
        JWTClaimsSet claims = transmitter.setClaims("CaepSessionRevoked", opaque("session-1"))
            .audience(TestTransmitter.AUDIENCE)
            .build();
        return transmitter.signSet(claims);
    }

}
