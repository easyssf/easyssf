package org.easyssf.receiver.event;

/**
 * Reacts to verified security events. Every handler given to the
 * {@link org.easyssf.receiver.set.SsfSetProcessor} is invoked for every SET.
 *
 * <p>
 * A SET is handled at least once, not exactly once, so handlers must be idempotent. A SET
 * is delivered again if any handler failed, and all handlers run again, possibly on
 * another instance of the application; with a shared dedup store a SET is claimed while
 * its handlers run and another instance leaves it alone meanwhile, but an instance that
 * dies after a handler's side effects and before the success is recorded hands the SET to
 * the next instance after a lease. Side effects outside the receiver's stores should be
 * keyed by {@link SsfEventContext#idempotencyKey()}.
 */
@FunctionalInterface
public interface SsfEventHandler {

    /**
     * Handles the given SET. Throwing makes the receiver report a failure to the
     * transmitter, which is then expected to deliver the SET again.
     */
    void handle(SsfEventContext eventContext);

}
