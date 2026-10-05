package org.easyssf.receiver.caep;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.core.caep.SsfCaepEvent;
import org.easyssf.core.caep.SsfCaepEventKind;
import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
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
import static org.easyssf.core.event.SsfSubjectIdentifiers.email;
import static org.easyssf.core.event.SsfSubjectIdentifiers.issSub;

class SsfCaepEventHandlerTests {

    private static final String UNKNOWN_CAEP_EVENT = "https://schemas.openid.net/secevent/caep/event-type/geo-fence-exit";

    private static final TestTransmitter transmitter = new TestTransmitter();

    private final RecordingHandler handler = new RecordingHandler();

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    @Test
    void dispatchesEveryKindToItsMethod() {
        for (SsfCaepEventKind kind : SsfCaepEventKind.values()) {
            String eventType = (kind != SsfCaepEventKind.OTHER) ? kind.eventType() : UNKNOWN_CAEP_EVENT;
            this.handler.handle(context(Map.of(eventType, Map.of())));
        }
        assertThat(this.handler.calls).containsExactly("onSessionRevoked", "onTokenClaimsChange", "onCredentialChange",
                "onAssuranceLevelChange", "onDeviceComplianceChange", "onSessionEstablished", "onSessionPresented",
                "onRiskLevelChange", "onOtherCaepEvent");
    }

    @Test
    void handlesAllCaepEventsOfASetAndIgnoresOtherSets() {
        Map<String, Object> events = new LinkedHashMap<>();
        events.put(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of("initiating_entity", "admin"));
        events.put(SsfEventTypes.RISC_ACCOUNT_DISABLED, Map.of("reason", "hijacking"));
        events.put(UNKNOWN_CAEP_EVENT, Map.of("event_timestamp", 1615304991, "zone", "HQ"));
        this.handler.handle(context(events));
        this.handler.handle(context(Map.of(SsfEventTypes.RISC_ACCOUNT_PURGED, Map.of())));
        this.handler.handle(context(Map.of("urn:example:acme:login", Map.of())));
        assertThat(this.handler.calls).containsExactly("onSessionRevoked", "onOtherCaepEvent");
        assertThat(this.handler.events.get(0).initiatingEntity()).isEqualTo("admin");
        SsfCaepEvent other = this.handler.events.get(1);
        assertThat(other.eventType()).isEqualTo(UNKNOWN_CAEP_EVENT);
        assertThat(other.eventTimestamp()).isEqualTo(Instant.ofEpochSecond(1615304991));
        assertThat(other.payload()).containsEntry("zone", "HQ");
        assertThat(this.handler.contexts.get(0).subject().email()).isEqualTo("someuser@somedomain.com");
    }

    @Test
    void caepSetPassesStrictVerificationAndReachesTheHandler() {
        transmitter.publishJwks(transmitter.key());
        NimbusSsfSetVerifier verifier = new NimbusSsfSetVerifier(transmitter.issuer(), transmitter::jwksUri,
                new JdkSsfHttpClient());
        verifier.setExpectedAudience(TestTransmitter.AUDIENCE);
        SsfSetProcessor processor = new SsfSetProcessor(verifier, new InMemorySsfJtiDedupStore(10),
                List.of(this.handler));
        JWTClaimsSet claims = transmitter
            .setClaims("CaepAssuranceLevelChange", issSub("https://idp.example.com/3456789/", "jane.smith@example.com"),
                    Map.of("namespace", "NIST-AAL", "current_level", "nist-aal1", "previous_level", "nist-aal2",
                            "change_direction", "decrease", "initiating_entity", "user", "event_timestamp", 1615304991))
            .claim("txn", "8675309")
            .build();

        assertThat(processor.process(transmitter.signSet(claims))).isEqualTo(SsfSetProcessor.Outcome.HANDLED);

        assertThat(this.handler.calls).containsExactly("onAssuranceLevelChange");
        SsfCaepEvent event = this.handler.events.get(0);
        assertThat(event.namespace()).isEqualTo("NIST-AAL");
        assertThat(event.currentLevel()).isEqualTo("nist-aal1");
        assertThat(event.previousLevel()).isEqualTo("nist-aal2");
        assertThat(event.changeDirection()).isEqualTo("decrease");
        assertThat(event.eventTimestamp()).isEqualTo(Instant.ofEpochSecond(1615304991));
        SsfEventContext eventContext = this.handler.contexts.get(0);
        assertThat(eventContext.subject().subject()).isEqualTo("jane.smith@example.com");
        assertThat(eventContext.eventToken().txn()).isEqualTo("8675309");
    }

    private static SsfEventContext context(Map<String, Object> events) {
        return new SsfEventContext(
                new SsfEventToken("jti-1", "https://idp.example.com", Instant.parse("2026-10-06T10:00:00Z"), List.of(),
                        events, email("someuser@somedomain.com"), null, Map.of()));
    }

    private static final class RecordingHandler extends SsfCaepEventHandler {

        private final List<String> calls = new ArrayList<>();

        private final List<SsfCaepEvent> events = new ArrayList<>();

        private final List<SsfEventContext> contexts = new ArrayList<>();

        @Override
        protected void onSessionRevoked(SsfCaepEvent event, SsfEventContext eventContext) {
            record("onSessionRevoked", event, eventContext);
        }

        @Override
        protected void onTokenClaimsChange(SsfCaepEvent event, SsfEventContext eventContext) {
            record("onTokenClaimsChange", event, eventContext);
        }

        @Override
        protected void onCredentialChange(SsfCaepEvent event, SsfEventContext eventContext) {
            record("onCredentialChange", event, eventContext);
        }

        @Override
        protected void onAssuranceLevelChange(SsfCaepEvent event, SsfEventContext eventContext) {
            record("onAssuranceLevelChange", event, eventContext);
        }

        @Override
        protected void onDeviceComplianceChange(SsfCaepEvent event, SsfEventContext eventContext) {
            record("onDeviceComplianceChange", event, eventContext);
        }

        @Override
        protected void onSessionEstablished(SsfCaepEvent event, SsfEventContext eventContext) {
            record("onSessionEstablished", event, eventContext);
        }

        @Override
        protected void onSessionPresented(SsfCaepEvent event, SsfEventContext eventContext) {
            record("onSessionPresented", event, eventContext);
        }

        @Override
        protected void onRiskLevelChange(SsfCaepEvent event, SsfEventContext eventContext) {
            record("onRiskLevelChange", event, eventContext);
        }

        @Override
        protected void onOtherCaepEvent(SsfCaepEvent event, SsfEventContext eventContext) {
            record("onOtherCaepEvent", event, eventContext);
        }

        private void record(String method, SsfCaepEvent event, SsfEventContext eventContext) {
            this.calls.add(method);
            this.events.add(event);
            this.contexts.add(eventContext);
        }

    }

}
