package org.easyssf.receiver.set;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.Set;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.event.SubjectCompatibilityMode;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.transmitter.SsfTransmitterUnavailableException;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.easyssf.core.event.SsfSubjectIdentifiers.complex;
import static org.easyssf.core.event.SsfSubjectIdentifiers.issSub;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

class NimbusSsfSetVerifierTests {

    private static final JOSEObjectType SECEVENT = new JOSEObjectType("secevent+jwt");

    private static final TestTransmitter transmitter = new TestTransmitter();

    private NimbusSsfSetVerifier verifier;

    @BeforeEach
    void setUp() {
        transmitter.publishJwks(transmitter.key());
        this.verifier = verifier(transmitter.jwksUri());
    }

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    private static NimbusSsfSetVerifier verifier(String jwksUri) {
        NimbusSsfSetVerifier verifier = new NimbusSsfSetVerifier(transmitter.issuer(), () -> jwksUri,
                new JdkSsfHttpClient());
        verifier.setExpectedAudience(TestTransmitter.AUDIENCE);
        return verifier;
    }

    @Test
    void verifiesSet() {
        JWTClaimsSet claims = sessionRevoked().claim("txn", "txn-1").build();
        SsfEventToken eventToken = this.verifier.verify(transmitter.signSet(claims));
        assertThat(eventToken.jti()).isEqualTo(claims.getJWTID());
        assertThat(eventToken.iss()).isEqualTo(transmitter.issuer());
        assertThat(eventToken.iat()).isEqualTo(claims.getIssueTime().toInstant().truncatedTo(ChronoUnit.SECONDS));
        assertThat(eventToken.aud()).containsExactly(TestTransmitter.AUDIENCE);
        assertThat(eventToken.events()).containsOnlyKeys(SsfEventTypes.CAEP_SESSION_REVOKED);
        assertThat(eventToken.subjectId()).containsEntry("format", "complex");
        assertThat(eventToken.txn()).isEqualTo("txn-1");
        assertThat(eventToken.claims()).containsKeys("iss", "jti", "iat", "aud", "events", "sub_id", "txn");
    }

    @Test
    void rejectsMalformedSet() {
        assertRejected("not-a-jwt", SsfSetVerificationException.INVALID_REQUEST);
    }

    @Test
    void rejectsSetSignedWithUnknownKey() {
        RSAKey otherKey = TestTransmitter.generateKey(2048, transmitter.key().getKeyID());
        assertRejected(TestTransmitter.sign(otherKey, SECEVENT, sessionRevoked().build()),
                SsfSetVerificationException.INVALID_KEY);
    }

    @Test
    void rejectsUnsignedSet() {
        String unsigned = new com.nimbusds.jwt.PlainJWT(sessionRevoked().build()).serialize();
        assertRejected(unsigned, SsfSetVerificationException.INVALID_REQUEST);
    }

    @Test
    void rejectsAlgorithmThatIsNotAccepted() {
        String set = TestTransmitter.sign(transmitter.key(), JWSAlgorithm.RS512, SECEVENT, sessionRevoked().build());
        assertRejected(set, SsfSetVerificationException.INVALID_KEY);
        this.verifier = verifier(transmitter.jwksUri());
        this.verifier.setAcceptedAlgorithms(Set.of("RS256", "RS512"));
        assertThat(this.verifier.verify(set)).isNotNull();
    }

    @Test
    void rejectsSigningKeyBelowMinimumSize() {
        RSAKey weakKey = TestTransmitter.generateKey(1024, "weak-key");
        transmitter.publishJwks(weakKey);
        String set = TestTransmitter.sign(weakKey, SECEVENT, sessionRevoked().build());
        assertRejected(set, SsfSetVerificationException.INVALID_KEY);
        this.verifier = verifier(transmitter.jwksUri());
        this.verifier.setMinRsaKeySize(0);
        assertThat(this.verifier.verify(set)).isNotNull();
    }

    @Test
    void rejectsSetThatIsNotTypedAsSecurityEvent() {
        String set = TestTransmitter.sign(transmitter.key(), JOSEObjectType.JWT, sessionRevoked().build());
        assertRejected(set, SsfSetVerificationException.INVALID_REQUEST);
        this.verifier = verifier(transmitter.jwksUri());
        this.verifier.setRequireTypeHeader(false);
        assertThat(this.verifier.verify(set)).isNotNull();
    }

    @Test
    void rejectsAccessTokenPresentedAsSet() {
        assertRejected(transmitter.accessToken("alice", "session-1", Instant.now()),
                SsfSetVerificationException.INVALID_REQUEST);
    }

    @Test
    void rejectsSetOfAnotherIssuer() {
        assertRejected(transmitter.signSet(sessionRevoked().issuer("https://other.example").build()),
                SsfSetVerificationException.INVALID_ISSUER);
    }

    @Test
    void rejectsSetForAnotherAudience() {
        assertRejected(transmitter.signSet(sessionRevoked().audience("https://other.example").build()),
                SsfSetVerificationException.INVALID_AUDIENCE);
    }

    @Test
    void acceptsAnyAudienceWhenNoneIsExpected() {
        this.verifier.setExpectedAudience(null);
        assertThat(
                this.verifier.verify(transmitter.signSet(sessionRevoked().audience("https://other.example").build())))
            .isNotNull();
    }

    @Test
    void rejectsSetWithoutRequiredClaims() {
        assertRejected(transmitter.signSet(sessionRevoked().jwtID(null).build()),
                SsfSetVerificationException.INVALID_REQUEST);
        assertRejected(transmitter.signSet(sessionRevoked().issueTime(null).build()),
                SsfSetVerificationException.INVALID_REQUEST);
        assertRejected(transmitter.signSet(sessionRevoked().claim("events", null).build()),
                SsfSetVerificationException.INVALID_REQUEST);
    }

    @Test
    void rejectsSetWithoutTopLevelSubjectUnlessAllowed() {
        // the subject in the event payload, as in earlier SSF drafts
        Map<String, Object> event = Map.of("subject", complex(issSub(transmitter.issuer(), "alice"), opaque("s-1")),
                "event_timestamp", Instant.now().getEpochSecond());
        String legacy = transmitter.signSet(transmitter.setClaims("CaepSessionRevoked", null, event).build());
        assertRejected(legacy, SsfSetVerificationException.INVALID_REQUEST);
        assertRejected(transmitter.signSet(sessionRevoked().claim("sub_id", Map.of()).build()),
                SsfSetVerificationException.INVALID_REQUEST);

        this.verifier.setSubjectCompatibilityMode(SubjectCompatibilityMode.LEGACY);
        assertThat(this.verifier.verify(legacy).subjectId()).isNull();
    }

    @Test
    void rejectsSetIssuedInTheFuture() {
        Date future = Date.from(Instant.now().plusSeconds(600));
        assertRejected(transmitter.signSet(sessionRevoked().issueTime(future).build()),
                SsfSetVerificationException.INVALID_REQUEST);
    }

    @Test
    void unreachableJwkSetIsReportedAsUnavailable() {
        NimbusSsfSetVerifier verifier = verifier(transmitter.issuer() + "/missing-jwks");
        String set = transmitter.signSet(sessionRevoked().build());
        assertThatExceptionOfType(SsfTransmitterUnavailableException.class).isThrownBy(() -> verifier.verify(set));
    }

    private void assertRejected(String set, String errorCode) {
        assertThatExceptionOfType(SsfSetVerificationException.class).isThrownBy(() -> this.verifier.verify(set))
            .satisfies((ex) -> assertThat(ex.getErrorCode()).isEqualTo(errorCode));
    }

    private static JWTClaimsSet.Builder sessionRevoked() {
        return transmitter.setClaims("CaepSessionRevoked",
                complex(issSub(transmitter.issuer(), "alice"), opaque("session-1")));
    }

}
