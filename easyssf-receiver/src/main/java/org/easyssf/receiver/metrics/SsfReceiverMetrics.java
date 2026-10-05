package org.easyssf.receiver.metrics;

import java.time.Duration;
import java.util.function.IntSupplier;

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
         * The SET is being handled by another instance and has been left for a
         * redelivery.
         */
        IN_PROGRESS,

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

    /**
     * A SET was received from a transmitter.
     * @param transmitter the issuer of the transmitter, {@code null} if the SET did not
     * tell
     */
    default void setReceived(String transmitter, SsfDeliveryMethod deliveryMethod, SetOutcome outcome) {
        setReceived(deliveryMethod, outcome);
    }

    /**
     * An event of a SET of a transmitter was handled.
     * @param transmitter the issuer of the transmitter
     * @param eventType the event type URI
     */
    default void eventHandled(String transmitter, String eventType, SsfDeliveryMethod deliveryMethod) {
        eventHandled(eventType, deliveryMethod);
    }

    /**
     * A transmitter was polled for SETs.
     * @param transmitter the issuer of the transmitter, {@code null} if unknown
     * @param success whether the transmitter answered the poll request
     */
    default void pollCompleted(String transmitter, Duration duration, boolean success) {
        pollCompleted(duration, success);
    }

    /**
     * A poller started and tells how to read the number of acknowledgements and error
     * reports it still owes its transmitter, for a gauge.
     * @param transmitter the issuer of the transmitter, {@code null} if unknown
     * @param pendingAcks reads the current number
     */
    default void pollerStarted(String transmitter, IntSupplier pendingAcks) {
    }

}
