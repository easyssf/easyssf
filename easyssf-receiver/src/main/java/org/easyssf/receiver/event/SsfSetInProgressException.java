package org.easyssf.receiver.event;

/**
 * Thrown when a SET is not handled because another instance of the application is
 * handling it right now (see {@code SsfJtiDedupStore.State#IN_PROGRESS}). Like any
 * {@link SsfEventHandlingException} it leaves the SET unacknowledged, so that the
 * transmitter delivers it again, by which time the other instance has finished with it.
 */
public class SsfSetInProgressException extends SsfEventHandlingException {

    public SsfSetInProgressException(String message) {
        super(message, null);
    }

}
