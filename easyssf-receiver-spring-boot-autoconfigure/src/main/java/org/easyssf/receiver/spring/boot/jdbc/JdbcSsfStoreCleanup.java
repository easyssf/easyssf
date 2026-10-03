package org.easyssf.receiver.spring.boot.jdbc;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.Assert;

/**
 * Purges the expired rows of the {@link JdbcSsfExpiringStore stores} periodically, so
 * that they do not linger in a receiver that gets no events for a while. Runs on its own
 * daemon thread while the application context is running.
 */
public class JdbcSsfStoreCleanup implements SmartLifecycle {

    private static final Log logger = LogFactory.getLog(JdbcSsfStoreCleanup.class);

    private final List<JdbcSsfExpiringStore> stores;

    private final Duration interval;

    private ScheduledExecutorService executor;

    /**
     * @param stores the stores to purge
     * @param interval how often; the cleanup is off when zero or negative
     */
    public JdbcSsfStoreCleanup(List<JdbcSsfExpiringStore> stores, Duration interval) {
        Assert.notNull(stores, "stores must not be null");
        Assert.notNull(interval, "interval must not be null");
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

    @Override
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

    @Override
    public synchronized void stop() {
        if (this.executor != null) {
            this.executor.shutdownNow();
            this.executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return this.executor != null;
    }

}
