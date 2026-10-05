package org.easyssf.receiver.poll;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link SsfPollAckStore} that keeps the pending acknowledgements in memory, in the order
 * they were recorded. Lost when the application stops, the default of the poller.
 */
public class InMemorySsfPollAckStore implements SsfPollAckStore {

    private final Map<String, Acks> pending = new ConcurrentHashMap<>();

    @Override
    public void record(String issuer, SsfPendingAck ack) {
        Acks acks = acks(issuer);
        acks.lock.lock();
        try {
            acks.byJti.putIfAbsent(ack.jti(), ack);
        }
        finally {
            acks.lock.unlock();
        }
    }

    @Override
    public List<SsfPendingAck> pending(String issuer, int limit) {
        Acks acks = acks(issuer);
        acks.lock.lock();
        try {
            List<SsfPendingAck> result = new ArrayList<>(Math.min(limit, acks.byJti.size()));
            for (SsfPendingAck ack : acks.byJti.values()) {
                if (result.size() >= limit) {
                    break;
                }
                result.add(ack);
            }
            return result;
        }
        finally {
            acks.lock.unlock();
        }
    }

    @Override
    public void remove(String issuer, Collection<String> jtis) {
        Acks acks = acks(issuer);
        acks.lock.lock();
        try {
            jtis.forEach(acks.byJti::remove);
        }
        finally {
            acks.lock.unlock();
        }
    }

    @Override
    public int size(String issuer) {
        Acks acks = acks(issuer);
        acks.lock.lock();
        try {
            return acks.byJti.size();
        }
        finally {
            acks.lock.unlock();
        }
    }

    private Acks acks(String issuer) {
        return this.pending.computeIfAbsent(String.valueOf(issuer), (key) -> new Acks());
    }

    /**
     * The pending acknowledgements of one transmitter. A lock rather than
     * {@code synchronized}, which pins virtual threads before JDK 24.
     */
    private static final class Acks {

        private final ReentrantLock lock = new ReentrantLock();

        private final Map<String, SsfPendingAck> byJti = new LinkedHashMap<>();

    }

}
