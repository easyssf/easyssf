package org.easyssf.receiver.spring.boot.jdbc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.receiver.set.SsfJtiDedupStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.util.Assert;

/**
 * {@link SsfJtiDedupStore} that remembers processed SETs in a database table, so that a
 * SET is handled once even if it is delivered to several instances of an application or
 * again after a restart.
 *
 * <p>
 * Whether a SET was seen before is decided by inserting it: the primary key of the table
 * makes that atomic. SETs are forgotten after the retention time: the store deletes them
 * when it is written to, at most once a minute, and {@link #purgeExpired()} does so on
 * demand, see {@link JdbcSsfStoreCleanup}.
 *
 * @see JdbcSsfSchema
 */
public class JdbcSsfJtiDedupStore implements SsfJtiDedupStore, JdbcSsfExpiringStore {

    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(1);

    private final JdbcOperations jdbc;

    private final String insert;

    private final String delete;

    private final String deleteExpired;

    private Duration retention = Duration.ofDays(7);

    private Clock clock = Clock.systemUTC();

    private volatile Instant nextCleanup = Instant.MIN;

    /**
     * @param jdbc used to access the database
     * @param tablePrefix the prefix of the table name, see
     * {@link JdbcSsfSchema#DEFAULT_TABLE_PREFIX}
     */
    public JdbcSsfJtiDedupStore(JdbcOperations jdbc, String tablePrefix) {
        Assert.notNull(jdbc, "jdbc must not be null");
        String table = JdbcSsfSchema.processedSetTable(tablePrefix);
        this.jdbc = jdbc;
        this.insert = "INSERT INTO " + table + " (ISSUER, JTI, PROCESSED_AT) VALUES (?, ?, ?)";
        this.delete = "DELETE FROM " + table + " WHERE ISSUER = ? AND JTI = ?";
        this.deleteExpired = "DELETE FROM " + table + " WHERE PROCESSED_AT < ?";
    }

    /**
     * Sets how long a processed SET is remembered. It has to cover the time a transmitter
     * keeps trying to deliver a SET.
     */
    public void setRetention(Duration retention) {
        Assert.isTrue(retention != null && retention.isPositive(), "retention must be positive");
        this.retention = retention;
    }

    public void setClock(Clock clock) {
        Assert.notNull(clock, "clock must not be null");
        this.clock = clock;
    }

    @Override
    public boolean seenBefore(SsfEventToken eventToken) {
        Instant now = this.clock.instant();
        removeExpired(now);
        try {
            this.jdbc.update(this.insert, eventToken.iss(), eventToken.jti(), now.toEpochMilli());
            return false;
        }
        catch (DuplicateKeyException ex) {
            return true;
        }
    }

    @Override
    public void forget(SsfEventToken eventToken) {
        this.jdbc.update(this.delete, eventToken.iss(), eventToken.jti());
    }

    @Override
    public int purgeExpired() {
        Instant now = this.clock.instant();
        this.nextCleanup = now.plus(CLEANUP_INTERVAL);
        return this.jdbc.update(this.deleteExpired, now.minus(this.retention).toEpochMilli());
    }

    private void removeExpired(Instant now) {
        if (now.isBefore(this.nextCleanup)) {
            return;
        }
        purgeExpired();
    }

}
