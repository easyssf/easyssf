package org.easyssf.receiver.set;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.event.SsfEventHandlingException;
import org.easyssf.receiver.set.SsfSetProcessor.Outcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class SsfSetProcessorTests {

    private final List<String> handled = new ArrayList<>();

    private final SsfSetVerifier verifier = (encodedSet) -> new SsfEventToken(encodedSet, "https://idp.example",
            Instant.now(), List.of(), Map.of(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of()), null, null, Map.of());

    private final SsfEventHandler recordingHandler = (context) -> this.handled.add(context.eventToken().jti());

    @Test
    void handsVerifiedSetToAllHandlers() {
        SsfSetProcessor processor = processor(this.recordingHandler, this.recordingHandler);
        assertThat(processor.process("jti-1")).isEqualTo(Outcome.HANDLED);
        assertThat(this.handled).containsExactly("jti-1", "jti-1");
    }

    @Test
    void skipsSetThatWasProcessedBefore() {
        SsfSetProcessor processor = processor(this.recordingHandler);
        assertThat(processor.process("jti-1")).isEqualTo(Outcome.HANDLED);
        assertThat(processor.process("jti-1")).isEqualTo(Outcome.DUPLICATE);
        assertThat(processor.process("jti-2")).isEqualTo(Outcome.HANDLED);
        assertThat(this.handled).containsExactly("jti-1", "jti-2");
    }

    @Test
    void processesDuplicatesWithoutDedupStore() {
        SsfSetProcessor processor = new SsfSetProcessor(this.verifier, null, List.of(this.recordingHandler));
        processor.process("jti-1");
        processor.process("jti-1");
        assertThat(this.handled).containsExactly("jti-1", "jti-1");
    }

    @Test
    void failingHandlerDoesNotKeepOthersFromHandlingAndAllowsRedelivery() {
        List<RuntimeException> failures = new ArrayList<>(List.of(new IllegalStateException("store is down")));
        SsfEventHandler failingOnce = (context) -> {
            if (!failures.isEmpty()) {
                throw failures.remove(0);
            }
        };
        SsfSetProcessor processor = processor(failingOnce, this.recordingHandler);
        assertThatExceptionOfType(SsfEventHandlingException.class).isThrownBy(() -> processor.process("jti-1"))
            .withCauseInstanceOf(IllegalStateException.class);
        assertThat(this.handled).containsExactly("jti-1");
        // the SET is delivered again and must not be skipped as a duplicate
        assertThat(processor.process("jti-1")).isEqualTo(Outcome.HANDLED);
        assertThat(this.handled).containsExactly("jti-1", "jti-1");
    }

    @Test
    void dedupStoreEvictsOldestEntryWhenFull() {
        InMemorySsfJtiDedupStore store = new InMemorySsfJtiDedupStore(2);
        assertThat(store.seenBefore(this.verifier.verify("jti-1"))).isFalse();
        assertThat(store.seenBefore(this.verifier.verify("jti-2"))).isFalse();
        assertThat(store.seenBefore(this.verifier.verify("jti-3"))).isFalse();
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.seenBefore(this.verifier.verify("jti-3"))).isTrue();
        assertThat(store.seenBefore(this.verifier.verify("jti-1"))).isFalse();
    }

    private SsfSetProcessor processor(SsfEventHandler... handlers) {
        return new SsfSetProcessor(this.verifier, new InMemorySsfJtiDedupStore(100), List.of(handlers));
    }

}
