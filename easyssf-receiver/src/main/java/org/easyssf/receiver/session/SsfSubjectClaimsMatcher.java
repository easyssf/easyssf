package org.easyssf.receiver.session;

import java.util.Map;

import org.easyssf.core.event.SsfSubject;

/**
 * Decides whether the subject of a security event refers to a user or session that is
 * described by claims, typically the claims of the ID token the user logged in with:
 * {@code sid} for a session, {@code sub} (and {@code iss}) or {@code email} for a user.
 */
public final class SsfSubjectClaimsMatcher {

    private SsfSubjectClaimsMatcher() {
    }

    /**
     * @param subject the subject of the event
     * @param claims the claims of the user or session
     * @param name the name of the user, compared with the subject of the event if the
     * claims have no {@code sub}, may be {@code null}
     * @return whether the subject refers to the user or session
     */
    public static boolean matches(SsfSubject subject, Map<String, ?> claims, String name) {
        String sessionId = string(claims, "sid");
        String userSubject = string(claims, "sub");
        if (userSubject == null) {
            userSubject = name;
        }
        if (subject.sessionId() != null) {
            return subject.sessionId().equals(sessionId);
        }
        if (subject.subject() != null) {
            String issuer = string(claims, "iss");
            return subject.subject().equals(userSubject)
                    && (subject.issuer() == null || issuer == null || subject.issuer().equals(issuer));
        }
        if (subject.email() != null) {
            return subject.email().equalsIgnoreCase(string(claims, "email"));
        }
        if (subject.opaqueId() != null) {
            // an opaque identifier does not tell whether it names a session or a user
            return subject.opaqueId().equals(sessionId) || subject.opaqueId().equals(userSubject);
        }
        return false;
    }

    private static String string(Map<String, ?> claims, String name) {
        Object value = claims.get(name);
        return (value != null) ? value.toString() : null;
    }

}
