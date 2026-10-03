package org.easyssf.receiver.revocation;

import java.time.Instant;

/**
 * Remembers which sessions and subjects were revoked, so that their access tokens can be
 * rejected. Implement it on top of a shared store to share revocations between the
 * instances of a resource server, since a SET is pushed to only one of them.
 *
 * <p>
 * Sessions and subjects are scoped to the issuer of their tokens: a subject {@code alice}
 * of one issuer is not the subject {@code alice} of another. Events name that issuer in
 * an {@code iss_sub} identifier; for identifiers without one, the transmitter that sent
 * the event is taken to be the issuer.
 *
 * <p>
 * Revocations only need to be kept for as long as an access token issued before the
 * revocation can still be valid, i.e. for the maximum access token lifetime.
 */
public interface SsfTokenRevocationStore {

    /**
     * Revokes all access tokens bound to the given session.
     * @param issuer the issuer of the tokens
     * @param sessionId the session identifier, as found in the {@code sid} claim
     */
    void revokeSession(String issuer, String sessionId);

    /**
     * Revokes all access tokens of the given subject that were issued at or before the
     * given time.
     * @param issuer the issuer of the tokens
     * @param subject the subject, as found in the {@code sub} claim
     */
    void revokeSubject(String issuer, String subject, Instant revokedAt);

    boolean isSessionRevoked(String issuer, String sessionId);

    /**
     * Returns the time up to which the tokens of the given subject are revoked.
     * @return the time of the most recent revocation, or {@code null} if the subject is
     * not revoked
     */
    Instant getSubjectRevokedAt(String issuer, String subject);

    /**
     * Whether an access token was revoked: it is bound to a revoked session, or it
     * belongs to a revoked subject and was issued at or before the revocation.
     * @param issuer the {@code iss} claim of the token; a token without one is not
     * revoked
     * @param sessionId the {@code sid} claim of the token, may be {@code null}
     * @param subject the {@code sub} claim of the token, may be {@code null}
     * @param issuedAt the {@code iat} claim of the token, may be {@code null}
     */
    default boolean isRevoked(String issuer, String sessionId, String subject, Instant issuedAt) {
        if (issuer == null) {
            return false;
        }
        if (sessionId != null && isSessionRevoked(issuer, sessionId)) {
            return true;
        }
        if (subject == null) {
            return false;
        }
        Instant revokedAt = getSubjectRevokedAt(issuer, subject);
        return revokedAt != null && (issuedAt == null || !issuedAt.isAfter(revokedAt));
    }

}
