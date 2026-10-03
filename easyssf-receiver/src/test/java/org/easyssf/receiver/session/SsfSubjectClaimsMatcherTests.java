package org.easyssf.receiver.session;

import java.util.Map;

import org.easyssf.core.event.SsfSubject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easyssf.core.event.SsfSubjectIdentifiers.complex;
import static org.easyssf.core.event.SsfSubjectIdentifiers.email;
import static org.easyssf.core.event.SsfSubjectIdentifiers.issSub;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;

class SsfSubjectClaimsMatcherTests {

    private static final Map<String, Object> CLAIMS = Map.of("iss", "https://idp.example", "sub", "alice", "sid",
            "session-1", "email", "alice@example.com");

    @Test
    void sessionSubjectMatchesThatSessionOnly() {
        assertThat(matches(complex(issSub("https://idp.example", "alice"), opaque("session-1")))).isTrue();
        assertThat(matches(complex(issSub("https://idp.example", "alice"), opaque("session-2")))).isFalse();
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
        assertThat(SsfSubjectClaimsMatcher.matches(subject, Map.of(), "alice")).isTrue();
        assertThat(SsfSubjectClaimsMatcher.matches(subject, Map.of(), "bob")).isFalse();
        assertThat(SsfSubjectClaimsMatcher.matches(SsfSubject.empty(), CLAIMS, "alice")).isFalse();
    }

    private static boolean matches(Map<String, Object> subjectId) {
        return SsfSubjectClaimsMatcher.matches(SsfSubject.from(subjectId), CLAIMS, null);
    }

}
