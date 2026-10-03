package org.easyssf.examples.oidcclient;

import java.time.Instant;
import java.util.Map;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the page find out whether its local session still exists. The check does not call
 * Keycloak: the receiver invalidates the local session when Keycloak reports the end of
 * the Keycloak session, so the request either arrives in the session (200) or without one
 * (401, see {@link SecurityConfiguration}).
 */
@RestController
class AuthController {

    @GetMapping("/auth/check")
    ResponseEntity<Map<String, Object>> check(@AuthenticationPrincipal OidcUser user, HttpSession session) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(Map.of("user", user.getPreferredUsername(), "sid", user.getClaimAsString("sid"), "localSession",
                    session.getId(), "checkedAt", Instant.now().toString()));
    }

}
