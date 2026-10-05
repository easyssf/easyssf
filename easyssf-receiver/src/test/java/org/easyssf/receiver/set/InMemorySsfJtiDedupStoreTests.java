package org.easyssf.receiver.set;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.receiver.set.SsfJtiDedupStore.Claim;
import org.easyssf.receiver.set.SsfJtiDedupStore.State;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySsfJtiDedupStoreTests {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    private final MutableClock clock = new MutableClock();

    private final InMemorySsfJtiDedupStore store = new InMemorySsfJtiDedupStore(10);

    InMemorySsfJtiDedupStoreTests() {
        this.store.setClock(this.clock);
    }

    @Test
    void claimInProgressProcessedForget() {
        Claim claim = this.store.claim(set("jti-1"));
        assertThat(claim.isNew()).isTrue();
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.inProgress());
        this.store.processed(set("jti-1"), claim);
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.processed());
        // a processed SET is not forgotten, the claim is over
        this.store.forget(set("jti-1"), claim);
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.processed());
        Claim second = this.store.claim(set("jti-2"));
        this.store.forget(set("jti-2"), second);
        assertThat(this.store.claim(set("jti-2")).state()).isEqualTo(State.NEW);
        // a jti is only unique per issuer
        assertThat(this.store.claim(set("https://other.example", "jti-1")).state()).isEqualTo(State.NEW);
        assertThat(this.store.size()).isEqualTo(3);
    }

    @Test
    void abandonedClaimIsTakenOverAfterTheLease() {
        this.store.setLease(Duration.ofSeconds(30));
        Claim first = this.store.claim(set("jti-1"));
        assertThat(first.isNew()).isTrue();
        this.clock.advance(Duration.ofSeconds(29));
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.inProgress());
        this.clock.advance(Duration.ofSeconds(1));
        Claim second = this.store.claim(set("jti-1"));
        assertThat(second.isNew()).isTrue();
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.inProgress());
        // processed rows do not expire with the lease
        this.store.processed(set("jti-1"), second);
        this.clock.advance(Duration.ofHours(1));
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.processed());
    }

    @Test
    void lateHolderCanNeitherCompleteNorForgetTheClaimTakenOverFromIt() {
        this.store.setLease(Duration.ofSeconds(30));
        Claim late = this.store.claim(set("jti-1"));
        this.clock.advance(Duration.ofSeconds(30));
        Claim current = this.store.claim(set("jti-1"));
        assertThat(current.isNew()).isTrue();
        // the late holder's handlers succeed: the SET still belongs to the current holder
        this.store.processed(set("jti-1"), late);
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.inProgress());
        // the late holder's handlers fail: the current claim stays
        this.store.forget(set("jti-1"), late);
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.inProgress());
        this.store.processed(set("jti-1"), current);
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.processed());
    }

    @Test
    void successOfAnEvictedClaimIsRecorded() {
        InMemorySsfJtiDedupStore small = new InMemorySsfJtiDedupStore(1);
        Claim claim = small.claim(set("jti-1"));
        small.claim(set("jti-2"));
        small.processed(set("jti-1"), claim);
        assertThat(small.claim(set("jti-1"))).isEqualTo(Claim.processed());
    }

    @Test
    @SuppressWarnings("deprecation")
    void seenBeforeClaimsAndTellsWhetherTheSetWasKnown() {
        assertThat(this.store.seenBefore(set("jti-1"))).isFalse();
        assertThat(this.store.seenBefore(set("jti-1"))).isTrue();
    }

    private static SsfEventToken set(String jti) {
        return set("https://idp.example", jti);
    }

    private static SsfEventToken set(String issuer, String jti) {
        return new SsfEventToken(jti, issuer, NOW, List.of(), Map.of(), null, null, Map.of());
    }

    private static final class MutableClock extends Clock {

        private Instant now = NOW;

        void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now;
        }

    }

}
