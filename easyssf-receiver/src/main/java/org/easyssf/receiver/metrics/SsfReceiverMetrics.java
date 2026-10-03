package org.easyssf.receiver.metrics;

import java.time.Duration;

import org.easyssf.core.SsfDeliveryMethod;

/**
 * Records what the receiver does. The default implementation records nothing, see
 * {@link MicrometerSsfReceiverMetrics}.
 */
public interface SsfReceiverMetrics {

    /**
     * Records nothing.
     */
    SsfReceiverMetrics NOOP = new SsfReceiverMetrics() {
    };

    /**
     * What happened to a received SET.
     */
    enum SetOutcome {

        /**
         * The SET was verified and handled.
         */
        HANDLED,

        /**
         * The SET was processed before and has been skipped.
         */
        DUPLICATE,

        /**
         * The SET was rejected because it is invalid.
         */
        INVALID,

        /**
         * The SET was rejected because the transmitter did not authenticate.
         */
        UNAUTHENTICATED,

        /**
         * The SET could not be verified because the keys of the transmitter could not be
         * obtained.
         */
        UNAVAILABLE,

        /**
         * An event handler failed.
         */
        FAILED

    }

    /**
     * A SET was received.
     */
    default void setReceived(SsfDeliveryMethod deliveryMethod, SetOutcome outcome) {
    }

    /**
     * An event of a SET was handled.
     * @param eventType the event type URI
     */
    default void eventHandled(String eventType, SsfDeliveryMethod deliveryMethod) {
    }

    /**
     * The transmitter was polled for SETs.
     * @param success whether the transmitter answered the poll request
     */
    default void pollCompleted(Duration duration, boolean success) {
    }

}
