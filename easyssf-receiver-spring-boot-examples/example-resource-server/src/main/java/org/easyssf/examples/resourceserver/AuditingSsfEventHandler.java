package org.easyssf.examples.resourceserver;

import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.event.SsfSubject;
import org.easyssf.receiver.event.SsfEventContext;
import org.easyssf.receiver.event.SsfEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * An application specific handler. It is invoked for every verified SET, in addition to
 * the handler of the starter that revokes the access tokens.
 */
@Component
class AuditingSsfEventHandler implements SsfEventHandler {

    private static final Logger logger = LoggerFactory.getLogger(AuditingSsfEventHandler.class);

    @Override
    public void handle(SsfEventContext eventContext) {
        for (String eventType : eventContext.eventTypes()) {
            SsfSubject subject = eventContext.subjectFor(eventType);
            logger.info("Security event {} for user {} and session {}: {}", SsfEventTypes.aliasOf(eventType),
                    subject.subject(), subject.sessionId(), eventContext.eventFor(eventType));
        }
    }

}
