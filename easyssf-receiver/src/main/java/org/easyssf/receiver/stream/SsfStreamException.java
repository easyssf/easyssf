package org.easyssf.receiver.stream;

/**
 * Thrown when a call to the stream management API of the transmitter failed.
 */
public class SsfStreamException extends RuntimeException {

    private final int statusCode;

    public SsfStreamException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * The HTTP status the transmitter answered with, {@code 0} if it did not answer.
     */
    public int getStatusCode() {
        return this.statusCode;
    }

}
