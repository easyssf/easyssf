package org.easyssf.receiver.spring.boot.oidcclient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;

import org.easyssf.core.event.SsfSubject;
import org.easyssf.receiver.session.SsfSessionTerminator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.util.Assert;

/**
 * {@link SsfSessionTerminator} for sessions kept by the servlet container. Tracks the
 * live {@link HttpSession HttpSessions} and invalidates those whose authentication
 * matches the subject of an event.
 *
 * <p>
 * Only sees the sessions of the instance it runs in. When sessions are replicated or kept
 * in an external store, provide a {@link SsfSessionTerminator} for that store.
 */
public class HttpSessionSsfSessionTerminator
        implements SsfSessionTerminator, HttpSessionListener, HttpSessionIdListener {

    private final Map<String, HttpSession> sessions = new ConcurrentHashMap<>();

    private final SsfSessionMatcher sessionMatcher;

    private String securityContextAttributeName = HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

    public HttpSessionSsfSessionTerminator(SsfSessionMatcher sessionMatcher) {
        Assert.notNull(sessionMatcher, "sessionMatcher must not be null");
        this.sessionMatcher = sessionMatcher;
    }

    /**
     * Sets the name of the session attribute the {@link SecurityContext} is stored in.
     */
    public void setSecurityContextAttributeName(String securityContextAttributeName) {
        Assert.hasText(securityContextAttributeName, "securityContextAttributeName must not be empty");
        this.securityContextAttributeName = securityContextAttributeName;
    }

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        this.sessions.put(event.getSession().getId(), event.getSession());
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        this.sessions.remove(event.getSession().getId());
    }

    @Override
    public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
        this.sessions.remove(oldSessionId);
        this.sessions.put(event.getSession().getId(), event.getSession());
    }

    @Override
    public int terminateSessions(SsfSubject subject) {
        int terminated = 0;
        for (HttpSession session : this.sessions.values()) {
            try {
                if (belongsTo(subject, session)) {
                    session.invalidate();
                    terminated++;
                }
            }
            catch (IllegalStateException ex) {
                // the session was invalidated concurrently
            }
        }
        return terminated;
    }

    private boolean belongsTo(SsfSubject subject, HttpSession session) {
        if (session.getAttribute(this.securityContextAttributeName) instanceof SecurityContext securityContext) {
            Authentication authentication = securityContext.getAuthentication();
            return authentication != null && this.sessionMatcher.matches(subject, authentication);
        }
        return false;
    }

}
