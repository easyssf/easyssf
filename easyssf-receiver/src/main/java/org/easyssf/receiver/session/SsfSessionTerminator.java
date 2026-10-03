package org.easyssf.receiver.session;

import org.easyssf.core.event.SsfSubject;

/**
 * Terminates the local sessions of a subject. Implement it if sessions are not kept by
 * the servlet container, for example when using Spring Session.
 */
@FunctionalInterface
public interface SsfSessionTerminator {

    /**
     * Terminates the sessions that belong to the given subject: the session with the
     * subject's session identifier if it has one, otherwise all sessions of its user.
     * @return the number of terminated sessions
     */
    int terminateSessions(SsfSubject subject);

}
