package org.easyssf.receiver.set;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.support.SsfAssert;

/**
 * Verifies SETs of several transmitters: the {@code iss} claim of a SET selects the
 * {@link SsfSetVerifier} of that transmitter, which then verifies the SET, including its
 * issuer. A SET of an unknown issuer is rejected.
 */
public class IssuerRoutingSsfSetVerifier implements SsfSetVerifier {

    private final Map<String, SsfSetVerifier> verifiers;

    /**
     * @param verifiers the verifier of each transmitter by its issuer
     */
    public IssuerRoutingSsfSetVerifier(Map<String, SsfSetVerifier> verifiers) {
        SsfAssert.isTrue(verifiers != null && !verifiers.isEmpty(), "verifiers must not be empty");
        this.verifiers = Collections.unmodifiableMap(new LinkedHashMap<>(verifiers));
    }

    public Map<String, SsfSetVerifier> getVerifiers() {
        return this.verifiers;
    }

    @Override
    public SsfEventToken verify(String encodedSet) {
        String issuer = SsfSetClaims.unverifiedIssuer(encodedSet);
        if (issuer == null) {
            throw new SsfSetVerificationException(SsfSetVerificationException.INVALID_REQUEST,
                    "The SET cannot be parsed or has no issuer");
        }
        SsfSetVerifier verifier = this.verifiers.get(issuer);
        if (verifier == null) {
            throw new SsfSetVerificationException(SsfSetVerificationException.INVALID_ISSUER,
                    "The SET was issued by '" + issuer + "', which is not a transmitter of this receiver");
        }
        return verifier.verify(encodedSet);
    }

}
