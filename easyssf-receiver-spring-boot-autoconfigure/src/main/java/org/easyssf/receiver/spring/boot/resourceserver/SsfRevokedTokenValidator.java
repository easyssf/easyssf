package org.easyssf.receiver.spring.boot.resourceserver;

import org.easyssf.receiver.revocation.SsfTokenRevocationStore;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.Assert;

/**
 * Rejects access tokens that were revoked by a security event: tokens bound to a revoked
 * session ({@code sid} claim) and tokens of a revoked subject ({@code sub} claim) that
 * were issued at or before the revocation.
 *
 * <p>
 * Spring Boot adds this validator to the {@code JwtDecoder} it auto-configures. An
 * application that defines its own {@code JwtDecoder} has to add it to the validators of
 * that decoder.
 */
public class SsfRevokedTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error REVOKED = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The token was revoked",
            "https://tools.ietf.org/html/rfc6750#section-3.1");

    private final SsfTokenRevocationStore revocationStore;

    public SsfRevokedTokenValidator(SsfTokenRevocationStore revocationStore) {
        Assert.notNull(revocationStore, "revocationStore must not be null");
        this.revocationStore = revocationStore;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (this.revocationStore.isRevoked(token.getClaimAsString("sid"), token.getSubject(), token.getIssuedAt())) {
            return OAuth2TokenValidatorResult.failure(REVOKED);
        }
        return OAuth2TokenValidatorResult.success();
    }

}
