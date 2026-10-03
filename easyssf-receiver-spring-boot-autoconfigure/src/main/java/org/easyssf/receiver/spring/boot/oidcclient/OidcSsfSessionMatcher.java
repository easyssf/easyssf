package org.easyssf.receiver.spring.boot.oidcclient;

import java.util.Map;

import org.easyssf.core.event.SsfSubject;
import org.easyssf.receiver.session.SsfSubjectClaimsMatcher;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * {@link SsfSessionMatcher} that compares the subject of an event with the claims of the
 * user that logged in using OpenID Connect: {@code sid} for a session, {@code sub} (and
 * {@code iss}) or {@code email} for a user.
 */
public class OidcSsfSessionMatcher implements SsfSessionMatcher {

    @Override
    public boolean matches(SsfSubject subject, Authentication authentication) {
        return SsfSubjectClaimsMatcher.matches(subject, claims(authentication), authentication.getName());
    }

    private static Map<String, Object> claims(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof OidcUser oidcUser) {
            return oidcUser.getClaims();
        }
        if (principal instanceof OAuth2AuthenticatedPrincipal oauth2Principal) {
            return oauth2Principal.getAttributes();
        }
        return Map.of();
    }

}
