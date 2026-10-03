package org.easyssf.core.event;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easyssf.core.event.SsfSubjectIdentifiers.complex;
import static org.easyssf.core.event.SsfSubjectIdentifiers.email;
import static org.easyssf.core.event.SsfSubjectIdentifiers.issSub;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

class SsfSubjectTests {

    @Test
    void absentSubjectIsEmpty() {
        assertThat(SsfSubject.from(null).isEmpty()).isTrue();
        assertThat(SsfSubject.from(Map.of()).isEmpty()).isTrue();
    }

    @Test
    void resolvesIssSub() {
        SsfSubject subject = SsfSubject.from(issSub("https://idp.example", "alice"));
        assertThat(subject.issuer()).isEqualTo("https://idp.example");
        assertThat(subject.subject()).isEqualTo("alice");
        assertThat(subject.sessionId()).isNull();
        assertThat(subject.hasUser()).isTrue();
    }

    @Test
    void resolvesEmail() {
        assertThat(SsfSubject.from(email("alice@example.com")).email()).isEqualTo("alice@example.com");
    }

    @Test
    void simpleOpaqueSubjectIsNeitherUserNorSession() {
        SsfSubject subject = SsfSubject.from(opaque("abc"));
        assertThat(subject.opaqueId()).isEqualTo("abc");
        assertThat(subject.subject()).isNull();
        assertThat(subject.sessionId()).isNull();
    }

    @Test
    void resolvesComplexSubjectWithUserAndSession() {
        SsfSubject subject = SsfSubject.from(complex(issSub("https://idp.example", "alice"), opaque("session-1")));
        assertThat(subject.issuer()).isEqualTo("https://idp.example");
        assertThat(subject.subject()).isEqualTo("alice");
        assertThat(subject.sessionId()).isEqualTo("session-1");
        assertThat(subject.opaqueId()).isNull();
    }

    @Test
    void keycloakAllSessionsMarkerNamesOnlyTheUser() {
        Map<String, Object> subjectId = complex(issSub("https://idp.example", "alice"), opaque("ALL"));
        SsfSubject subject = SsfSubject.from(subjectId);
        assertThat(subject.subject()).isEqualTo("alice");
        assertThat(subject.sessionId()).isNull();
        assertThat(subject.raw()).isEqualTo(subjectId);
    }

    @Test
    void resolvesComplexSubjectWithOpaqueUser() {
        SsfSubject subject = SsfSubject.from(complex(opaque("alice"), opaque("session-1")));
        assertThat(subject.subject()).isEqualTo("alice");
        assertThat(subject.sessionId()).isEqualTo("session-1");
    }

    @Test
    void resolvesComplexSubjectWithoutFormatOfEarlyDrafts() {
        SsfSubject subject = SsfSubject.from(Map.of("session", opaque("session-1")));
        assertThat(subject.sessionId()).isEqualTo("session-1");
        assertThat(subject.hasUser()).isFalse();
    }

    @Test
    void unsupportedFormatIsEmptyButKeepsRawIdentifier() {
        Map<String, Object> phone = Map.of("format", "phone_number", "phone_number", "+12065550100");
        SsfSubject subject = SsfSubject.from(phone);
        assertThat(subject.isEmpty()).isTrue();
        assertThat(subject.raw()).isEqualTo(phone);
    }

    @Test
    void withoutSessionWidensToUser() {
        SsfSubject subject = SsfSubject.from(complex(issSub("https://idp.example", "alice"), opaque("session-1")))
            .withoutSession();
        assertThat(subject.sessionId()).isNull();
        assertThat(subject.subject()).isEqualTo("alice");
    }

}
