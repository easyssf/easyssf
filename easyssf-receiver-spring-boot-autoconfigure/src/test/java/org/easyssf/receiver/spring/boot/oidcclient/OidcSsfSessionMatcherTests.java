package org.easyssf.receiver.spring.boot.oidcclient;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.easyssf.core.event.SsfSubject;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.easyssf.core.event.SsfSubjectIdentifiers.complex;
import static org.easyssf.core.event.SsfSubjectIdentifiers.issSub;
import static org.easyssf.core.event.SsfSubjectIdentifiers.opaque;
import static org.easyssf.core.event.SsfSubjectIdentifiers.scim;

class OidcSsfSessionMatcherTests {

    private static final Authentication ALICE = login(Map.of("iss", "https://idp.example", "sub", "alice-id", "sid",
            "session-1", "email", "alice@example.com", "preferred_username", "alice"));

    @Test
    void matchesSessionAndUserOfTheIdToken() {
        OidcSsfSessionMatcher matcher = new OidcSsfSessionMatcher();
        assertThat(matcher
            .matches(SsfSubject.from(complex(issSub("https://idp.example", "alice-id"), opaque("session-1"))), ALICE))
            .isTrue();
        assertThat(matcher.matches(SsfSubject.from(issSub("https://idp.example", "bob-id")), ALICE)).isFalse();
    }

    @Test
    void matchesScimResourceByTheDefaultAttributeClaims() {
        OidcSsfSessionMatcher matcher = new OidcSsfSessionMatcher();
        assertThat(matcher.matches(SsfSubject.from(scim("/Users/alice-id")), ALICE)).isTrue();
        assertThat(matcher.matches(SsfSubject.from(scim("/Users/2b2f880a", "alice-id")), ALICE)).isTrue();
        Map<String, Object> byUserName = new HashMap<>(scim("/Users/2b2f880a"));
        byUserName.put("userName", "alice");
        assertThat(matcher.matches(SsfSubject.from(byUserName), ALICE)).isTrue();
        assertThat(matcher.matches(SsfSubject.from(scim("/Users/2b2f880a", "alice@example.com")), ALICE)).isFalse();
    }

    @Test
    void matchesScimResourceByConfiguredAttributeClaims() {
        OidcSsfSessionMatcher matcher = new OidcSsfSessionMatcher(Map.of("externalId", "email"));
        assertThat(matcher.matches(SsfSubject.from(scim("/Users/2b2f880a", "alice@example.com")), ALICE)).isTrue();
        assertThat(matcher.matches(SsfSubject.from(scim("/Users/alice-id")), ALICE)).isFalse();
    }

    private static Authentication login(Map<String, Object> claims) {
        OidcIdToken idToken = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(60), claims);
        DefaultOidcUser user = new DefaultOidcUser(AuthorityUtils.createAuthorityList("ROLE_USER"), idToken);
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "idp");
    }

}
