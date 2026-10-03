package org.easyssf.receiver.stream;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.set.InMemorySsfJtiDedupStore;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.easyssf.receiver.set.SsfSetProcessor.Outcome;
import org.easyssf.receiver.set.SsfSetVerificationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.easyssf.core.event.SsfSubjectIdentifiers.issSub;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

class SsfStreamVerificationTests {

    private static final String STREAM_ID = "stream-1";

    private final SsfStreamVerification verification = new SsfStreamVerification();

    SsfStreamVerificationTests() {
        this.verification.setStreamId(() -> STREAM_ID);
    }

    @Test
    void acceptsVerificationEventThatEchoesTheRequestedState() {
        String state = this.verification.newState();
        assertThat(this.verification.isPending()).isTrue();
        assertThatNoException().isThrownBy(() -> this.verification.validate(verificationEvent(STREAM_ID, state)));
        assertThat(this.verification.isPending()).isFalse();
    }

    @Test
    void rejectsVerificationEventWithAnotherStateThanTheRequestedOne() {
        String state = this.verification.newState();
        assertRejected(verificationEvent(STREAM_ID, "made-up"), SsfSetVerificationException.INVALID_STATE);
        // the requested verification is still outstanding
        assertThatNoException().isThrownBy(() -> this.verification.validate(verificationEvent(STREAM_ID, state)));
    }

    @Test
    void acceptsVerificationEventInitiatedByTheTransmitter() {
        this.verification.newState();
        assertThatNoException().isThrownBy(() -> this.verification.validate(verificationEvent(STREAM_ID, null)));
        assertThat(this.verification.isPending()).isTrue();
    }

    @Test
    void acceptsStateWhenNoVerificationWasRequestedUnlessStrict() {
        assertThatNoException().isThrownBy(() -> this.verification.validate(verificationEvent(STREAM_ID, "made-up")));
        this.verification.setRejectUnrequestedState(true);
        assertRejected(verificationEvent(STREAM_ID, "made-up"), SsfSetVerificationException.INVALID_STATE);
    }

    @Test
    void rejectsVerificationEventAboutAnotherStream() {
        String state = this.verification.newState();
        assertRejected(verificationEvent("another-stream", state), SsfSetVerificationException.INVALID_REQUEST);
        assertRejected(new SsfEventContext(token("jti", SsfEventTypes.SSF_STREAM_VERIFICATION, Map.of("state", state),
                issSub("https://idp.example", "alice"))), SsfSetVerificationException.INVALID_REQUEST);
        // the state was not used up by the rejected events
        assertThatNoException().isThrownBy(() -> this.verification.validate(verificationEvent(STREAM_ID, state)));
    }

    @Test
    void doesNotCheckTheStreamWhileItIsUnknown() {
        SsfStreamVerification unknownStream = new SsfStreamVerification();
        assertThatNoException().isThrownBy(() -> unknownStream.validate(verificationEvent("any-stream", null)));
    }

    @Test
    void ignoresOtherEvents() {
        this.verification.newState();
        SsfEventContext sessionRevoked = new SsfEventContext(
                token("jti", SsfEventTypes.CAEP_SESSION_REVOKED, Map.of("state", "x"), opaque("another-stream")));
        assertThatNoException().isThrownBy(() -> this.verification.validate(sessionRevoked));
    }

    @Test
    void processorRejectsInvalidVerificationEventWithoutHandlingOrRememberingIt() {
        List<String> handled = new ArrayList<>();
        SsfEventHandler handler = (eventContext) -> handled.add(eventContext.eventToken().jti());
        Map<String, SsfEventToken> sets = new LinkedHashMap<>();
        SsfSetProcessor processor = new SsfSetProcessor(sets::get, new InMemorySsfJtiDedupStore(10), List.of(handler));
        processor.setStreamVerification(this.verification);
        String state = this.verification.newState();
        sets.put("wrong", verificationEvent(STREAM_ID, "made-up").eventToken());
        sets.put("right", verificationEvent(STREAM_ID, state).eventToken());

        assertThatExceptionOfType(SsfSetVerificationException.class).isThrownBy(() -> processor.process("wrong"))
            .satisfies((ex) -> assertThat(ex.getErrorCode()).isEqualTo(SsfSetVerificationException.INVALID_STATE));
        assertThat(handled).isEmpty();

        assertThat(processor.process("right")).isEqualTo(Outcome.HANDLED);
        assertThat(handled).hasSize(1);
    }

    private void assertRejected(SsfEventContext eventContext, String errorCode) {
        assertThatExceptionOfType(SsfSetVerificationException.class)
            .isThrownBy(() -> this.verification.validate(eventContext))
            .satisfies((ex) -> assertThat(ex.getErrorCode()).isEqualTo(errorCode));
    }

    private static SsfEventContext verificationEvent(String streamId, String state) {
        Map<String, Object> event = (state != null) ? Map.of("state", state) : Map.of();
        return new SsfEventContext(
                token("jti-" + streamId + "-" + state, SsfEventTypes.SSF_STREAM_VERIFICATION, event, opaque(streamId)));
    }

    private static SsfEventToken token(String jti, String eventType, Map<String, Object> event,
            Map<String, Object> subjectId) {
        return new SsfEventToken(jti, "https://idp.example", Instant.now(), List.of(), Map.of(eventType, event),
                subjectId, null, Map.of());
    }

}
