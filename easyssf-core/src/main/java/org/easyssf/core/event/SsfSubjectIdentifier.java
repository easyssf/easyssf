package org.easyssf.core.event;

import java.util.List;
import java.util.Map;

import org.easyssf.core.support.SsfCollections;

/**
 * A subject identifier (RFC 9493): a {@code format} and the members that format defines.
 * Any format is represented, the ones of RFC 9493 and the {@code scim} format of SCIM
 * Events (RFC 9967) are known by name.
 *
 * @param format the format, never {@code null}
 * @param claims the members of the identifier as received, including {@code format}
 */
public record SsfSubjectIdentifier(String format, Map<String, Object> claims) {

    public static final String ACCOUNT = "account";

    public static final String EMAIL = "email";

    public static final String ISS_SUB = "iss_sub";

    public static final String OPAQUE = "opaque";

    public static final String PHONE_NUMBER = "phone_number";

    public static final String DID = "did";

    public static final String URI = "uri";

    public static final String ALIASES = "aliases";

    /**
     * The format of SCIM Events (RFC 9967, section 2.1): a SCIM resource named by its
     * relative {@code uri}, with its {@code externalId} and {@code id} if known.
     * @see org.easyssf.core.scim.SsfScimSubject
     */
    public static final String SCIM = "scim";

    public SsfSubjectIdentifier {
        if (format == null || format.isBlank()) {
            throw new IllegalArgumentException("format must not be empty");
        }
        claims = SsfCollections.copyOf((claims != null) ? claims : Map.of());
    }

    /**
     * @param identifier the identifier as received, with a {@code format} member
     * @return the identifier, {@code null} if the map is {@code null}, empty or has no
     * {@code format}
     */
    public static SsfSubjectIdentifier from(Map<String, Object> identifier) {
        if (identifier == null || !(identifier.get("format") instanceof String format) || format.isBlank()) {
            return null;
        }
        return new SsfSubjectIdentifier(format, identifier);
    }

    public boolean is(String format) {
        return this.format.equals(format);
    }

    public boolean isIssSub() {
        return is(ISS_SUB);
    }

    public boolean isEmail() {
        return is(EMAIL);
    }

    public boolean isOpaque() {
        return is(OPAQUE);
    }

    public boolean isScim() {
        return is(SCIM);
    }

    /**
     * @return a member of the identifier, {@code null} if absent or not a non-empty
     * string
     */
    public String claim(String name) {
        return (this.claims.get(name) instanceof String value && !value.isBlank()) ? value : null;
    }

    /**
     * @return the issuer of an {@code iss_sub} identifier, {@code null} for other formats
     */
    public String issuer() {
        return isIssSub() ? claim("iss") : null;
    }

    /**
     * The member that identifies the subject within its format: {@code sub} of
     * {@code iss_sub}, {@code email}, {@code id} of {@code opaque}, {@code uri} of
     * {@code account}, {@code uri} and {@code scim}, {@code phone_number}, {@code url} of
     * {@code did}.
     * @return the value, {@code null} for {@code aliases} and unknown formats
     */
    public String value() {
        return switch (this.format) {
            case ISS_SUB -> claim("sub");
            case EMAIL -> claim("email");
            case OPAQUE -> claim("id");
            case ACCOUNT, URI, SCIM -> claim("uri");
            case PHONE_NUMBER -> claim("phone_number");
            case DID -> claim("url");
            default -> null;
        };
    }

    /**
     * @return the identifiers of an {@code aliases} identifier, empty for other formats
     */
    public List<SsfSubjectIdentifier> aliases() {
        if (!is(ALIASES) || !(this.claims.get("identifiers") instanceof List<?> identifiers)) {
            return List.of();
        }
        return identifiers.stream()
            .filter(Map.class::isInstance)
            .map((identifier) -> from(asMap(identifier)))
            .filter((identifier) -> identifier != null)
            .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

}
