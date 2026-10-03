package org.easyssf.receiver.set;

import java.util.LinkedHashMap;
import java.util.Map;

import org.easyssf.core.event.SsfEventToken;

/**
 * {@link SsfJtiDedupStore} that remembers a bounded number of SETs in memory, evicting
 * the oldest first. Its content is lost on restart and not shared between instances.
 */
public class InMemorySsfJtiDedupStore implements SsfJtiDedupStore {

    private final Map<String, Boolean> seen;

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
    public synchronized boolean seenBefore(SsfEventToken eventToken) {
        return this.seen.put(SsfJtiDedupStore.key(eventToken), Boolean.TRUE) != null;
    }

    @Override
    public synchronized void forget(SsfEventToken eventToken) {
        this.seen.remove(SsfJtiDedupStore.key(eventToken));
    }

    public synchronized int size() {
        return this.seen.size();
    }

}
