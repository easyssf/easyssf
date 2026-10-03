package org.easyssf.receiver.set;

import org.easyssf.core.event.SsfEventToken;

/**
 * Remembers which SETs were already processed, so that a SET delivered more than once is
 * handled only once. Implement it to back it with a shared or durable store.
 */
public interface SsfJtiDedupStore {

    /**
     * Atomically checks whether the given SET was seen before and records it.
     * @return {@code true} if the SET was already recorded
     */
    boolean seenBefore(SsfEventToken eventToken);

    /**
     * Forgets the given SET again, because handling it failed and it is expected to be
     * delivered once more.
     */
    void forget(SsfEventToken eventToken);

    /**
     * The key identifying a SET. A {@code jti} is only unique per issuer (RFC 8417,
     * section 2.2).
     */
    static String key(SsfEventToken eventToken) {
        return eventToken.iss() + "::" + eventToken.jti();
    }

}
