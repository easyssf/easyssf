package org.easyssf.receiver.scim;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.scim.SsfScimEvent;
import org.easyssf.core.scim.SsfScimOperation;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.http.JdkSsfHttpClient;
import org.easyssf.receiver.set.InMemorySsfJtiDedupStore;
import org.easyssf.receiver.set.NimbusSsfSetVerifier;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.test.TestTransmitter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import com.nimbusds.jwt.JWTClaimsSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;
import static org.easyssf.core.event.SsfSubjectIdentifiers.scim;

class SsfScimEventHandlerTests {

    private static final String USER_URI = "/Users/2b2f880af6674ac284bae9381673d462";

    private static final TestTransmitter transmitter = new TestTransmitter();

    private final RecordingHandler handler = new RecordingHandler();

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    @Test
    void dispatchesEveryOperationToItsMethod() {
        for (SsfScimOperation operation : SsfScimOperation.values()) {
            for (String eventType : operation.eventTypes()) {
                this.handler.handle(context(Map.of(eventType, Map.of()), scim(USER_URI)));
            }
        }
        assertThat(this.handler.calls).containsExactly("onFeedAdd", "onFeedRemove", "onCreate", "onCreate", "onPatch",
                "onPatch", "onPut", "onPut", "onDelete", "onActivate", "onDeactivate", "onAsyncResponse");
    }

    @Test
    void handlesAllScimEventsOfASetAndIgnoresOtherSets() {
        Map<String, Object> events = new LinkedHashMap<>();
        events.put(SsfEventTypes.SCIM_PROV_CREATE_FULL, Map.of("data", Map.of("userName", "jdoe")));
        events.put(SsfEventTypes.SCIM_FEED_ADD, Map.of());
        events.put(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of());
        this.handler.handle(context(events, scim(USER_URI, "jdoe")));
        this.handler.handle(context(Map.of(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of()), opaque("abc")));
        assertThat(this.handler.calls).containsExactly("onCreate", "onFeedAdd");
        assertThat(this.handler.events).allSatisfy((event) -> {
            assertThat(event.subject().externalId()).isEqualTo("jdoe");
            assertThat(event.subject().resourceType()).isEqualTo("Users");
        });
        assertThat(this.handler.events.get(0).data()).containsEntry("userName", "jdoe");
    }

    @Test
    void scimSetPassesStrictVerificationAndReachesTheHandler() {
        transmitter.publishJwks(transmitter.key());
        NimbusSsfSetVerifier verifier = new NimbusSsfSetVerifier(transmitter.issuer(), transmitter::jwksUri,
                new JdkSsfHttpClient());
        verifier.setExpectedAudience(TestTransmitter.AUDIENCE);
        SsfSetProcessor processor = new SsfSetProcessor(verifier, new InMemorySsfJtiDedupStore(10),
                List.of(this.handler));
        JWTClaimsSet claims = transmitter
            .setClaims("ScimProvPatchNotice", scim(USER_URI, "jdoe"),
                    Map.of("attributes", List.of("members"), "version", "a330bc54f0671c9"))
            .claim("txn", "b7b953f11cc6489bbfb87834747cc4c1")
            .build();

        assertThat(processor.process(transmitter.signSet(claims))).isEqualTo(SsfSetProcessor.Outcome.HANDLED);

        assertThat(this.handler.calls).containsExactly("onPatch");
        SsfScimEvent event = this.handler.events.get(0);
        assertThat(event.isNotice()).isTrue();
        assertThat(event.attributes()).containsExactly("members");
        assertThat(event.version()).isEqualTo("a330bc54f0671c9");
        assertThat(event.subject().id()).isEqualTo("2b2f880af6674ac284bae9381673d462");
        assertThat(this.handler.contexts.get(0).eventToken().txn()).isEqualTo("b7b953f11cc6489bbfb87834747cc4c1");
    }

    private static SsfEventContext context(Map<String, Object> events, Map<String, Object> subjectId) {
        return new SsfEventContext(new SsfEventToken("jti-1", "https://scim.example.com",
                Instant.parse("2026-10-04T10:00:00Z"), List.of(), events, subjectId, null, Map.of()));
    }

    private static final class RecordingHandler extends SsfScimEventHandler {

        private final List<String> calls = new ArrayList<>();

        private final List<SsfScimEvent> events = new ArrayList<>();

        private final List<SsfEventContext> contexts = new ArrayList<>();

        @Override
        protected void onFeedAdd(SsfScimEvent event, SsfEventContext eventContext) {
            record("onFeedAdd", event, eventContext);
        }

        @Override
        protected void onFeedRemove(SsfScimEvent event, SsfEventContext eventContext) {
            record("onFeedRemove", event, eventContext);
        }

        @Override
        protected void onCreate(SsfScimEvent event, SsfEventContext eventContext) {
            record("onCreate", event, eventContext);
        }

        @Override
        protected void onPatch(SsfScimEvent event, SsfEventContext eventContext) {
            record("onPatch", event, eventContext);
        }

        @Override
        protected void onPut(SsfScimEvent event, SsfEventContext eventContext) {
            record("onPut", event, eventContext);
        }

        @Override
        protected void onDelete(SsfScimEvent event, SsfEventContext eventContext) {
            record("onDelete", event, eventContext);
        }

        @Override
        protected void onActivate(SsfScimEvent event, SsfEventContext eventContext) {
            record("onActivate", event, eventContext);
        }

        @Override
        protected void onDeactivate(SsfScimEvent event, SsfEventContext eventContext) {
            record("onDeactivate", event, eventContext);
        }

        @Override
        protected void onAsyncResponse(SsfScimEvent event, SsfEventContext eventContext) {
            record("onAsyncResponse", event, eventContext);
        }

        private void record(String method, SsfScimEvent event, SsfEventContext eventContext) {
            this.calls.add(method);
            this.events.add(event);
            this.contexts.add(eventContext);
        }

    }

}
