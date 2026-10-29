package org.easyssf.receiver.stream;

import org.easyssf.core.stream.SsfStreamConfiguration;

/**
 * Thrown when the transmitter returned a stream whose issuer is not the issuer the
 * transmitter metadata was obtained from (SSF 1.0, section 8.1.1). Such a stream must not
 * be used: its SETs would not verify.
 */
public class SsfStreamIssuerMismatchException extends SsfStreamException {

    private final transient SsfStreamConfiguration stream;

    public SsfStreamIssuerMismatchException(String expectedIssuer, SsfStreamConfiguration stream) {
        super("The transmitter returned stream " + stream.streamId() + " with the issuer '" + stream.issuer()
                + "' instead of '" + expectedIssuer + "'", 0, null);
        this.stream = stream;
    }

    /**
     * The stream the transmitter returned, for example to delete it if it was created.
     */
    public SsfStreamConfiguration getStream() {
        return this.stream;
    }

}
