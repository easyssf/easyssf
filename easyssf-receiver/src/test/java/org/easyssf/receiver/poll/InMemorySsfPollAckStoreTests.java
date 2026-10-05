package org.easyssf.receiver.poll;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class InMemorySsfPollAckStoreTests {

    private static final String ISSUER = "https://idp.example";

    private final InMemorySsfPollAckStore store = new InMemorySsfPollAckStore();

    @Test
    void keepsAcknowledgementsAndErrorsInOrderPerIssuer() {
        this.store.record(ISSUER, SsfPendingAck.ack("jti-1"));
        this.store.record(ISSUER, SsfPendingAck.error("jti-2", "invalid_request", "not a SET"));
        this.store.record(ISSUER, SsfPendingAck.ack("jti-3"));
        this.store.record("https://other.example", SsfPendingAck.ack("jti-1"));

        assertThat(this.store.size(ISSUER)).isEqualTo(3);
        assertThat(this.store.pending(ISSUER, 10)).extracting(SsfPendingAck::jti)
            .containsExactly("jti-1", "jti-2", "jti-3");
        assertThat(this.store.pending(ISSUER, 2)).extracting(SsfPendingAck::jti).containsExactly("jti-1", "jti-2");
        assertThat(this.store.pending(ISSUER, 10).get(1).isError()).isTrue();
        assertThat(this.store.pending(ISSUER, 10).get(1).errorDescription()).isEqualTo("not a SET");
        assertThat(this.store.size("https://other.example")).isEqualTo(1);
        assertThat(this.store.size("https://unknown.example")).isZero();
    }

    @Test
    void recordingTheSameSetAgainChangesNothing() {
        this.store.record(ISSUER, SsfPendingAck.ack("jti-1"));
        this.store.record(ISSUER, SsfPendingAck.error("jti-1", "invalid_request", null));
        assertThat(this.store.pending(ISSUER, 10)).singleElement()
            .satisfies((ack) -> assertThat(ack.isError()).isFalse());
    }

    @Test
    void removesWhatTheTransmitterReceived() {
        this.store.record(ISSUER, SsfPendingAck.ack("jti-1"));
        this.store.record(ISSUER, SsfPendingAck.ack("jti-2"));
        this.store.remove(ISSUER, List.of("jti-1", "jti-unknown"));
        assertThat(this.store.pending(ISSUER, 10)).extracting(SsfPendingAck::jti).containsExactly("jti-2");
    }

    @Test
    void pendingAckIsValidated() {
        assertThatIllegalArgumentException().isThrownBy(() -> SsfPendingAck.error("jti", null, "x"));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new SsfPendingAck("jti", null, "description without code"));
        assertThat(SsfPendingAck.ack("jti").isError()).isFalse();
    }

}
