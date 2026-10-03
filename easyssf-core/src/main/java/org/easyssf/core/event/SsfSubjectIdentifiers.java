package org.easyssf.core.event;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creates subject identifiers (RFC 9493) as they appear in the {@code sub_id} claim of a
 * SET and in requests to add or remove subjects of a stream.
 *
 * @see SsfSubject
 */
public final class SsfSubjectIdentifiers {

    private SsfSubjectIdentifiers() {
    }

    /**
     * A subject identified by its issuer and its identifier at that issuer.
     */
    public static Map<String, Object> issSub(String issuer, String subject) {
        return identifier("iss_sub", "iss", issuer, "sub", subject);
    }

    public static Map<String, Object> email(String email) {
        return identifier("email", "email", email);
    }

    public static Map<String, Object> opaque(String id) {
        return identifier("opaque", "id", id);
    }

    /**
     * A subject made up of a user and a session.
     * @param user the identifier of the user, may be {@code null}
     * @param session the identifier of the session, may be {@code null}
     */
    public static Map<String, Object> complex(Map<String, Object> user, Map<String, Object> session) {
        Map<String, Object> identifier = new LinkedHashMap<>();
        identifier.put("format", "complex");
        if (user != null) {
            identifier.put("user", user);
        }
        if (session != null) {
            identifier.put("session", session);
        }
        return identifier;
    }

    private static Map<String, Object> identifier(String format, String... members) {
        Map<String, Object> identifier = new LinkedHashMap<>();
        identifier.put("format", format);
        for (int i = 0; i < members.length; i += 2) {
            identifier.put(members[i], members[i + 1]);
        }
        return identifier;
    }

}
