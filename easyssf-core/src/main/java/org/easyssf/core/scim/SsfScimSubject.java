package org.easyssf.core.scim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfSubject;
import org.easyssf.core.event.SsfSubjectIdentifier;
import org.easyssf.core.support.SsfAssert;

/**
 * The subject of a SCIM Event (RFC 9967, section 2.1): a SCIM resource named by a
 * {@code scim} subject identifier. The identifier carries the relative {@code uri} of the
 * resource (its resource type endpoint plus its {@code id}, for example
 * {@code /Users/2b2f880af6674ac284bae9381673d462}), the {@code externalId} and {@code id}
 * of the resource if known, and possibly further attributes of the resource that are
 * unique at the SCIM service provider, such as {@code userName}.
 *
 * @param identifier the {@code scim} subject identifier as received
 */
public record SsfScimSubject(SsfSubjectIdentifier identifier) {

    public SsfScimSubject {
        SsfAssert.notNull(identifier, "identifier must not be null");
        if (!identifier.isScim()) {
            throw new IllegalArgumentException(
                    "A SCIM subject needs a subject identifier of format 'scim', not '" + identifier.format() + "'");
        }
    }

    /**
     * @param identifier a subject identifier, may be {@code null}
     * @return the SCIM subject, {@code null} if the identifier is not of format
     * {@code scim}
     */
    public static SsfScimSubject from(SsfSubjectIdentifier identifier) {
        return (identifier != null && identifier.isScim()) ? new SsfScimSubject(identifier) : null;
    }

    /**
     * @param subject the subject of a SET, may be {@code null}
     * @return the SCIM subject, {@code null} if the subject is not a {@code scim}
     * identifier (nor a complex subject whose {@code user} is one)
     */
    public static SsfScimSubject from(SsfSubject subject) {
        return (subject != null) ? from(subject.userIdentifier()) : null;
    }

    /**
     * @return the relative path of the resource at the SCIM service provider, which RFC
     * 9967 requires; {@code null} if the transmitter left it out nevertheless
     */
    public String uri() {
        return this.identifier.claim("uri");
    }

    /**
     * @return the resource type endpoint of the {@link #uri()}, for example {@code Users}
     * or {@code Groups}; {@code null} if the uri has no such segment
     */
    public String resourceType() {
        List<String> segments = segments();
        return (segments.size() >= 2) ? segments.get(segments.size() - 2) : null;
    }

    /**
     * @return the {@code id} of the resource: the {@code id} attribute of the identifier
     * or, if absent, the last segment of the {@link #uri()}; {@code null} if there is
     * neither
     */
    public String id() {
        String id = this.identifier.claim("id");
        if (id != null) {
            return id;
        }
        List<String> segments = segments();
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }

    /**
     * @return the {@code externalId} of the resource, the identifier the receiver's
     * domain knows it by; {@code null} if not known
     */
    public String externalId() {
        return this.identifier.claim("externalId");
    }

    /**
     * @return a further attribute of the identifier, such as {@code userName};
     * {@code null} if absent or not a non-empty string
     */
    public String attribute(String name) {
        return this.identifier.claim(name);
    }

    /**
     * @return all members of the identifier as received, including {@code format}
     */
    public Map<String, Object> claims() {
        return this.identifier.claims();
    }

    private List<String> segments() {
        String uri = uri();
        if (uri == null) {
            return List.of();
        }
        int query = uri.indexOf('?');
        if (query >= 0) {
            uri = uri.substring(0, query);
        }
        List<String> segments = new ArrayList<>();
        for (String segment : uri.split("/")) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }

}
