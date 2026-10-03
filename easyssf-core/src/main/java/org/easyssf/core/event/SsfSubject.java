package org.easyssf.core.event;

import java.util.Map;

/**
 * The identifiers of an SSF subject (RFC 9493 subject identifier), resolved from the
 * {@code sub_id} claim of a SET.
 *
 * <p>
 * Supported formats are {@code iss_sub}, {@code email}, {@code opaque} and
 * {@code complex} (with {@code user} and {@code session} members). Everything else is
 * still available through {@link #raw()}.
 *
 * <p>
 * Keycloak reports the revocation of all sessions of a user with a {@code session} member
 * whose identifier is {@value #ALL_SESSIONS}. Such a subject is resolved like one that
 * names only the user: its {@link #sessionId()} is {@code null}.
 *
 * @param raw the subject identifier as received, empty if the SET carried none
 * @param issuer issuer of the user ({@code iss_sub}), may be {@code null}
 * @param subject identifier of the user at the issuer, may be {@code null}
 * @param sessionId identifier of the session at the issuer, may be {@code null}
 * @param email email address of the user, may be {@code null}
 * @param opaqueId identifier of a simple {@code opaque} subject, which does not tell
 * whether it names a user or a session, may be {@code null}
 */
public record SsfSubject(Map<String, Object> raw, String issuer, String subject, String sessionId, String email,
        String opaqueId) {

    /**
     * Session identifier Keycloak uses to refer to all sessions of a user.
     */
    public static final String ALL_SESSIONS = "ALL";

    private static final SsfSubject EMPTY = new SsfSubject(Map.of(), null, null, null, null, null);

    public static SsfSubject empty() {
        return EMPTY;
    }

    /**
     * Resolves the given subject identifier.
     * @param subjectId the {@code sub_id} claim, may be {@code null}
     */
    public static SsfSubject from(Map<String, Object> subjectId) {
        if (subjectId == null || subjectId.isEmpty()) {
            return EMPTY;
        }
        String format = string(subjectId, "format");
        Map<String, Object> user = map(subjectId, "user");
        Map<String, Object> session = map(subjectId, "session");
        // Early SSF drafts used complex subjects without a format member
        boolean complex = "complex".equals(format) || (format == null && (user != null || session != null));
        if (!complex) {
            return simple(subjectId, subjectId);
        }
        SsfSubject resolvedUser = (user != null) ? simple(subjectId, user) : EMPTY;
        String userSubject = (resolvedUser.subject() != null) ? resolvedUser.subject() : resolvedUser.opaqueId();
        String sessionId = (session != null) ? string(session, "id") : null;
        if (ALL_SESSIONS.equals(sessionId) && (userSubject != null || resolvedUser.email() != null)) {
            sessionId = null;
        }
        return new SsfSubject(subjectId, resolvedUser.issuer(), userSubject, sessionId, resolvedUser.email(), null);
    }

    private static SsfSubject simple(Map<String, Object> raw, Map<String, Object> identifier) {
        String format = string(identifier, "format");
        if (format == null) {
            return new SsfSubject(raw, null, null, null, null, null);
        }
        return switch (format) {
            case "iss_sub" ->
                new SsfSubject(raw, string(identifier, "iss"), string(identifier, "sub"), null, null, null);
            case "email" -> new SsfSubject(raw, null, null, null, string(identifier, "email"), null);
            case "opaque" -> new SsfSubject(raw, null, null, null, null, string(identifier, "id"));
            default -> new SsfSubject(raw, null, null, null, null, null);
        };
    }

    /**
     * Whether no supported identifier could be resolved.
     */
    public boolean isEmpty() {
        return this.subject == null && this.sessionId == null && this.email == null && this.opaqueId == null;
    }

    /**
     * Whether this subject identifies a user (as opposed to only a session).
     */
    public boolean hasUser() {
        return this.subject != null || this.email != null || this.opaqueId != null;
    }

    /**
     * Returns this subject without its session identifier, i.e. widened to the user.
     */
    public SsfSubject withoutSession() {
        if (this.sessionId == null) {
            return this;
        }
        return new SsfSubject(this.raw, this.issuer, this.subject, null, this.email, this.opaqueId);
    }

    private static String string(Map<String, Object> map, String key) {
        return (map.get(key) instanceof String value && !value.isBlank()) ? value : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> map, String key) {
        return (map.get(key) instanceof Map<?, ?> value) ? (Map<String, Object>) value : null;
    }

}
