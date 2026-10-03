package org.easyssf.receiver.jdbc;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.easyssf.core.support.SsfAssert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Purges the expired rows of the {@link JdbcSsfExpiringStore stores} periodically, so
 * that they do not linger in a receiver that gets no events for a while. Either call
 * {@link #purgeExpired()} from the scheduler of the application, or {@link #start()} to
 * run it on a daemon thread of its own.
 */
public class JdbcSsfStoreCleanup {

    private static final Logger logger = LoggerFactory.getLogger(JdbcSsfStoreCleanup.class);

    private final List<JdbcSsfExpiringStore> stores;

    private final Duration interval;

    private ScheduledExecutorService executor;

    /**
     * @param stores the stores to purge
     * @param interval how often; the periodic cleanup is off when zero or negative
     */
    public JdbcSsfStoreCleanup(List<JdbcSsfExpiringStore> stores, Duration interval) {
        SsfAssert.notNull(stores, "stores must not be null");
        SsfAssert.notNull(interval, "interval must not be null");
        this.stores = List.copyOf(stores);
        this.interval = interval;
    }

    public boolean isEnabled() {
        return this.interval.isPositive() && !this.stores.isEmpty();
    }

    /**
     * Purges the expired rows of all stores once.
     * @return the number of rows deleted
     */
    public int purgeExpired() {
        int deleted = 0;
        for (JdbcSsfExpiringStore store : this.stores) {
            try {
                deleted += store.purgeExpired();
            }
            catch (RuntimeException ex) {
                logger.warn("Could not purge the expired rows of " + store.getClass().getSimpleName(), ex);
            }
        }
        if (deleted > 0 && logger.isDebugEnabled()) {
            logger.debug("Purged " + deleted + " expired rows");
        }
        return deleted;
    }

    /**
     * Starts purging periodically on a daemon thread, if enabled.
     */
    public synchronized void start() {
        if (this.executor != null || !isEnabled()) {
            return;
        }
        this.executor = Executors.newSingleThreadScheduledExecutor((runnable) -> {
            Thread thread = new Thread(runnable, "easyssf-jdbc-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        long millis = this.interval.toMillis();
        this.executor.scheduleWithFixedDelay(this::purgeExpired, millis, millis, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (this.executor != null) {
            this.executor.shutdownNow();
            this.executor = null;
        }
    }

    public synchronized boolean isRunning() {
        return this.executor != null;
    }

}
