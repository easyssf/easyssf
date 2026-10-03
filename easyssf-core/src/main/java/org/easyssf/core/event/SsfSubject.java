package org.easyssf.core.event;

import java.util.LinkedHashMap;
import java.util.Map;

import org.easyssf.core.support.SsfCollections;

/**
 * The subject of a SET (its {@code sub_id} claim): a simple subject identifier (RFC
 * 9493), or a complex subject whose members ({@code user}, {@code session},
 * {@code device}, {@code tenant}, ...) are identifiers.
 *
 * <p>
 * {@link #userIdentifier()} and {@link #session()} are the two members the receiver acts
 * on, and {@link #issuer()}, {@link #subject()}, {@link #sessionId()}, {@link #email()}
 * and {@link #opaqueId()} are shortcuts to their most common values. Everything else is
 * available through {@link #member(String)} and {@link #raw()}.
 *
 * <p>
 * Keycloak reports the revocation of all sessions of a user with a {@code session} member
 * whose identifier is {@value #ALL_SESSIONS}. Such a subject is resolved like one that
 * names only the user: it has no {@link #session()}.
 *
 * @param raw the subject identifier as received, empty if the SET carried none
 * @param identifier the identifier of a simple subject, {@code null} for a complex
 * subject or none
 * @param members the members of a complex subject by name, empty for a simple subject
 */
public record SsfSubject(Map<String, Object> raw, SsfSubjectIdentifier identifier,
        Map<String, SsfSubjectIdentifier> members) {

    /**
     * Session identifier Keycloak uses to refer to all sessions of a user.
     */
    public static final String ALL_SESSIONS = "ALL";

    public static final String USER = "user";

    public static final String SESSION = "session";

    public static final String DEVICE = "device";

    public static final String APPLICATION = "application";

    public static final String TENANT = "tenant";

    public static final String ORG_UNIT = "org_unit";

    public static final String GROUP = "group";

    private static final SsfSubject EMPTY = new SsfSubject(Map.of(), null, Map.of());

    public SsfSubject {
        raw = SsfCollections.copyOf((raw != null) ? raw : Map.of());
        members = SsfCollections.copyOf((members != null) ? members : Map.of());
    }

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
        String format = (subjectId.get("format") instanceof String value) ? value : null;
        // Early SSF drafts used complex subjects without a format member
        boolean complex = "complex".equals(format) || (format == null && hasMembers(subjectId));
        if (!complex) {
            return new SsfSubject(subjectId, SsfSubjectIdentifier.from(subjectId), Map.of());
        }
        Map<String, SsfSubjectIdentifier> members = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : subjectId.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> member) {
                SsfSubjectIdentifier identifier = SsfSubjectIdentifier.from(asMap(member));
                if (identifier != null) {
                    members.put(entry.getKey(), identifier);
                }
            }
        }
        SsfSubjectIdentifier session = members.get(SESSION);
        if (session != null && session.isOpaque() && ALL_SESSIONS.equals(session.value())
                && members.containsKey(USER)) {
            members.remove(SESSION);
        }
        return new SsfSubject(subjectId, null, members);
    }

    private static boolean hasMembers(Map<String, Object> subjectId) {
        return subjectId.values().stream().anyMatch(Map.class::isInstance);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    /**
     * Whether this is a complex subject.
     */
    public boolean isComplex() {
        return !this.members.isEmpty();
    }

    /**
     * @return the member of a complex subject with the given name, {@code null} if absent
     */
    public SsfSubjectIdentifier member(String name) {
        return this.members.get(name);
    }

    public SsfSubjectIdentifier user() {
        return member(USER);
    }

    public SsfSubjectIdentifier session() {
        return member(SESSION);
    }

    public SsfSubjectIdentifier device() {
        return member(DEVICE);
    }

    public SsfSubjectIdentifier application() {
        return member(APPLICATION);
    }

    public SsfSubjectIdentifier tenant() {
        return member(TENANT);
    }

    public SsfSubjectIdentifier orgUnit() {
        return member(ORG_UNIT);
    }

    public SsfSubjectIdentifier group() {
        return member(GROUP);
    }

    /**
     * The identifier that names the user: the {@code user} member of a complex subject,
     * or the identifier of a simple subject unless it is {@code opaque}, which may as
     * well name a session (see {@link #opaqueId()}).
     * @return the identifier, {@code null} if the subject does not name a user
     */
    public SsfSubjectIdentifier userIdentifier() {
        if (this.identifier != null) {
            return this.identifier.isOpaque() ? null : this.identifier;
        }
        return user();
    }

    /**
     * @return the issuer of the user, from an {@code iss_sub} identifier, may be
     * {@code null}
     */
    public String issuer() {
        SsfSubjectIdentifier user = userIdentifier();
        return (user != null) ? user.issuer() : null;
    }

    /**
     * @return the identifier of the user at the issuer: {@code sub} of an {@code iss_sub}
     * identifier, or the {@code id} of an {@code opaque} user of a complex subject, may
     * be {@code null}
     */
    public String subject() {
        SsfSubjectIdentifier user = userIdentifier();
        return (user != null && (user.isIssSub() || user.isOpaque())) ? user.value() : null;
    }

    /**
     * @return the identifier of the session, may be {@code null}
     */
    public String sessionId() {
        SsfSubjectIdentifier session = session();
        return (session != null) ? session.value() : null;
    }

    /**
     * @return the email address of the user, from an {@code email} identifier, may be
     * {@code null}
     */
    public String email() {
        SsfSubjectIdentifier user = userIdentifier();
        return (user != null && user.isEmail()) ? user.value() : null;
    }

    /**
     * @return the identifier of a simple {@code opaque} subject, which does not tell
     * whether it names a user or a session, may be {@code null}
     */
    public String opaqueId() {
        return (this.identifier != null && this.identifier.isOpaque()) ? this.identifier.value() : null;
    }

    /**
     * Whether the SET carried no subject identifier that could be resolved.
     */
    public boolean isEmpty() {
        return this.identifier == null && this.members.isEmpty();
    }

    /**
     * Whether this subject identifies a user (as opposed to only a session).
     */
    public boolean hasUser() {
        return userIdentifier() != null;
    }

    /**
     * Returns this subject without its session, i.e. widened to the user.
     */
    public SsfSubject withoutSession() {
        if (session() == null) {
            return this;
        }
        Map<String, SsfSubjectIdentifier> members = new LinkedHashMap<>(this.members);
        members.remove(SESSION);
        return new SsfSubject(this.raw, this.identifier, members);
    }

}
