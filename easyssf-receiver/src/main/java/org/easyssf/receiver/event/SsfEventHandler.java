package org.easyssf.receiver.event;

/**
 * Reacts to verified security events. Every handler given to the
 * {@link org.easyssf.receiver.set.SsfSetProcessor} is invoked for every SET.
 *
 * <p>
 * Handlers must be idempotent: a SET is delivered again if any handler failed, and
 * de-duplication does not survive a restart.
 */
@FunctionalInterface
public interface SsfEventHandler {

    /**
     * Handles the given SET. Throwing makes the receiver report a failure to the
     * transmitter, which is then expected to deliver the SET again.
     */
    void handle(SsfEventContext eventContext);

}
