package org.easyssf.receiver.poll;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.easyssf.receiver.http.ScriptedSsfHttpClient;
import org.easyssf.receiver.set.SsfSetProcessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * How the poller reacts when the transmitter rate-limits the poll endpoint.
 */
class SsfPollerRateLimitTests {

    private final ScriptedSsfHttpClient http = new ScriptedSsfHttpClient();

    private final SsfPoller poller = new SsfPoller(this.http, () -> "token",
            () -> URI.create("https://tr.example/poll"), new SsfSetProcessor((set) -> {
                throw new IllegalStateException("no SET expected");
            }, null, List.of()));

    @Test
    void pausesForRetryAfter() {
        this.http.respond(429, Map.of("Retry-After", List.of("120")), "");
        assertThatIllegalStateException().isThrownBy(this.poller::pollNow);
        assertThat(this.poller.getPausedUntil()).isBetween(Instant.now().plusSeconds(110),
                Instant.now().plusSeconds(121));
        // paused: the next poll makes no request
        assertThat(this.poller.pollNow()).isZero();
        assertThat(this.http.requests()).hasSize(1);
    }

    @Test
    void pollsAgainAtTheRegularIntervalAfter429WithoutRetryAfterByDefault() {
        this.http.respond(429, "").respond(429, "");
        assertThatIllegalStateException().isThrownBy(this.poller::pollNow);
        assertThat(this.poller.getPausedUntil()).isBefore(Instant.now());
        assertThatIllegalStateException().isThrownBy(this.poller::pollNow);
        assertThat(this.http.requests()).hasSize(2);
    }

    @Test
    void pausesForTheFallbackAfter429WithoutRetryAfter() {
        this.poller.setRateLimitFallback(Duration.ofSeconds(45));
        this.http.respond(429, "");
        assertThatIllegalStateException().isThrownBy(this.poller::pollNow);
        assertThat(this.poller.getPausedUntil()).isBetween(Instant.now().plusSeconds(40),
                Instant.now().plusSeconds(46));
        assertThat(this.poller.pollNow()).isZero();
        assertThat(this.http.requests()).hasSize(1);
    }

    @Test
    void fallbackDoesNotApplyTo503WithoutRetryAfter() {
        this.poller.setRateLimitFallback(Duration.ofSeconds(45));
        this.http.respond(503, "");
        assertThatIllegalStateException().isThrownBy(this.poller::pollNow);
        assertThat(this.poller.getPausedUntil()).isBefore(Instant.now());
    }

    @Test
    void pauseIsCappedAtTheMaximum() {
        this.poller.setMaxPause(Duration.ofSeconds(60));
        this.http.respond(503, Map.of("Retry-After", List.of("3600")), "");
        assertThatIllegalStateException().isThrownBy(this.poller::pollNow);
        assertThat(this.poller.getPausedUntil()).isBetween(Instant.now().plusSeconds(55),
                Instant.now().plusSeconds(61));
        this.poller.setRateLimitFallback(Duration.ofHours(1));
        this.http.respond(429, "");
        this.poller.setMaxPause(Duration.ofSeconds(1));
        // still paused from before, so no request is made; wait for the pause to end
        assertThat(this.poller.pollNow()).isZero();
    }

}
