package org.easyssf.receiver.set;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.receiver.transmitter.SsfTransmitterUnavailableException;

/**
 * Verifies an encoded Security Event Token (RFC 8417).
 */
public interface SsfSetVerifier {

    /**
     * Verifies the signature and claims of the given SET.
     * @param encodedSet the SET in JWS compact serialization
     * @return the verified token
     * @throws SsfSetVerificationException if the SET is invalid
     * @throws SsfTransmitterUnavailableException if the transmitter's metadata or keys
     * cannot be obtained
     */
    SsfEventToken verify(String encodedSet);

}
