package org.easyssf.receiver.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfSubject;
import org.easyssf.core.event.SsfSubjectIdentifier;
import org.easyssf.core.scim.SsfScimSubject;
import org.easyssf.core.support.SsfAssert;

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
 *
 * <p>
 * A {@code scim} identifier (RFC 9967) names a resource of a SCIM service provider, whose
 * identifiers an OpenID Provider may or may not share. Which attributes of the resource
 * are compared with which claims is configurable; by default
 * ({@link #DEFAULT_SCIM_ATTRIBUTE_CLAIMS}) the resource matches a user whose {@code sub}
 * is its {@code externalId} or its {@code id}, or whose {@code preferred_username} is its
 * {@code userName}. A SCIM attribute that is multi-valued, such as {@code emails},
 * matches if any of its values (the {@code value} of each entry) does. Values compared
 * with the {@code email} claim match ignoring case, all others exactly.
 */
public final class SsfSubjectClaimsMatcher {

    /**
     * The attributes of a {@code scim} subject and the claims they are compared with by
     * default: {@code externalId} and {@code id} with {@code sub}, {@code userName} with
     * {@code preferred_username}.
     */
    public static final Map<String, String> DEFAULT_SCIM_ATTRIBUTE_CLAIMS = Map.of("externalId", "sub", "id", "sub",
            "userName", "preferred_username");

    private SsfSubjectClaimsMatcher() {
    }

    /**
     * Matches with the {@link #DEFAULT_SCIM_ATTRIBUTE_CLAIMS}.
     * @param subject the subject of the event
     * @param claims the claims of the user or session
     * @param name the name of the user, compared with the subject of the event if the
     * claims have no {@code sub}, may be {@code null}
     * @return whether the subject refers to the user or session
     */
    public static boolean matches(SsfSubject subject, Map<String, ?> claims, String name) {
        return matches(subject, claims, name, DEFAULT_SCIM_ATTRIBUTE_CLAIMS);
    }

    /**
     * @param subject the subject of the event
     * @param claims the claims of the user or session
     * @param name the name of the user, compared with the subject of the event if the
     * claims have no {@code sub}, may be {@code null}
     * @param scimAttributeClaims the attributes of a {@code scim} subject (RFC 9967) and
     * the claim each is compared with, for example {@code externalId} with {@code sub};
     * the subject matches if any pair does
     * @return whether the subject refers to the user or session
     */
    public static boolean matches(SsfSubject subject, Map<String, ?> claims, String name,
            Map<String, String> scimAttributeClaims) {
        SsfAssert.notNull(scimAttributeClaims, "scimAttributeClaims must not be null");
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
            return matchesUser(user, claims, userSubject, scimAttributeClaims);
        }
        String opaqueId = subject.opaqueId();
        if (opaqueId != null) {
            // an opaque identifier does not tell whether it names a session or a user
            return opaqueId.equals(sessionId) || opaqueId.equals(userSubject);
        }
        return false;
    }

    private static boolean matchesUser(SsfSubjectIdentifier user, Map<String, ?> claims, String userSubject,
            Map<String, String> scimAttributeClaims) {
        String value = user.value();
        return switch (user.format()) {
            case SsfSubjectIdentifier.ISS_SUB -> value != null && value.equals(userSubject) && user.issuer() != null
                    && user.issuer().equals(string(claims, "iss"));
            case SsfSubjectIdentifier.EMAIL -> value != null && value.equalsIgnoreCase(string(claims, "email"));
            case SsfSubjectIdentifier.OPAQUE -> value != null && value.equals(userSubject);
            case SsfSubjectIdentifier.ALIASES -> user.aliases()
                .stream()
                .anyMatch((alias) -> matchesUser(alias, claims, userSubject, scimAttributeClaims));
            case SsfSubjectIdentifier.SCIM ->
                matchesScimResource(SsfScimSubject.from(user), claims, userSubject, scimAttributeClaims);
            // phone_number, account, did, uri and unknown formats: a claim of the same
            // name
            default -> value != null && value.equals(string(claims, user.format()));
        };
    }

    private static boolean matchesScimResource(SsfScimSubject resource, Map<String, ?> claims, String userSubject,
            Map<String, String> scimAttributeClaims) {
        for (Map.Entry<String, String> attributeClaim : scimAttributeClaims.entrySet()) {
            String claimName = attributeClaim.getValue();
            String claimValue = "sub".equals(claimName) ? userSubject : string(claims, claimName);
            if (claimValue == null) {
                continue;
            }
            for (String value : scimAttributeValues(resource, attributeClaim.getKey())) {
                if ("email".equals(claimName) ? value.equalsIgnoreCase(claimValue) : value.equals(claimValue)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The values of an attribute of a SCIM resource: the string of a single-valued
     * attribute, the {@code id} resolved from the uri if the resource has no {@code id}
     * attribute, or the strings and the {@code value} members of a multi-valued attribute
     * such as {@code emails}.
     */
    private static List<String> scimAttributeValues(SsfScimSubject resource, String attribute) {
        if ("id".equals(attribute)) {
            String id = resource.id();
            return (id != null) ? List.of(id) : List.of();
        }
        Object value = resource.claims().get(attribute);
        if (value instanceof String string && !string.isBlank()) {
            return List.of(string);
        }
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        for (Object element : values) {
            if (element instanceof String string && !string.isBlank()) {
                strings.add(string);
            }
            else if (element instanceof Map<?, ?> complex && complex.get("value") instanceof String string
                    && !string.isBlank()) {
                strings.add(string);
            }
        }
        return strings;
    }

    private static String string(Map<String, ?> claims, String name) {
        Object value = claims.get(name);
        return (value != null) ? value.toString() : null;
    }

}
