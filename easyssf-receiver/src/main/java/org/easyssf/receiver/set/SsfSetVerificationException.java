package org.easyssf.receiver.set;

/**
 * Thrown when a SET is rejected. Carries the error code reported to the transmitter (RFC
 * 8935, section 2.4).
 */
public class SsfSetVerificationException extends RuntimeException {

    public static final String INVALID_REQUEST = "invalid_request";

    public static final String INVALID_KEY = "invalid_key";

    public static final String INVALID_ISSUER = "invalid_issuer";

    public static final String INVALID_AUDIENCE = "invalid_audience";

    /**
     * The state of a verification event is not the expected one (SSF 1.0, section
     * 8.1.4.1).
     */
    public static final String INVALID_STATE = "invalid_state";

    private final String errorCode;

    public SsfSetVerificationException(String errorCode, String message) {
        this(errorCode, message, null);
    }

    public SsfSetVerificationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return this.errorCode;
    }

}
