package org.easyssf.core.event;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.easyssf.core.event.SsfSubjectIdentifiers.account;
import static org.easyssf.core.event.SsfSubjectIdentifiers.aliases;
import static org.easyssf.core.event.SsfSubjectIdentifiers.complex;
import static org.easyssf.core.event.SsfSubjectIdentifiers.did;
import static org.easyssf.core.event.SsfSubjectIdentifiers.email;
import static org.easyssf.core.event.SsfSubjectIdentifiers.issSub;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;
import static org.easyssf.core.event.SsfSubjectIdentifiers.phoneNumber;
import static org.easyssf.core.event.SsfSubjectIdentifiers.scim;
import static org.easyssf.core.event.SsfSubjectIdentifiers.uri;

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
    void otherFormatsOfRfc9493NameTheUser() {
        SsfSubject phone = SsfSubject.from(phoneNumber("+12065550100"));
        assertThat(phone.isEmpty()).isFalse();
        assertThat(phone.userIdentifier().format()).isEqualTo(SsfSubjectIdentifier.PHONE_NUMBER);
        assertThat(phone.userIdentifier().value()).isEqualTo("+12065550100");
        assertThat(phone.subject()).isNull();
        assertThat(phone.raw()).isEqualTo(phoneNumber("+12065550100"));
        assertThat(SsfSubject.from(account("acct:alice@example.com")).userIdentifier().value())
            .isEqualTo("acct:alice@example.com");
        assertThat(SsfSubject.from(did("did:example:123")).userIdentifier().value()).isEqualTo("did:example:123");
        assertThat(SsfSubject.from(uri("https://example.com/alice")).userIdentifier().value())
            .isEqualTo("https://example.com/alice");
    }

    @Test
    void scimResourceOfRfc9967NamesTheUserByItsUri() {
        SsfSubject subject = SsfSubject.from(scim("/Users/2b2f880a", "jdoe"));
        assertThat(subject.isEmpty()).isFalse();
        assertThat(subject.hasUser()).isTrue();
        assertThat(subject.userIdentifier().isScim()).isTrue();
        assertThat(subject.userIdentifier().value()).isEqualTo("/Users/2b2f880a");
        assertThat(subject.userIdentifier().claim("externalId")).isEqualTo("jdoe");
        assertThat(subject.subject()).isNull();
        assertThat(scim("/Users/2b2f880a")).doesNotContainKey("externalId");
        assertThat(scim("/Users/2b2f880a", null)).doesNotContainKey("externalId");
    }

    @Test
    void aliasesCarryEveryIdentifier() {
        SsfSubject subject = SsfSubject
            .from(aliases(List.of(issSub("https://idp.example", "alice"), email("alice@example.com"))));
        assertThat(subject.userIdentifier().is(SsfSubjectIdentifier.ALIASES)).isTrue();
        assertThat(subject.userIdentifier().value()).isNull();
        assertThat(subject.userIdentifier().aliases()).extracting(SsfSubjectIdentifier::value)
            .containsExactly("alice", "alice@example.com");
        assertThat(subject.subject()).isNull();
    }

    @Test
    void unknownFormatIsKeptWithoutValue() {
        Map<String, Object> custom = Map.of("format", "x-badge", "badge", "4711");
        SsfSubject subject = SsfSubject.from(custom);
        assertThat(subject.isEmpty()).isFalse();
        assertThat(subject.userIdentifier().format()).isEqualTo("x-badge");
        assertThat(subject.userIdentifier().value()).isNull();
        assertThat(subject.userIdentifier().claim("badge")).isEqualTo("4711");
        assertThat(subject.raw()).isEqualTo(custom);
    }

    @Test
    void identifierWithoutFormatIsEmpty() {
        SsfSubject subject = SsfSubject.from(Map.of("sub", "alice"));
        assertThat(subject.isEmpty()).isTrue();
        assertThat(subject.raw()).containsEntry("sub", "alice");
    }

    @Test
    void complexSubjectExposesAllMembers() {
        SsfSubject subject = SsfSubject.from(complex(Map.of("user", issSub("https://idp.example", "alice"), "device",
                opaque("device-1"), "tenant", uri("https://tenant.example"))));
        assertThat(subject.isComplex()).isTrue();
        assertThat(subject.user().value()).isEqualTo("alice");
        assertThat(subject.device().value()).isEqualTo("device-1");
        assertThat(subject.tenant().value()).isEqualTo("https://tenant.example");
        assertThat(subject.member("tenant")).isSameAs(subject.tenant());
        assertThat(subject.session()).isNull();
        assertThat(subject.members()).containsOnlyKeys("user", "device", "tenant");
    }

    @Test
    void subjectIsImmutable() {
        Map<String, Object> subjectId = new HashMap<>(complex(issSub("https://idp.example", "alice"), opaque("s-1")));
        SsfSubject subject = SsfSubject.from(subjectId);
        subjectId.clear();
        assertThat(subject.subject()).isEqualTo("alice");
        assertThat(subject.raw()).isNotEmpty();
        assertThatThrownBy(() -> subject.raw().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> subject.members().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void withoutSessionWidensToUser() {
        SsfSubject subject = SsfSubject.from(complex(issSub("https://idp.example", "alice"), opaque("session-1")))
            .withoutSession();
        assertThat(subject.sessionId()).isNull();
        assertThat(subject.subject()).isEqualTo("alice");
    }

}
