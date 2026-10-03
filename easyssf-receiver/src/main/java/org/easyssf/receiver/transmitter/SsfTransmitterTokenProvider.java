package org.easyssf.receiver.transmitter;

/**
 * Provides the access token the receiver authenticates with when it calls the stream
 * management and poll endpoints of the transmitter. Implement it to obtain the token in
 * another way than the built-in ones.
 */
@FunctionalInterface
public interface SsfTransmitterTokenProvider {

    /**
     * @return the access token to send as bearer token, or {@code null} to call the
     * transmitter without authentication
     */
    String getAccessToken();

    /**
     * Called when the transmitter rejected the access token (HTTP 401), so that the next
     * call of {@link #getAccessToken()} returns a new one. Does nothing by default.
     */
    default void invalidate() {
    }

}
