package org.easyssf.receiver.set;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.support.SsfAssert;

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
 *
 * <p>
 * A granted claim carries a {@link Claim#token() token} that fences it:
 * {@link #processed} and {@link #forget} only act on the claim with that token. An
 * instance whose handlers outlived the lease therefore neither completes nor deletes the
 * claim of the instance that took the SET over; the SET belongs to the new holder.
 */
public interface SsfJtiDedupStore {

    /**
     * What a store knows about a SET when it is claimed.
     */
    enum State {

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
     * The outcome of {@link #claim}: the {@link State} of the SET and, for a
     * {@link State#NEW new} claim, the token that identifies this claim against a later
     * take-over of the same SET.
     *
     * @param state what the store knew about the SET
     * @param token the token of the claim granted, meaningless unless the state is
     * {@link State#NEW}; a value the store chooses, for example the time of the claim
     */
    record Claim(State state, long token) {

        private static final Claim IN_PROGRESS = new Claim(State.IN_PROGRESS, 0);

        private static final Claim PROCESSED = new Claim(State.PROCESSED, 0);

        public Claim {
            SsfAssert.notNull(state, "state must not be null");
        }

        /**
         * @return a claim granted to the caller, who handles the SET
         */
        public static Claim granted(long token) {
            return new Claim(State.NEW, token);
        }

        /**
         * @return the SET is being handled by another instance
         */
        public static Claim inProgress() {
            return IN_PROGRESS;
        }

        /**
         * @return the SET was handled before
         */
        public static Claim processed() {
            return PROCESSED;
        }

        /**
         * @return whether the claim was granted: the caller handles the SET
         */
        public boolean isNew() {
            return this.state == State.NEW;
        }

    }

    /**
     * Atomically records that the caller is about to handle the SET, unless it was
     * handled before or is being handled by another instance.
     * @return the state of the SET and, if it is new, the token of the claim
     */
    Claim claim(SsfEventToken eventToken);

    /**
     * Records that the handlers of a claimed SET succeeded. Does nothing if the claim was
     * taken over meanwhile, because the handlers outlived the lease: the SET then belongs
     * to the instance that took it over.
     * @param claim the claim {@link #claim} granted for the SET
     */
    void processed(SsfEventToken eventToken, Claim claim);

    /**
     * Forgets the given SET again, because handling it failed and it is expected to be
     * delivered once more. Does nothing if the claim was taken over meanwhile.
     * @param claim the claim {@link #claim} granted for the SET
     */
    void forget(SsfEventToken eventToken, Claim claim);

    /**
     * Claims the SET and tells whether it was known before, as processed or in progress.
     * @deprecated use {@link #claim(SsfEventToken)}, which distinguishes the two
     */
    @Deprecated(since = "0.3.0")
    default boolean seenBefore(SsfEventToken eventToken) {
        return !claim(eventToken).isNew();
    }

    /**
     * The key identifying a SET, the same one handlers use for their side effects.
     * @see SsfEventToken#idempotencyKey()
     */
    static String key(SsfEventToken eventToken) {
        return eventToken.idempotencyKey();
    }

}
