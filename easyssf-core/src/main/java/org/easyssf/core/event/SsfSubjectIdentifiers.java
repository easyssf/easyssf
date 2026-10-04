package org.easyssf.core.event;

import java.util.LinkedHashMap;
import java.util.List;
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
     * A subject identified by an {@code acct:} URI (RFC 7565).
     */
    public static Map<String, Object> account(String uri) {
        return identifier("account", "uri", uri);
    }

    /**
     * A subject identified by a telephone number in E.164 format.
     */
    public static Map<String, Object> phoneNumber(String phoneNumber) {
        return identifier("phone_number", "phone_number", phoneNumber);
    }

    /**
     * A subject identified by a decentralized identifier.
     */
    public static Map<String, Object> did(String url) {
        return identifier("did", "url", url);
    }

    /**
     * A subject identified by a URI.
     */
    public static Map<String, Object> uri(String uri) {
        return identifier("uri", "uri", uri);
    }

    /**
     * A SCIM resource (RFC 9967), the subject of SCIM Events.
     * @param uri the relative path of the resource, for example {@code /Users/2b2f880a}
     */
    public static Map<String, Object> scim(String uri) {
        return identifier("scim", "uri", uri);
    }

    /**
     * A SCIM resource (RFC 9967), the subject of SCIM Events.
     * @param uri the relative path of the resource, for example {@code /Users/2b2f880a}
     * @param externalId the {@code externalId} of the resource, may be {@code null}
     */
    public static Map<String, Object> scim(String uri, String externalId) {
        Map<String, Object> identifier = scim(uri);
        if (externalId != null) {
            identifier.put("externalId", externalId);
        }
        return identifier;
    }

    /**
     * A subject with several identifiers that all name it.
     */
    public static Map<String, Object> aliases(List<Map<String, Object>> identifiers) {
        Map<String, Object> identifier = new LinkedHashMap<>();
        identifier.put("format", "aliases");
        identifier.put("identifiers", List.copyOf(identifiers));
        return identifier;
    }

    /**
     * A complex subject with the given members ({@code user}, {@code session},
     * {@code device}, {@code tenant}, ...), each a subject identifier.
     */
    public static Map<String, Object> complex(Map<String, Map<String, Object>> members) {
        Map<String, Object> identifier = new LinkedHashMap<>();
        identifier.put("format", "complex");
        members.forEach((name, member) -> {
            if (member != null) {
                identifier.put(name, member);
            }
        });
        return identifier;
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
