package org.easyssf.receiver.risc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.risc.SsfRiscEvent;
import org.easyssf.core.risc.SsfRiscEventKind;
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

class SsfRiscEventHandlerTests {

    private static final String UNKNOWN_RISC_EVENT = "https://schemas.openid.net/secevent/risc/event-type/account-merged";

    private static final TestTransmitter transmitter = new TestTransmitter();

    private final RecordingHandler handler = new RecordingHandler();

    @AfterAll
    static void stopTransmitter() {
        transmitter.close();
    }

    @Test
    void dispatchesEveryKindToItsMethod() {
        for (SsfRiscEventKind kind : SsfRiscEventKind.values()) {
            String eventType = (kind != SsfRiscEventKind.OTHER) ? kind.eventType() : UNKNOWN_RISC_EVENT;
            this.handler.handle(context(Map.of(eventType, Map.of())));
        }
        assertThat(this.handler.calls).containsExactly("onAccountCredentialChangeRequired", "onAccountPurged",
                "onAccountDisabled", "onAccountEnabled", "onIdentifierChanged", "onIdentifierRecycled",
                "onCredentialCompromise", "onOptIn", "onOptOutInitiated", "onOptOutCancelled", "onOptOutEffective",
                "onRecoveryActivated", "onRecoveryInformationChanged", "onSessionsRevoked", "onOtherRiscEvent");
    }

    @Test
    void handlesAllRiscEventsOfASetAndIgnoresOtherSets() {
        Map<String, Object> events = new LinkedHashMap<>();
        events.put(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of());
        events.put(SsfEventTypes.RISC_ACCOUNT_DISABLED, Map.of("reason", "hijacking"));
        events.put(UNKNOWN_RISC_EVENT, Map.of("into", "other-account"));
        this.handler.handle(context(events));
        this.handler.handle(context(Map.of(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of())));
        this.handler.handle(context(Map.of("urn:example:acme:login", Map.of())));
        assertThat(this.handler.calls).containsExactly("onAccountDisabled", "onOtherRiscEvent");
        assertThat(this.handler.events.get(0).reason()).isEqualTo("hijacking");
        assertThat(this.handler.events.get(1).eventType()).isEqualTo(UNKNOWN_RISC_EVENT);
        assertThat(this.handler.events.get(1).payload()).containsEntry("into", "other-account");
        assertThat(this.handler.contexts.get(0).subject().email()).isEqualTo("john.doe@example.com");
    }

    @Test
    void riscSetPassesStrictVerificationAndReachesTheHandler() {
        transmitter.publishJwks(transmitter.key());
        NimbusSsfSetVerifier verifier = new NimbusSsfSetVerifier(transmitter.issuer(), transmitter::jwksUri,
                new JdkSsfHttpClient());
        verifier.setExpectedAudience(TestTransmitter.AUDIENCE);
        SsfSetProcessor processor = new SsfSetProcessor(verifier, new InMemorySsfJtiDedupStore(10),
                List.of(this.handler));
        JWTClaimsSet claims = transmitter
            .setClaims("RiscCredentialCompromise", issSub("https://idp.example.com/3456790/", "joe.smith@example.com"),
                    Map.of("credential_type", "password", "event_timestamp", 1508184845))
            .build();

        assertThat(processor.process(transmitter.signSet(claims))).isEqualTo(SsfSetProcessor.Outcome.HANDLED);

        assertThat(this.handler.calls).containsExactly("onCredentialCompromise");
        SsfRiscEvent event = this.handler.events.get(0);
        assertThat(event.credentialType()).isEqualTo("password");
        assertThat(event.eventTimestamp()).isEqualTo(Instant.ofEpochSecond(1508184845));
        assertThat(this.handler.contexts.get(0).subject().subject()).isEqualTo("joe.smith@example.com");
    }

    private static SsfEventContext context(Map<String, Object> events) {
        return new SsfEventContext(
                new SsfEventToken("jti-1", "https://idp.example.com", Instant.parse("2026-10-06T10:00:00Z"), List.of(),
                        events, email("john.doe@example.com"), null, Map.of()));
    }

    private static final class RecordingHandler extends SsfRiscEventHandler {

        private final List<String> calls = new ArrayList<>();

        private final List<SsfRiscEvent> events = new ArrayList<>();

        private final List<SsfEventContext> contexts = new ArrayList<>();

        @Override
        protected void onAccountCredentialChangeRequired(SsfRiscEvent event, SsfEventContext eventContext) {
            record("onAccountCredentialChangeRequired", event, eventContext);
        }

        @Override
        protected void onAccountPurged(SsfRiscEvent event, SsfEventContext eventContext) {
            record("onAccountPurged", event, eventContext);
        }

        @Override
        protected void onAccountDisabled(SsfRiscEvent event, SsfEventContext eventContext) {
            record("onAccountDisabled", event, eventContext);
        }

        @Override
        protected void onAccountEnabled(SsfRiscEvent event, SsfEventContext eventContext) {
            record("onAccountEnabled", event, eventContext);
        }

        @Override
        protected void onIdentifierChanged(SsfRiscEvent event, SsfEventContext eventContext) {
            record("onIdentifierChanged", event, eventContext);
        }

        @Override
        protected void onIdentifierRecycled(SsfRiscEvent event, SsfEventContext eventContext) {
            record("onIdentifierRecycled", event, eventContext);
        }

        @Override
        protected void onCredentialCompromise(SsfRiscEvent event, SsfEventContext eventContext) {
            record("onCredentialCompromise", event, eventContext);
        }

        @Override
        protected void onOptIn(SsfRiscEvent event, SsfEventContext eventContext) {
            record("onOptIn", event, eventContext);
        }

        @Override
        protected void onOptOutInitiated(SsfRiscEvent event, SsfEventContext eventContext) {
            record("onOptOutInitiated", event, eventContext);
        }

        @Override
        protected void onOptOutCancelled(SsfRiscEvent event, SsfEventContext eventContext) {
            record("onOptOutCancelled", event, eventContext);
        }

        @Override
        protected void onOptOutEffective(SsfRiscEvent event, SsfEventContext eventContext) {
            record("onOptOutEffective", event, eventContext);
        }

        @Override
        protected void onRecoveryActivated(SsfRiscEvent event, SsfEventContext eventContext) {
            record("onRecoveryActivated", event, eventContext);
        }

        @Override
        protected void onRecoveryInformationChanged(SsfRiscEvent event, SsfEventContext eventContext) {
            record("onRecoveryInformationChanged", event, eventContext);
        }

        @Override
        protected void onSessionsRevoked(SsfRiscEvent event, SsfEventContext eventContext) {
            record("onSessionsRevoked", event, eventContext);
        }

        @Override
        protected void onOtherRiscEvent(SsfRiscEvent event, SsfEventContext eventContext) {
            record("onOtherRiscEvent", event, eventContext);
        }

        private void record(String method, SsfRiscEvent event, SsfEventContext eventContext) {
            this.calls.add(method);
            this.events.add(event);
            this.contexts.add(eventContext);
        }

    }

}
