package org.easyssf.receiver.jdbc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.support.SsfAssert;
import org.easyssf.receiver.set.SsfJtiDedupStore;

/**
 * {@link SsfJtiDedupStore} that remembers processed SETs in a database table, so that a
 * SET is handled once even if it is delivered to several instances of an application or
 * again after a restart.
 *
 * <p>
 * A claim inserts the row with the state {@code IN_PROGRESS}: the primary key of the
 * table makes that atomic, and a second instance that meets the row leaves the SET alone.
 * The handlers' success turns the state into {@code PROCESSED}. {@code PROCESSED_AT} is
 * the time of the last state change, so an {@code IN_PROGRESS} row older than the lease
 * (60 seconds by default) counts as abandoned by a crashed instance and the next claim
 * takes it over.
 *
 * <p>
 * SETs are forgotten after the retention time: the store deletes them when it is written
 * to, at most once a minute, and {@link #purgeExpired()} does so on demand, see
 * {@link JdbcSsfStoreCleanup}.
 *
 * @see JdbcSsfSchema
 */
public class JdbcSsfJtiDedupStore implements SsfJtiDedupStore, JdbcSsfExpiringStore {

    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(1);

    private static final String IN_PROGRESS = "IN_PROGRESS";

    private static final String PROCESSED = "PROCESSED";

    private final SsfJdbcOperations jdbc;

    private final String table;

    private final String insert;

    private final String select;

    private final String takeOver;

    private final String markProcessed;

    private final String delete;

    private final String deleteExpired;

    private Duration retention = Duration.ofDays(7);

    private Duration lease = Duration.ofSeconds(60);

    private Clock clock = Clock.systemUTC();

    private volatile Instant nextCleanup = Instant.MIN;

    /**
     * @param jdbc used to access the database
     * @param tablePrefix the prefix of the table name, see
     * {@link JdbcSsfSchema#DEFAULT_TABLE_PREFIX}
     * @throws IllegalStateException if the table exists without the {@code STATE} column
     * of easyssf 0.3.0, with the statement that adds it
     */
    public JdbcSsfJtiDedupStore(SsfJdbcOperations jdbc, String tablePrefix) {
        SsfAssert.notNull(jdbc, "jdbc must not be null");
        this.table = JdbcSsfSchema.processedSetTable(tablePrefix);
        this.jdbc = jdbc;
        this.insert = "INSERT INTO " + this.table + " (ISSUER, JTI, STATE, PROCESSED_AT) VALUES (?, ?, ?, ?)";
        this.select = "SELECT STATE, PROCESSED_AT FROM " + this.table + " WHERE ISSUER = ? AND JTI = ?";
        this.takeOver = "UPDATE " + this.table + " SET PROCESSED_AT = ? WHERE ISSUER = ? AND JTI = ? AND STATE = '"
                + IN_PROGRESS + "' AND PROCESSED_AT = ?";
        this.markProcessed = "UPDATE " + this.table + " SET STATE = '" + PROCESSED
                + "', PROCESSED_AT = ? WHERE ISSUER = ? AND JTI = ?";
        this.delete = "DELETE FROM " + this.table + " WHERE ISSUER = ? AND JTI = ?";
        this.deleteExpired = "DELETE FROM " + this.table + " WHERE PROCESSED_AT < ?";
        requireStateColumn();
    }

    private void requireStateColumn() {
        try {
            this.jdbc.query("SELECT STATE FROM " + this.table + " WHERE 1 = 0", (row) -> row.getString(1));
        }
        catch (SsfJdbcException ex) {
            if (this.jdbc.tableExists(this.table)) {
                throw new IllegalStateException("The table " + this.table + " of the easyssf receiver predates "
                        + "easyssf 0.3.0 and lacks the STATE column. Add it with: ALTER TABLE " + this.table
                        + " ADD STATE VARCHAR(16) DEFAULT '" + PROCESSED + "' NOT NULL", ex);
            }
            throw ex;
        }
    }

    /**
     * Sets how long a processed SET is remembered. It has to cover the time a transmitter
     * keeps trying to deliver a SET.
     */
    public void setRetention(Duration retention) {
        SsfAssert.isTrue(retention != null && retention.isPositive(), "retention must be positive");
        this.retention = retention;
    }

    /**
     * Sets how long a claim holds before the SET counts as abandoned by a crashed
     * instance and is handled again; 60 seconds by default, longer than the longest
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
        Instant now = this.clock.instant();
        removeExpired(now);
        try {
            this.jdbc.update(this.insert, eventToken.iss(), eventToken.jti(), IN_PROGRESS, now.toEpochMilli());
            return Claim.NEW;
        }
        catch (SsfJdbcDuplicateKeyException ex) {
            List<Row> rows = this.jdbc.query(this.select, (row) -> new Row(row.getString(1), row.getLong(2)),
                    eventToken.iss(), eventToken.jti());
            if (rows.isEmpty()) {
                // deleted in between: the redelivery will be claimed
                return Claim.IN_PROGRESS;
            }
            Row row = rows.get(0);
            if (PROCESSED.equals(row.state())) {
                return Claim.PROCESSED;
            }
            Instant since = Instant.ofEpochMilli(row.since());
            if (!since.plus(this.lease).isAfter(now)) {
                // abandoned: take it over, unless another instance did just now
                int taken = this.jdbc.update(this.takeOver, now.toEpochMilli(), eventToken.iss(), eventToken.jti(),
                        row.since());
                return (taken == 1) ? Claim.NEW : Claim.IN_PROGRESS;
            }
            return Claim.IN_PROGRESS;
        }
    }

    @Override
    public void processed(SsfEventToken eventToken) {
        long now = this.clock.instant().toEpochMilli();
        int updated = this.jdbc.update(this.markProcessed, now, eventToken.iss(), eventToken.jti());
        if (updated == 0) {
            // the claim was purged in between
            try {
                this.jdbc.update(this.insert, eventToken.iss(), eventToken.jti(), PROCESSED, now);
            }
            catch (SsfJdbcDuplicateKeyException ex) {
                this.jdbc.update(this.markProcessed, now, eventToken.iss(), eventToken.jti());
            }
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
        if (now.isAfter(this.nextCleanup)) {
            purgeExpired();
        }
    }

    private record Row(String state, long since) {
    }

}
