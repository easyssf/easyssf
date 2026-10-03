package org.easyssf.receiver.revocation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.easyssf.core.support.SsfAssert;

/**
 * {@link SsfTokenRevocationStore} that keeps revocations in memory for a fixed time. Its
 * content is lost on restart and not shared between instances.
 */
public class InMemorySsfTokenRevocationStore implements SsfTokenRevocationStore {

    private final Map<String, Instant> sessionExpirations = new ConcurrentHashMap<>();

    private final Map<String, SubjectRevocation> subjectRevocations = new ConcurrentHashMap<>();

    private final Duration ttl;

    private final Clock clock;

    /**
     * @param ttl how long a revocation is remembered, at least the maximum access token
     * lifetime
     */
    public InMemorySsfTokenRevocationStore(Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    public InMemorySsfTokenRevocationStore(Duration ttl, Clock clock) {
        SsfAssert.isTrue(ttl != null && ttl.isPositive(), "ttl must be positive");
        SsfAssert.notNull(clock, "clock must not be null");
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public void revokeSession(String sessionId) {
        Instant now = this.clock.instant();
        removeExpired(now);
        this.sessionExpirations.put(sessionId, now.plus(this.ttl));
    }

    @Override
    public void revokeSubject(String subject, Instant revokedAt) {
        Instant now = this.clock.instant();
        removeExpired(now);
        SubjectRevocation revocation = new SubjectRevocation(revokedAt, now.plus(this.ttl));
        // an event delivered late must not shorten a more recent revocation
        this.subjectRevocations.merge(subject, revocation,
                (existing, added) -> existing.revokedAt().isAfter(added.revokedAt())
                        ? new SubjectRevocation(existing.revokedAt(), added.expiresAt()) : added);
    }

    @Override
    public boolean isSessionRevoked(String sessionId) {
        Instant expiresAt = this.sessionExpirations.get(sessionId);
        return expiresAt != null && expiresAt.isAfter(this.clock.instant());
    }

    @Override
    public Instant getSubjectRevokedAt(String subject) {
        SubjectRevocation revocation = this.subjectRevocations.get(subject);
        return (revocation != null && revocation.expiresAt().isAfter(this.clock.instant())) ? revocation.revokedAt()
                : null;
    }

    private void removeExpired(Instant now) {
        this.sessionExpirations.values().removeIf((expiresAt) -> !expiresAt.isAfter(now));
        this.subjectRevocations.values().removeIf((revocation) -> !revocation.expiresAt().isAfter(now));
    }

    private record SubjectRevocation(Instant revokedAt, Instant expiresAt) {
    }

}
