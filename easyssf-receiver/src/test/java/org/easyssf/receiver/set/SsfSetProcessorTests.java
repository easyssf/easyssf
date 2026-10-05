package org.easyssf.receiver.set;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.receiver.event.SsfEventHandler;
import org.easyssf.receiver.event.SsfEventHandlingException;
import org.easyssf.receiver.event.SsfSetInProgressException;
import org.easyssf.receiver.set.SsfSetProcessor.Outcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class SsfSetProcessorTests {

    private final List<String> handled = new ArrayList<>();

    private final List<RuntimeException> failures = new ArrayList<>();

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
    void setBeingHandledElsewhereIsLeftForARedelivery() {
        SsfJtiDedupStore inProgress = new SsfJtiDedupStore() {
            @Override
            public Claim claim(org.easyssf.core.event.SsfEventToken eventToken) {
                return Claim.inProgress();
            }

            @Override
            public void processed(org.easyssf.core.event.SsfEventToken eventToken, Claim claim) {
            }

            @Override
            public void forget(org.easyssf.core.event.SsfEventToken eventToken, Claim claim) {
            }
        };
        SsfSetProcessor processor = new SsfSetProcessor(this.verifier, inProgress, List.of(this.recordingHandler));
        assertThatExceptionOfType(SsfSetInProgressException.class).isThrownBy(() -> processor.process("jti-1"))
            .withMessageContaining("another instance");
        assertThat(this.handled).isEmpty();
    }

    @Test
    void handledSetIsMarkedProcessedAndFailedOneForgotten() {
        InMemorySsfJtiDedupStore store = new InMemorySsfJtiDedupStore(10);
        this.failures.add(new IllegalStateException("store is down"));
        SsfEventHandler failingOnce = (context) -> {
            if (!this.failures.isEmpty()) {
                throw this.failures.remove(0);
            }
            this.handled.add(context.eventToken().jti());
        };
        SsfSetProcessor processor = new SsfSetProcessor(this.verifier, store, List.of(failingOnce));
        assertThatExceptionOfType(SsfEventHandlingException.class).isThrownBy(() -> processor.process("jti-1"));
        // forgotten: the redelivery is new, not in progress
        SsfJtiDedupStore.Claim claim = store.claim(this.verifier.verify("jti-1"));
        assertThat(claim.isNew()).isTrue();
        store.forget(this.verifier.verify("jti-1"), claim);
        assertThat(processor.process("jti-1")).isEqualTo(Outcome.HANDLED);
        assertThat(store.claim(this.verifier.verify("jti-1"))).isEqualTo(SsfJtiDedupStore.Claim.processed());
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

    @Test
    void rejectsSetWithCriticalSubjectMemberTheReceiverDoesNotUnderstand() {
        Map<String, Object> subjectId = Map.of("format", "complex", "user",
                Map.of("format", "email", "email", "alice@example.com"), "tenant",
                Map.of("format", "opaque", "id", "t1"), "custom", Map.of("format", "acme-custom", "ref", "x"));
        SsfSetVerifier verifier = (encodedSet) -> new SsfEventToken(encodedSet, "https://idp.example", Instant.now(),
                List.of(), Map.of(SsfEventTypes.CAEP_SESSION_REVOKED, Map.of()), subjectId, null, Map.of());
        SsfSetProcessor processor = new SsfSetProcessor(verifier, null, List.of(this.recordingHandler));

        // nothing declared critical: anything goes
        assertThat(processor.process("jti-1")).isEqualTo(Outcome.HANDLED);

        // critical members this receiver models are fine, a critical member it does not
        // model is not
        processor.setCriticalSubjectMembers((issuer) -> List.of("tenant"));
        assertThat(processor.process("jti-2")).isEqualTo(Outcome.HANDLED);
        processor.setCriticalSubjectMembers((issuer) -> List.of("tenant", "custom"));
        assertThatExceptionOfType(SsfSetVerificationException.class).isThrownBy(() -> processor.process("jti-3"))
            .satisfies((ex) -> assertThat(ex.getErrorCode()).isEqualTo(SsfSetVerificationException.INVALID_REQUEST))
            .withMessageContaining("custom");

        // unless the application says it understands it
        processor.setUnderstoodSubjectMembers(Set.of("user", "tenant", "custom"));
        assertThat(processor.process("jti-4")).isEqualTo(Outcome.HANDLED);
        // a critical member that is absent from the subject does not matter
        processor.setCriticalSubjectMembers((issuer) -> List.of("device"));
        assertThat(processor.process("jti-5")).isEqualTo(Outcome.HANDLED);
        assertThat(this.handled).containsExactly("jti-1", "jti-2", "jti-4", "jti-5");
    }

    private SsfSetProcessor processor(SsfEventHandler... handlers) {
        return new SsfSetProcessor(this.verifier, new InMemorySsfJtiDedupStore(100), List.of(handlers));
    }

}
