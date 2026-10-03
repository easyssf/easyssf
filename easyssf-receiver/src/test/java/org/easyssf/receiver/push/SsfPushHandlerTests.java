package org.easyssf.receiver.push;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfSubject;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.http.SsfHttpClient;
import org.easyssf.receiver.set.InMemorySsfJtiDedupStore;
import org.easyssf.receiver.set.NimbusSsfSetVerifier;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.transmitter.SsfTransmitterMetadataResolver;
import org.easyssf.test.TestTransmitter;
import org.easyssf.test.TestTransmitter.MetadataLocation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.util.JSONObjectUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easyssf.core.event.SsfSubjectIdentifiers.complex;
import static org.easyssf.core.event.SsfSubjectIdentifiers.issSub;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

/**
 * A receiver assembled by hand, the way an application without a framework integration
 * would do it.
 */
class SsfPushHandlerTests {

    private static final String AUTHORIZATION = "Bearer push-secret";

    private static final TestTransmitter transmitter = new TestTransmitter();

    private final List<SsfSubject> revoked = new ArrayList<>();

    private final List<RuntimeException> failures = new ArrayList<>();

    private SsfPushHandler pushHandler;

    @BeforeEach
    void setUp() {
        this.pushHandler = pushHandler(transmitter);
    }

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    private SsfPushHandler pushHandler(TestTransmitter transmitter) {
        SsfHttpClient httpClient = new JdkSsfHttpClient();
        SsfTransmitterMetadataResolver metadataResolver = new SsfTransmitterMetadataResolver(transmitter.issuer(), null,
                httpClient);
        NimbusSsfSetVerifier verifier = new NimbusSsfSetVerifier(transmitter.issuer(),
                () -> metadataResolver.resolve().jwksUri().toString(), httpClient);
        verifier.setExpectedAudience(TestTransmitter.AUDIENCE);
        SsfEventHandler handler = (eventContext) -> {
            if (!this.failures.isEmpty()) {
                throw this.failures.remove(0);
            }
            if (eventContext.hasEvent("CaepSessionRevoked")) {
                this.revoked.add(eventContext.subjectFor("CaepSessionRevoked"));
            }
        };
        SsfSetProcessor processor = new SsfSetProcessor(verifier, new InMemorySsfJtiDedupStore(100), List.of(handler));
        return new SsfPushHandler(processor, AUTHORIZATION);
    }

    @Test
    void acceptsAndHandlesSet() {
        SsfPushResponse response = push(AUTHORIZATION, sessionRevoked("session-1"));
        assertThat(response.status()).isEqualTo(202);
        assertThat(response.isAccepted()).isTrue();
        assertThat(response.body()).isNull();
        assertThat(this.revoked).extracting(SsfSubject::sessionId).containsExactly("session-1");
        assertThat(this.revoked).extracting(SsfSubject::subject).containsExactly("alice");
    }

    @Test
    void acceptsDuplicateWithoutHandlingItAgain() {
        String set = sessionRevoked("session-2");
        assertThat(push(AUTHORIZATION, set).status()).isEqualTo(202);
        assertThat(push(AUTHORIZATION, set).status()).isEqualTo(202);
        assertThat(this.revoked).hasSize(1);
    }

    @Test
    void rejectsTransmitterThatDoesNotAuthenticate() throws Exception {
        for (String authorization : new String[] { null, "Bearer wrong" }) {
            SsfPushResponse response = push(authorization, sessionRevoked("session-3"));
            assertThat(response.status()).isEqualTo(401);
            assertThat(error(response)).containsEntry("err", "authentication_failed");
        }
        assertThat(this.revoked).isEmpty();
    }

    @Test
    void rejectsInvalidSetWithErrorDocument() throws Exception {
        SsfPushResponse malformed = push(AUTHORIZATION, "not-a-set");
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(error(malformed)).containsEntry("err", "invalid_request").containsKey("description");

        SsfPushResponse wrongAudience = push(AUTHORIZATION, transmitter.signSet(
                transmitter.setClaims("CaepSessionRevoked", opaque("x")).audience("https://other.example").build()));
        assertThat(wrongAudience.status()).isEqualTo(400);
        assertThat(error(wrongAudience)).containsEntry("err", "invalid_audience");
    }

    @Test
    void rejectsOversizedSet() {
        byte[] body = new byte[SsfPushHandler.MAX_SET_SIZE + 1];
        assertThat(this.pushHandler.handle(AUTHORIZATION, body).status()).isEqualTo(413);
    }

    @Test
    void asksForRedeliveryWhenHandlerFails() {
        String set = sessionRevoked("session-4");
        this.failures.add(new IllegalStateException("store is down"));
        assertThat(push(AUTHORIZATION, set).status()).isEqualTo(500);
        assertThat(push(AUTHORIZATION, set).status()).isEqualTo(202);
        assertThat(this.revoked).hasSize(1);
    }

    @Test
    void asksForRedeliveryWhileTransmitterKeysAreUnavailable() {
        try (TestTransmitter unavailable = new TestTransmitter(MetadataLocation.NONE)) {
            SsfPushHandler handler = pushHandler(unavailable);
            String set = unavailable.set("CaepSessionRevoked",
                    complex(issSub(unavailable.issuer(), "alice"), opaque("session-5")));
            assertThat(handler.handle(AUTHORIZATION, set.getBytes(StandardCharsets.UTF_8)).status()).isEqualTo(503);
            unavailable.publishMetadata(MetadataLocation.SSF);
            assertThat(handler.handle(AUTHORIZATION, set.getBytes(StandardCharsets.UTF_8)).status()).isEqualTo(202);
        }
    }

    private SsfPushResponse push(String authorization, String set) {
        return this.pushHandler.handle(authorization, set.getBytes(StandardCharsets.UTF_8));
    }

    private static String sessionRevoked(String sessionId) {
        return transmitter.set("CaepSessionRevoked", complex(issSub(transmitter.issuer(), "alice"), opaque(sessionId)));
    }

    private static Map<String, Object> error(SsfPushResponse response) throws Exception {
        return JSONObjectUtils.parse(response.body());
    }

}
