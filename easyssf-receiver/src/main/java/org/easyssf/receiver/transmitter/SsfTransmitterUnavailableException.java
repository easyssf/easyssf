package org.easyssf.receiver.transmitter;

/**
 * Thrown when the transmitter's metadata or keys cannot be obtained. Unlike an invalid
 * SET this is a temporary condition, the SET should be delivered again later.
 */
public class SsfTransmitterUnavailableException extends RuntimeException {

    public SsfTransmitterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

}
