package org.easyssf.receiver.revocation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.receiver.event.SsfEventContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easyssf.core.event.SsfSubjectIdentifiers.complex;
import static org.easyssf.core.event.SsfSubjectIdentifiers.email;
import static org.easyssf.core.event.SsfSubjectIdentifiers.issSub;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

class SsfTokenRevocationTests {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);

    private final InMemorySsfTokenRevocationStore store = new InMemorySsfTokenRevocationStore(Duration.ofMinutes(10),
            this.clock);

    private final SsfTokenRevocationEventHandler handler = new SsfTokenRevocationEventHandler(this.store,
            List.of("CaepSessionRevoked"));

    @Test
    void sessionEventRevokesTokensOfThatSessionOnly() {
        this.handler.handle(sessionRevoked(complex(issSub("https://idp.example", "alice"), opaque("session-1"))));
        assertThat(isValid(token("alice", "session-1", NOW.minusSeconds(60)))).isFalse();
        assertThat(isValid(token("alice", "session-1", NOW.plusSeconds(60)))).isFalse();
        assertThat(isValid(token("alice", "session-2", NOW.minusSeconds(60)))).isTrue();
    }

    @Test
    void userEventRevokesTokensIssuedUpToTheEvent() {
        this.handler.handle(sessionRevoked(issSub("https://idp.example", "alice")));
        assertThat(isValid(token("alice", "session-1", NOW.minusSeconds(60)))).isFalse();
        assertThat(isValid(token("alice", "session-1", NOW))).isFalse();
        assertThat(isValid(token("alice", "session-2", NOW.plusSeconds(1)))).isTrue();
        assertThat(isValid(token("bob", "session-3", NOW.minusSeconds(60)))).isTrue();
    }

    @Test
    void opaqueSubjectRevokesMatchingSessionAndSubject() {
        this.handler.handle(sessionRevoked(opaque("abc")));
        assertThat(isValid(token("alice", "abc", NOW.plusSeconds(60)))).isFalse();
        assertThat(isValid(token("abc", "session-1", NOW.minusSeconds(60)))).isFalse();
        assertThat(isValid(token("alice", "session-1", NOW.minusSeconds(60)))).isTrue();
    }

    @Test
    void ignoresOtherEventsAndSubjectsWithoutSessionOrSubject() {
        this.handler.handle(event(SsfEventTypes.CAEP_CREDENTIAL_CHANGE, issSub("https://idp.example", "alice")));
        this.handler.handle(sessionRevoked(email("alice@example.com")));
        assertThat(isValid(token("alice", "session-1", NOW.minusSeconds(60)))).isTrue();
    }

    @Test
    void revocationsExpire() {
        this.store.revokeSession("session-1");
        this.store.revokeSubject("alice", NOW);
        this.clock.advance(Duration.ofMinutes(9));
        assertThat(this.store.isSessionRevoked("session-1")).isTrue();
        assertThat(this.store.getSubjectRevokedAt("alice")).isEqualTo(NOW);
        this.clock.advance(Duration.ofMinutes(2));
        assertThat(this.store.isSessionRevoked("session-1")).isFalse();
        assertThat(this.store.getSubjectRevokedAt("alice")).isNull();
    }

    @Test
    void eventDeliveredLateDoesNotShortenMoreRecentRevocation() {
        this.store.revokeSubject("alice", NOW);
        this.store.revokeSubject("alice", NOW.minusSeconds(120));
        assertThat(this.store.getSubjectRevokedAt("alice")).isEqualTo(NOW);
    }

    private boolean isValid(Token token) {
        return !this.store.isRevoked(token.sessionId(), token.subject(), token.issuedAt());
    }

    private static Token token(String subject, String sessionId, Instant issuedAt) {
        return new Token(subject, sessionId, issuedAt);
    }

    private record Token(String subject, String sessionId, Instant issuedAt) {
    }

    private static SsfEventContext sessionRevoked(Map<String, Object> subjectId) {
        return event(SsfEventTypes.CAEP_SESSION_REVOKED, subjectId);
    }

    private static SsfEventContext event(String eventType, Map<String, Object> subjectId) {
        Map<String, Object> events = Map.of(eventType, Map.of("event_timestamp", NOW.getEpochSecond()));
        return new SsfEventContext(
                new SsfEventToken("jti-1", "https://idp.example", NOW, List.of(), events, subjectId, null, Map.of()));
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            this.now = this.now.plus(duration);
        }

        @Override
        public Instant instant() {
            return this.now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

    }

}
