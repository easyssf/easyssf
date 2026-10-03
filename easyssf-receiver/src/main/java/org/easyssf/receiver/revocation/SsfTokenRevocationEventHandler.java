package org.easyssf.receiver.revocation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.event.SsfSubject;
import org.easyssf.core.support.SsfAssert;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records the session or subject of revocation events (by default CAEP
 * {@code session-revoked}) in the {@link SsfTokenRevocationStore}.
 *
 * <p>
 * An event that names a session revokes the tokens of that session. An event that only
 * names a user revokes all tokens of that user issued up to the time of the event.
 */
public class SsfTokenRevocationEventHandler implements SsfEventHandler {

    private static final Logger logger = LoggerFactory.getLogger(SsfTokenRevocationEventHandler.class);

    private final SsfTokenRevocationStore revocationStore;

    private final List<String> eventTypes;

    /**
     * @param revocationStore records the revocations
     * @param eventTypes the event types (alias or URI) that revoke tokens
     */
    public SsfTokenRevocationEventHandler(SsfTokenRevocationStore revocationStore, Collection<String> eventTypes) {
        SsfAssert.notNull(revocationStore, "revocationStore must not be null");
        SsfAssert.notNull(eventTypes, "eventTypes must not be null");
        this.revocationStore = revocationStore;
        this.eventTypes = eventTypes.stream().map(SsfEventTypes::resolve).toList();
    }

    @Override
    public void handle(SsfEventContext eventContext) {
        for (String eventType : this.eventTypes) {
            if (eventContext.hasEvent(eventType)) {
                revoke(eventContext, eventType);
            }
        }
    }

    private void revoke(SsfEventContext eventContext, String eventType) {
        SsfSubject subject = eventContext.subjectFor(eventType);
        Instant revokedAt = eventContext.eventTimestamp(eventType);
        String alias = SsfEventTypes.aliasOf(eventType);
        if (subject.sessionId() != null) {
            this.revocationStore.revokeSession(subject.sessionId());
            logger.info(alias + ": revoked access tokens of session " + subject.sessionId());
        }
        else if (subject.subject() != null) {
            this.revocationStore.revokeSubject(subject.subject(), revokedAt);
            logger.info(alias + ": revoked access tokens of subject " + subject.subject());
        }
        else if (subject.opaqueId() != null) {
            // an opaque identifier does not tell whether it names a session or a user
            this.revocationStore.revokeSession(subject.opaqueId());
            this.revocationStore.revokeSubject(subject.opaqueId(), revokedAt);
            logger.info(alias + ": revoked access tokens of session or subject " + subject.opaqueId());
        }
        else {
            logger.warn(alias + " event of SET " + eventContext.eventToken().jti()
                    + " has no subject that identifies a session or user by sid or sub, no tokens were revoked");
        }
    }

}
