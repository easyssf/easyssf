package org.easyssf.receiver.set;

import org.easyssf.core.event.SsfEventToken;

/**
 * Remembers which SETs were processed, so that a redelivery of a handled SET is skipped
 * and a SET another instance is handling is left alone. It does not make handling exactly
 * once: an instance that dies after a handler ran and before {@link #processed} hands the
 * SET to the next instance after the lease, which is why handlers are idempotent.
 * Implement it to back it with a shared or durable store.
 *
 * <p>
 * A SET is {@link #claim claimed} before its handlers run and marked {@link #processed
 * processed} once they succeeded. In between it is <em>in progress</em>: another instance
 * of the application that receives the same SET then neither handles nor acknowledges it,
 * and leaves it to the transmitter's redelivery. An instance that crashes mid-processing
 * leaves the claim behind, so a claim older than the store's lease counts as abandoned
 * and the next instance takes the SET over; handlers have to be idempotent for that
 * reason as well.
 */
public interface SsfJtiDedupStore {

    /**
     * What a store knows about a SET when it is claimed.
     */
    enum Claim {

        /**
         * Not seen before, or abandoned by a crashed instance: the caller handles the
         * SET.
         */
        NEW,

        /**
         * Another instance is handling the SET right now: neither handle nor acknowledge
         * it.
         */
        IN_PROGRESS,

        /**
         * The SET was handled before: acknowledge it, do not handle it again.
         */
        PROCESSED

    }

    /**
     * Atomically records that the caller is about to handle the SET, unless it was
     * handled before or is being handled by another instance.
     */
    Claim claim(SsfEventToken eventToken);

    /**
     * Records that the handlers of a claimed SET succeeded.
     */
    void processed(SsfEventToken eventToken);

    /**
     * Forgets the given SET again, because handling it failed and it is expected to be
     * delivered once more.
     */
    void forget(SsfEventToken eventToken);

    /**
     * Claims the SET and tells whether it was known before, as processed or in progress.
     * @deprecated use {@link #claim(SsfEventToken)}, which distinguishes the two
     */
    @Deprecated(since = "0.3.0")
    default boolean seenBefore(SsfEventToken eventToken) {
        return claim(eventToken) != Claim.NEW;
    }

    /**
     * The key identifying a SET, the same one handlers use for their side effects.
     * @see SsfEventToken#idempotencyKey()
     */
    static String key(SsfEventToken eventToken) {
        return eventToken.idempotencyKey();
    }

}
