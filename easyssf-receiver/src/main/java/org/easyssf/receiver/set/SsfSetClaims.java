package org.easyssf.receiver.set;

import java.text.ParseException;

import com.nimbusds.jwt.JWTParser;

/**
 * Reads claims of a SET before it is verified, to decide how to verify it. Nothing read
 * this way is trusted.
 */
public final class SsfSetClaims {

    private SsfSetClaims() {
    }

    /**
     * @param encodedSet the SET as received
     * @return the {@code iss} claim, {@code null} if the SET cannot be parsed or has none
     */
    public static String unverifiedIssuer(String encodedSet) {
        if (encodedSet == null || encodedSet.isBlank()) {
            return null;
        }
        try {
            return JWTParser.parse(encodedSet).getJWTClaimsSet().getIssuer();
        }
        catch (ParseException | RuntimeException ex) {
            return null;
        }
    }

}
