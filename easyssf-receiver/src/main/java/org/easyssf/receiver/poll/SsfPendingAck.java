package org.easyssf.receiver.poll;

import org.easyssf.core.support.SsfAssert;

/**
 * What the poller owes the transmitter for a SET it fetched (RFC 8936, section 2.2): an
 * acknowledgement that the SET was received and handled, or an error that it was
 * rejected, which goes into the {@code setErrs} member of the next poll request.
 *
 * @param jti the identifier of the SET
 * @param errorCode the error code of a rejected SET ({@code invalid_request}, ...),
 * {@code null} for an acknowledgement
 * @param errorDescription a description of the error, may be {@code null}
 */
public record SsfPendingAck(String jti, String errorCode, String errorDescription) {

    public SsfPendingAck {
        SsfAssert.notNull(jti, "jti must not be null");
        SsfAssert.isTrue(errorCode != null || errorDescription == null, "an acknowledgement has no error description");
    }

    /**
     * The acknowledgement of a handled SET.
     */
    public static SsfPendingAck ack(String jti) {
        return new SsfPendingAck(jti, null, null);
    }

    /**
     * The report of a rejected SET.
     */
    public static SsfPendingAck error(String jti, String errorCode, String errorDescription) {
        SsfAssert.notNull(errorCode, "errorCode must not be null");
        return new SsfPendingAck(jti, errorCode, errorDescription);
    }

    /**
     * Whether this reports an error rather than acknowledging the SET.
     */
    public boolean isError() {
        return this.errorCode != null;
    }

}
