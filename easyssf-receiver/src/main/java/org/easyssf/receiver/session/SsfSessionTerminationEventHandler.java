package org.easyssf.receiver.session;

import java.util.Collection;
import java.util.List;

import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.event.SsfSubject;
import org.easyssf.core.event.SsfSubjectIdentifier;
import org.easyssf.core.support.SsfAssert;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Terminates local sessions in reaction to security events.
 *
 * <p>
 * Session events (by default CAEP {@code session-revoked}) terminate the session named by
 * the event, or all sessions of its user if it names no session. User events (by default
 * CAEP {@code credential-change}) always terminate all sessions of the user.
 */
public class SsfSessionTerminationEventHandler implements SsfEventHandler {

    private static final Logger logger = LoggerFactory.getLogger(SsfSessionTerminationEventHandler.class);

    private final SsfSessionTerminator sessionTerminator;

    private final List<String> sessionEventTypes;

    private final List<String> userEventTypes;

    /**
     * @param sessionTerminator terminates the sessions
     * @param sessionEventTypes the event types (alias or URI) that terminate the sessions
     * of their subject
     * @param userEventTypes the event types (alias or URI) that terminate all sessions of
     * their subject's user
     */
    public SsfSessionTerminationEventHandler(SsfSessionTerminator sessionTerminator,
            Collection<String> sessionEventTypes, Collection<String> userEventTypes) {
        SsfAssert.notNull(sessionTerminator, "sessionTerminator must not be null");
        SsfAssert.notNull(sessionEventTypes, "sessionEventTypes must not be null");
        SsfAssert.notNull(userEventTypes, "userEventTypes must not be null");
        this.sessionTerminator = sessionTerminator;
        this.sessionEventTypes = sessionEventTypes.stream().map(SsfEventTypes::resolve).toList();
        this.userEventTypes = userEventTypes.stream().map(SsfEventTypes::resolve).toList();
    }

    @Override
    public void handle(SsfEventContext eventContext) {
        for (String eventType : this.sessionEventTypes) {
            if (eventContext.hasEvent(eventType)) {
                terminate(eventContext, eventType, eventContext.subjectFor(eventType));
            }
        }
        for (String eventType : this.userEventTypes) {
            if (eventContext.hasEvent(eventType)) {
                terminate(eventContext, eventType, eventContext.subjectFor(eventType).withoutSession());
            }
        }
    }

    private void terminate(SsfEventContext eventContext, String eventType, SsfSubject subject) {
        String alias = SsfEventTypes.aliasOf(eventType);
        if (subject.isEmpty()) {
            logger.warn(alias + " event of SET " + eventContext.eventToken().jti()
                    + " has no subject that identifies a session or user, no sessions were terminated");
            return;
        }
        int terminated = this.sessionTerminator.terminateSessions(subject);
        logger.info(alias + ": terminated " + terminated + " session(s) of " + describe(subject));
    }

    private static String describe(SsfSubject subject) {
        if (subject.sessionId() != null) {
            return "session " + subject.sessionId();
        }
        SsfSubjectIdentifier user = subject.userIdentifier();
        if (user != null) {
            return "user " + ((user.value() != null) ? user.value() : user.format());
        }
        return "session or subject " + subject.opaqueId();
    }

}
