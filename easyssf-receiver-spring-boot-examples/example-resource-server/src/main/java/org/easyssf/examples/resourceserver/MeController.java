package org.easyssf.examples.resourceserver;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController {

    private static final Logger logger = LoggerFactory.getLogger(MeController.class);

    @GetMapping("/api/me")
    Map<String, Object> me(@AuthenticationPrincipal Jwt accessToken) {
        logger.info("GET /api/me for user " + accessToken.getClaimAsString("preferred_username") + " of session "
                + accessToken.getClaimAsString("sid") + ", token issued at " + accessToken.getIssuedAt());
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("username", accessToken.getClaimAsString("preferred_username"));
        me.put("subject", accessToken.getSubject());
        me.put("sessionId", accessToken.getClaimAsString("sid"));
        me.put("tokenIssuedAt", String.valueOf(accessToken.getIssuedAt()));
        me.put("tokenExpiresAt", String.valueOf(accessToken.getExpiresAt()));
        return me;
    }

}
