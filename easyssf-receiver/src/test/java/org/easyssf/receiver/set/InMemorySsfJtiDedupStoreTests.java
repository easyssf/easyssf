package org.easyssf.receiver.set;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.receiver.set.SsfJtiDedupStore.Claim;
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
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.NEW);
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.IN_PROGRESS);
        this.store.processed(set("jti-1"));
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.PROCESSED);
        this.store.forget(set("jti-1"));
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.NEW);
        // a jti is only unique per issuer
        assertThat(this.store.claim(set("https://other.example", "jti-1"))).isEqualTo(Claim.NEW);
        assertThat(this.store.size()).isEqualTo(2);
    }

    @Test
    void abandonedClaimIsTakenOverAfterTheLease() {
        this.store.setLease(Duration.ofSeconds(30));
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.NEW);
        this.clock.advance(Duration.ofSeconds(29));
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.IN_PROGRESS);
        this.clock.advance(Duration.ofSeconds(1));
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.NEW);
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.IN_PROGRESS);
        // processed rows do not expire with the lease
        this.store.processed(set("jti-1"));
        this.clock.advance(Duration.ofHours(1));
        assertThat(this.store.claim(set("jti-1"))).isEqualTo(Claim.PROCESSED);
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
