package org.easyssf.core.scim;

import java.util.LinkedHashMap;
import java.util.Map;

import org.easyssf.core.event.SsfSubject;
import org.easyssf.core.event.SsfSubjectIdentifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.easyssf.core.event.SsfSubjectIdentifiers.complex;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;
import static org.easyssf.core.event.SsfSubjectIdentifiers.scim;

class SsfScimSubjectTests {

    private static final String USER_URI = "/Users/2b2f880af6674ac284bae9381673d462";

    @Test
    void resolvesResourceTypeAndIdFromTheUri() {
        SsfScimSubject subject = SsfScimSubject.from(SsfSubject.from(scim(USER_URI, "jdoe")));
        assertThat(subject.uri()).isEqualTo(USER_URI);
        assertThat(subject.resourceType()).isEqualTo("Users");
        assertThat(subject.id()).isEqualTo("2b2f880af6674ac284bae9381673d462");
        assertThat(subject.externalId()).isEqualTo("jdoe");
        assertThat(subject.claims()).containsEntry("format", "scim").containsEntry("uri", USER_URI);
    }

    @Test
    void toleratesAbsoluteUrisAndQueries() {
        assertThat(scimSubject(Map.of("uri", "https://scim.example.com/v2/Groups/abc?attributes=members")).id())
            .isEqualTo("abc");
        assertThat(scimSubject(Map.of("uri", "https://scim.example.com/v2/Groups/abc")).resourceType())
            .isEqualTo("Groups");
        assertThat(scimSubject(Map.of("uri", "abc")).resourceType()).isNull();
        assertThat(scimSubject(Map.of("uri", "abc")).id()).isEqualTo("abc");
    }

    @Test
    void idAttributeWinsOverTheUri() {
        SsfScimSubject subject = scimSubject(Map.of("uri", USER_URI, "id", "legacy-id"));
        assertThat(subject.id()).isEqualTo("legacy-id");
    }

    @Test
    void furtherAttributesAreAvailableByName() {
        SsfScimSubject subject = scimSubject(Map.of("uri", USER_URI, "userName", "jdoe", "emails", Map.of()));
        assertThat(subject.attribute("userName")).isEqualTo("jdoe");
        assertThat(subject.attribute("emails")).isNull();
        assertThat(subject.attribute("externalId")).isNull();
    }

    @Test
    void subjectWithoutUriHasNoResource() {
        SsfScimSubject subject = scimSubject(Map.of("externalId", "jdoe"));
        assertThat(subject.uri()).isNull();
        assertThat(subject.resourceType()).isNull();
        assertThat(subject.id()).isNull();
        assertThat(subject.externalId()).isEqualTo("jdoe");
    }

    @Test
    void onlyScimIdentifiersAreScimSubjects() {
        assertThat(SsfScimSubject.from(SsfSubject.from(opaque("abc")))).isNull();
        assertThat(SsfScimSubject.from(SsfSubject.empty())).isNull();
        assertThat(SsfScimSubject.from((SsfSubject) null)).isNull();
        assertThat(SsfScimSubject.from((SsfSubjectIdentifier) null)).isNull();
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new SsfScimSubject(SsfSubjectIdentifier.from(opaque("abc"))))
            .withMessageContaining("'scim'");
    }

    @Test
    void userOfAComplexSubjectMayBeAScimResource() {
        SsfSubject subject = SsfSubject.from(complex(scim(USER_URI), opaque("session-1")));
        assertThat(SsfScimSubject.from(subject).resourceType()).isEqualTo("Users");
        assertThat(subject.userIdentifier().isScim()).isTrue();
        assertThat(subject.userIdentifier().value()).isEqualTo(USER_URI);
    }

    private static SsfScimSubject scimSubject(Map<String, Object> attributes) {
        Map<String, Object> identifier = new LinkedHashMap<>(Map.of("format", "scim"));
        identifier.putAll(attributes);
        return SsfScimSubject.from(SsfSubjectIdentifier.from(identifier));
    }

}
