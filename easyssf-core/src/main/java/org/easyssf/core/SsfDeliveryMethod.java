package org.easyssf.core;

/**
 * How SETs get from the transmitter to the receiver.
 */
public enum SsfDeliveryMethod {

    /**
     * The transmitter sends SETs to an endpoint of the receiver (RFC 8935).
     */
    PUSH("urn:ietf:rfc:8935"),

    /**
     * The receiver fetches SETs from an endpoint of the transmitter (RFC 8936).
     */
    POLL("urn:ietf:rfc:8936");

    private final String uri;

    SsfDeliveryMethod(String uri) {
        this.uri = uri;
    }

    /**
     * The identifier of the delivery method in a stream configuration.
     */
    public String uri() {
        return this.uri;
    }

}
