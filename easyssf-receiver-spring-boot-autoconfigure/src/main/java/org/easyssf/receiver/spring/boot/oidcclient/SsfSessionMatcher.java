package org.easyssf.receiver.spring.boot.oidcclient;

import org.easyssf.core.event.SsfSubject;
import org.springframework.security.core.Authentication;

/**
 * Decides whether a local session belongs to the subject of a security event.
 */
@FunctionalInterface
public interface SsfSessionMatcher {

    /**
     * @param subject the subject of the event
     * @param authentication the authentication of a local session
     * @return whether the session belongs to the subject
     */
    boolean matches(SsfSubject subject, Authentication authentication);

}
