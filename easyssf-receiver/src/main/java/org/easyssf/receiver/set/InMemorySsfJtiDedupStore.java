package org.easyssf.receiver.set;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.support.SsfAssert;

/**
 * {@link SsfJtiDedupStore} that remembers a bounded number of SETs in memory, evicting
 * the oldest first. Its content is lost on restart and not shared between instances, so
 * the {@link Claim#IN_PROGRESS in progress} state only matters when the same SET arrives
 * twice while a handler of this instance still runs.
 */
public class InMemorySsfJtiDedupStore implements SsfJtiDedupStore {

    private final Map<String, Known> entries;

    /** a lock rather than synchronized, which pins virtual threads before JDK 24 */
    private final ReentrantLock lock = new ReentrantLock();

    private Duration lease = Duration.ofSeconds(60);

    private Clock clock = Clock.systemUTC();

    private final AtomicLong nextToken = new AtomicLong();

    public InMemorySsfJtiDedupStore(int capacity) {
        int maxEntries = Math.max(1, capacity);
        this.entries = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Known> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /**
     * Sets how long a claim holds before the SET counts as abandoned by a crashed or
     * stuck handler and is handled again; 60 seconds by default, longer than the longest
     * handler.
     */
    public void setLease(Duration lease) {
        SsfAssert.isTrue(lease != null && lease.isPositive(), "lease must be positive");
        this.lease = lease;
    }

    public void setClock(Clock clock) {
        SsfAssert.notNull(clock, "clock must not be null");
        this.clock = clock;
    }

    @Override
    public Claim claim(SsfEventToken eventToken) {
        String key = SsfJtiDedupStore.key(eventToken);
        Instant now = this.clock.instant();
        this.lock.lock();
        try {
            Known entry = this.entries.get(key);
            if (entry == null) {
                return grant(key, now);
            }
            if (entry.state == State.PROCESSED) {
                return Claim.processed();
            }
            if (!entry.since.plus(this.lease).isAfter(now)) {
                // abandoned: taken over, with a token of its own
                return grant(key, now);
            }
            return Claim.inProgress();
        }
        finally {
            this.lock.unlock();
        }
    }

    private Claim grant(String key, Instant now) {
        long token = this.nextToken.incrementAndGet();
        this.entries.put(key, new Known(State.IN_PROGRESS, now, token));
        return Claim.granted(token);
    }

    @Override
    public void processed(SsfEventToken eventToken, Claim claim) {
        SsfAssert.notNull(claim, "claim must not be null");
        String key = SsfJtiDedupStore.key(eventToken);
        this.lock.lock();
        try {
            Known entry = this.entries.get(key);
            // the claim may have been evicted meanwhile: record the success anyway
            if (entry == null || holds(entry, claim)) {
                this.entries.put(key, new Known(State.PROCESSED, this.clock.instant(), claim.token()));
            }
        }
        finally {
            this.lock.unlock();
        }
    }

    @Override
    public void forget(SsfEventToken eventToken, Claim claim) {
        SsfAssert.notNull(claim, "claim must not be null");
        String key = SsfJtiDedupStore.key(eventToken);
        this.lock.lock();
        try {
            Known entry = this.entries.get(key);
            if (entry != null && holds(entry, claim)) {
                this.entries.remove(key);
            }
        }
        finally {
            this.lock.unlock();
        }
    }

    /**
     * Whether the entry is the claim given: in progress and not taken over since.
     */
    private static boolean holds(Known entry, Claim claim) {
        return entry.state == State.IN_PROGRESS && entry.token == claim.token();
    }

    public int size() {
        this.lock.lock();
        try {
            return this.entries.size();
        }
        finally {
            this.lock.unlock();
        }
    }

    private record Known(State state, Instant since, long token) {
    }

}
