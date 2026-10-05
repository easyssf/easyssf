package org.easyssf.core.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.easyssf.core.support.SsfCollections;

/**
 * A verified Security Event Token (SET, RFC 8417) with the SSF profile claims.
 *
 * @param jti unique identifier of the SET
 * @param iss issuer of the SET (the transmitter)
 * @param iat time the SET was issued
 * @param aud audiences of the SET, empty if the claim was absent
 * @param events event payloads keyed by event type URI
 * @param subjectId the top-level {@code sub_id} claim, {@code null} if absent
 * @param txn transaction identifier, {@code null} if absent
 * @param claims all claims of the SET
 */
public record SsfEventToken(String jti, String iss, Instant iat, List<String> aud, Map<String, Object> events,
        Map<String, Object> subjectId, String txn, Map<String, Object> claims) {

    public SsfEventToken {
        aud = SsfCollections.copyOf(aud);
        events = SsfCollections.copyOf(events);
        subjectId = SsfCollections.copyOf(subjectId);
        claims = SsfCollections.copyOf(claims);
    }

    /**
     * The key that identifies this SET across deliveries: the issuer and the {@code jti},
     * which is only unique per issuer (RFC 8417, section 2.2). A handler that has side
     * effects outside the receiver's stores uses it to make them idempotent, because a
     * SET may be handled more than once: after a handler failed, or on another instance
     * after the one that handled it died before recording the success.
     */
    public String idempotencyKey() {
        return this.iss + "::" + this.jti;
    }

}
