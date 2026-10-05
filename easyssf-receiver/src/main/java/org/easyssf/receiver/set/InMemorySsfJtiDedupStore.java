package org.easyssf.receiver.set;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.easyssf.core.event.SsfEventToken;

/**
 * {@link SsfJtiDedupStore} that remembers a bounded number of SETs in memory, evicting
 * the oldest first. Its content is lost on restart and not shared between instances.
 */
public class InMemorySsfJtiDedupStore implements SsfJtiDedupStore {

    private final Map<String, Boolean> seen;

    /** a lock rather than synchronized, which pins virtual threads before JDK 24 */
    private final ReentrantLock lock = new ReentrantLock();

    public InMemorySsfJtiDedupStore(int capacity) {
        int maxEntries = Math.max(1, capacity);
        this.seen = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > maxEntries;
            }
        };
    }

    @Override
    public boolean seenBefore(SsfEventToken eventToken) {
        this.lock.lock();
        try {
            return this.seen.put(SsfJtiDedupStore.key(eventToken), Boolean.TRUE) != null;
        }
        finally {
            this.lock.unlock();
        }
    }

    @Override
    public void forget(SsfEventToken eventToken) {
        this.lock.lock();
        try {
            this.seen.remove(SsfJtiDedupStore.key(eventToken));
        }
        finally {
            this.lock.unlock();
        }
    }

    public int size() {
        this.lock.lock();
        try {
            return this.seen.size();
        }
        finally {
            this.lock.unlock();
        }
    }

}
