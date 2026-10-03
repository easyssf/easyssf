package org.easyssf.receiver.session;

import java.util.List;
import java.util.Map;

import org.easyssf.core.event.SsfSubject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easyssf.core.event.SsfSubjectIdentifiers.aliases;
import static org.easyssf.core.event.SsfSubjectIdentifiers.complex;
import static org.easyssf.core.event.SsfSubjectIdentifiers.did;
import static org.easyssf.core.event.SsfSubjectIdentifiers.email;
import static org.easyssf.core.event.SsfSubjectIdentifiers.issSub;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;
import static org.easyssf.core.event.SsfSubjectIdentifiers.phoneNumber;

class SsfSubjectClaimsMatcherTests {

    private static final Map<String, Object> CLAIMS = Map.of("iss", "https://idp.example", "sub", "alice", "sid",
            "session-1", "email", "alice@example.com");

    @Test
    void sessionSubjectMatchesThatSessionOnly() {
        assertThat(matches(complex(issSub("https://idp.example", "alice"), opaque("session-1")))).isTrue();
        assertThat(matches(complex(issSub("https://idp.example", "alice"), opaque("session-2")))).isFalse();
    }

    @Test
    void sessionSubjectMatchesOnlyAtTheIssuerOfItsUser() {
        assertThat(matches(complex(issSub("https://other.example", "alice"), opaque("session-1")))).isFalse();
        // no issuer named: the session identifier alone decides
        assertThat(matches(complex(opaque("alice"), opaque("session-1")))).isTrue();
        assertThat(matches(Map.of("session", opaque("session-1")))).isTrue();
        assertThat(SsfSubjectClaimsMatcher.matches(
                SsfSubject.from(complex(issSub("https://idp.example", "alice"), opaque("session-1"))),
                Map.of("sid", "session-1"), null))
            .isFalse();
    }

    @Test
    void userSubjectMatchesSubjectOfThatIssuer() {
        assertThat(matches(issSub("https://idp.example", "alice"))).isTrue();
        assertThat(matches(issSub("https://idp.example", "bob"))).isFalse();
        assertThat(matches(issSub("https://other.example", "alice"))).isFalse();
    }

    @Test
    void emailSubjectMatchesIgnoringCase() {
        assertThat(matches(email("ALICE@example.com"))).isTrue();
        assertThat(matches(email("bob@example.com"))).isFalse();
    }

    @Test
    void opaqueSubjectMatchesSessionOrUser() {
        assertThat(matches(opaque("session-1"))).isTrue();
        assertThat(matches(opaque("alice"))).isTrue();
        assertThat(matches(opaque("other"))).isFalse();
    }

    @Test
    void nameIsUsedWhenClaimsHaveNoSubject() {
        SsfSubject subject = SsfSubject.from(issSub("https://idp.example", "alice"));
        Map<String, Object> issuerOnly = Map.of("iss", "https://idp.example");
        assertThat(SsfSubjectClaimsMatcher.matches(subject, issuerOnly, "alice")).isTrue();
        assertThat(SsfSubjectClaimsMatcher.matches(subject, issuerOnly, "bob")).isFalse();
        assertThat(SsfSubjectClaimsMatcher.matches(SsfSubject.empty(), CLAIMS, "alice")).isFalse();
    }

    @Test
    void issSubRequiresTheIssuerOfTheClaims() {
        SsfSubject subject = SsfSubject.from(issSub("https://idp.example", "alice"));
        assertThat(SsfSubjectClaimsMatcher.matches(subject, Map.of("sub", "alice"), null)).isFalse();
        assertThat(SsfSubjectClaimsMatcher.matches(subject, Map.of(), "alice")).isFalse();
        assertThat(
                SsfSubjectClaimsMatcher.matches(subject, Map.of("iss", "https://other.example", "sub", "alice"), null))
            .isFalse();
    }

    @Test
    void phoneNumberAndOtherFormatsMatchTheClaimOfTheSameName() {
        assertThat(SsfSubjectClaimsMatcher.matches(SsfSubject.from(phoneNumber("+12065550100")),
                Map.of("phone_number", "+12065550100"), null))
            .isTrue();
        assertThat(SsfSubjectClaimsMatcher.matches(SsfSubject.from(phoneNumber("+12065550100")),
                Map.of("phone_number", "+12065550199"), null))
            .isFalse();
        assertThat(SsfSubjectClaimsMatcher.matches(SsfSubject.from(did("did:example:123")),
                Map.of("did", "did:example:123"), null))
            .isTrue();
        assertThat(SsfSubjectClaimsMatcher.matches(SsfSubject.from(did("did:example:123")), CLAIMS, null)).isFalse();
    }

    @Test
    void aliasesMatchIfAnyIdentifierDoes() {
        assertThat(matches(aliases(List.of(issSub("https://other.example", "alice"), email("alice@example.com")))))
            .isTrue();
        assertThat(matches(aliases(List.of(issSub("https://other.example", "alice"), email("bob@example.com")))))
            .isFalse();
    }

    @Test
    void complexSubjectWithOtherMembersOnlyDoesNotMatch() {
        assertThat(matches(complex(Map.of("device", opaque("device-1"))))).isFalse();
    }

    private static boolean matches(Map<String, Object> subjectId) {
        return SsfSubjectClaimsMatcher.matches(SsfSubject.from(subjectId), CLAIMS, null);
    }

}
