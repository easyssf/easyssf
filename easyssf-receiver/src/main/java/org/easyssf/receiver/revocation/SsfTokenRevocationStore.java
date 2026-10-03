package org.easyssf.receiver.revocation;

import java.time.Instant;

/**
 * Remembers which sessions and subjects were revoked, so that their access tokens can be
 * rejected. Implement it on top of a shared store to share revocations between the
 * instances of a resource server, since a SET is pushed to only one of them.
 *
 * <p>
 * Revocations only need to be kept for as long as an access token issued before the
 * revocation can still be valid, i.e. for the maximum access token lifetime.
 */
public interface SsfTokenRevocationStore {

    /**
     * Revokes all access tokens bound to the given session.
     * @param sessionId the session identifier, as found in the {@code sid} claim
     */
    void revokeSession(String sessionId);

    /**
     * Revokes all access tokens of the given subject that were issued at or before the
     * given time.
     * @param subject the subject, as found in the {@code sub} claim
     */
    void revokeSubject(String subject, Instant revokedAt);

    boolean isSessionRevoked(String sessionId);

    /**
     * Returns the time up to which the tokens of the given subject are revoked.
     * @return the time of the most recent revocation, or {@code null} if the subject is
     * not revoked
     */
    Instant getSubjectRevokedAt(String subject);

    /**
     * Whether an access token was revoked: it is bound to a revoked session, or it
     * belongs to a revoked subject and was issued at or before the revocation.
     * @param sessionId the {@code sid} claim of the token, may be {@code null}
     * @param subject the {@code sub} claim of the token, may be {@code null}
     * @param issuedAt the {@code iat} claim of the token, may be {@code null}
     */
    default boolean isRevoked(String sessionId, String subject, Instant issuedAt) {
        if (sessionId != null && isSessionRevoked(sessionId)) {
            return true;
        }
        if (subject == null) {
            return false;
        }
        Instant revokedAt = getSubjectRevokedAt(subject);
        return revokedAt != null && (issuedAt == null || !issuedAt.isAfter(revokedAt));
    }

}
