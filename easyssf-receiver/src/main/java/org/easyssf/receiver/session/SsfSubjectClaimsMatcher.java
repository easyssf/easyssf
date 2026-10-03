package org.easyssf.receiver.session;

import java.util.Map;

import org.easyssf.core.event.SsfSubject;
import org.easyssf.core.event.SsfSubjectIdentifier;

/**
 * Decides whether the subject of a security event refers to a user or session that is
 * described by claims, typically the claims of the ID token the user logged in with:
 * {@code sid} for a session, {@code iss} and {@code sub}, {@code email} or
 * {@code phone_number} for a user.
 *
 * <p>
 * An {@code iss_sub} identifier names the user at an issuer, so both have to match:
 * claims without an {@code iss} never match such a subject. A session is matched by its
 * {@code sid} and, if the event names the issuer of the session or of its user, by that
 * issuer as well.
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
        SsfSubjectIdentifier session = subject.session();
        if (session != null) {
            if (session.value() == null || !session.value().equals(sessionId)) {
                return false;
            }
            // the session belongs to the issuer the event names, in the session
            // identifier or the
            // user it belongs to; session identifiers are not unique across issuers
            String issuer = (session.issuer() != null) ? session.issuer()
                    : ((subject.user() != null) ? subject.user().issuer() : null);
            return issuer == null || issuer.equals(string(claims, "iss"));
        }
        SsfSubjectIdentifier user = subject.userIdentifier();
        if (user != null) {
            return matchesUser(user, claims, userSubject);
        }
        String opaqueId = subject.opaqueId();
        if (opaqueId != null) {
            // an opaque identifier does not tell whether it names a session or a user
            return opaqueId.equals(sessionId) || opaqueId.equals(userSubject);
        }
        return false;
    }

    private static boolean matchesUser(SsfSubjectIdentifier user, Map<String, ?> claims, String userSubject) {
        String value = user.value();
        return switch (user.format()) {
            case SsfSubjectIdentifier.ISS_SUB -> value != null && value.equals(userSubject) && user.issuer() != null
                    && user.issuer().equals(string(claims, "iss"));
            case SsfSubjectIdentifier.EMAIL -> value != null && value.equalsIgnoreCase(string(claims, "email"));
            case SsfSubjectIdentifier.OPAQUE -> value != null && value.equals(userSubject);
            case SsfSubjectIdentifier.ALIASES ->
                user.aliases().stream().anyMatch((alias) -> matchesUser(alias, claims, userSubject));
            // phone_number, account, did, uri and unknown formats: a claim of the same
            // name
            default -> value != null && value.equals(string(claims, user.format()));
        };
    }

    private static String string(Map<String, ?> claims, String name) {
        Object value = claims.get(name);
        return (value != null) ? value.toString() : null;
    }

}
