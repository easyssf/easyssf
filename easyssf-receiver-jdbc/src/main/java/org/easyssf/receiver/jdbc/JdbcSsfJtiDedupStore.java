package org.easyssf.receiver.jdbc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.easyssf.core.event.SsfEventToken;
import org.easyssf.core.support.SsfAssert;
import org.easyssf.receiver.set.SsfJtiDedupStore;

/**
 * {@link SsfJtiDedupStore} that remembers processed SETs in a database table shared by
 * the instances of an application: a SET delivered to several of them or again after a
 * restart is handled by one and skipped by the others, and a SET one instance is handling
 * is left alone by the rest. Processing stays at least once, see
 * {@link SsfJtiDedupStore}.
 *
 * <p>
 * A claim inserts the row with the state {@code IN_PROGRESS}: the primary key of the
 * table makes that atomic, and a second instance that meets the row leaves the SET alone.
 * The handlers' success turns the state into {@code PROCESSED}. {@code STATE_CHANGED_AT}
 * (called {@code PROCESSED_AT} before 0.4.0) is the time of the last state change, so an
 * {@code IN_PROGRESS} row older than the lease (60 seconds by default) counts as
 * abandoned by a crashed instance and the next claim takes it over. That
 * {@code STATE_CHANGED_AT} is also the token of the claim: the success or failure of the
 * handlers only changes the row while it still carries the token they were granted, so an
 * instance that outlived the lease leaves the claim of the taker-over alone.
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
        this.insert = "INSERT INTO " + this.table + " (ISSUER, JTI, STATE, STATE_CHANGED_AT) VALUES (?, ?, ?, ?)";
        this.select = "SELECT STATE, STATE_CHANGED_AT FROM " + this.table + " WHERE ISSUER = ? AND JTI = ?";
        this.takeOver = "UPDATE " + this.table + " SET STATE_CHANGED_AT = ? WHERE ISSUER = ? AND JTI = ? AND STATE = '"
                + IN_PROGRESS + "' AND STATE_CHANGED_AT = ?";
        // processed and forget are fenced by the claim's token, the STATE_CHANGED_AT of
        // the
        // claim
        this.markProcessed = "UPDATE " + this.table + " SET STATE = '" + PROCESSED
                + "', STATE_CHANGED_AT = ? WHERE ISSUER = ? AND JTI = ? AND STATE = '" + IN_PROGRESS
                + "' AND STATE_CHANGED_AT = ?";
        this.delete = "DELETE FROM " + this.table + " WHERE ISSUER = ? AND JTI = ? AND STATE = '" + IN_PROGRESS
                + "' AND STATE_CHANGED_AT = ?";
        this.deleteExpired = "DELETE FROM " + this.table + " WHERE STATE_CHANGED_AT < ?";
        requireCurrentColumns(tablePrefix);
    }

    /**
     * Fails with the statements to run if the table predates this release: {@code STATE}
     * came with 0.3.0, {@code STATE_CHANGED_AT} with 0.4.0.
     */
    private void requireCurrentColumns(String tablePrefix) {
        if (!this.jdbc.tableExists(this.table)) {
            return; // the first access reports the missing table
        }
        List<JdbcSsfSchema.Upgrade> missing = JdbcSsfSchema.missingUpgrades(this.jdbc, this.table,
                JdbcSsfSchema.processedSetUpgrades(tablePrefix));
        if (!missing.isEmpty()) {
            throw new IllegalStateException("The table " + this.table + " of the easyssf receiver predates this "
                    + "release and lacks the column(s) " + missing.stream().map(JdbcSsfSchema.Upgrade::column).toList()
                    + ". Run " + missing.stream().map(JdbcSsfSchema.Upgrade::statement).toList()
                    + " or the migration scripts in " + JdbcSsfSchema.MIGRATION_LOCATION);
        }
    }

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
        long token = now.toEpochMilli();
        try {
            this.jdbc.update(this.insert, eventToken.iss(), eventToken.jti(), IN_PROGRESS, token);
            return Claim.granted(token);
        }
        catch (SsfJdbcDuplicateKeyException ex) {
            List<Row> rows = select(eventToken);
            if (rows.isEmpty()) {
                // deleted in between: the redelivery will be claimed
                return Claim.inProgress();
            }
            Row row = rows.get(0);
            if (PROCESSED.equals(row.state())) {
                return Claim.processed();
            }
            Instant since = Instant.ofEpochMilli(row.since());
            if (!since.plus(this.lease).isAfter(now)) {
                // abandoned: take it over, unless another instance did just now. The new
                // STATE_CHANGED_AT is the token of the new claim; the old holder's token
                // no
                // longer matches, so it can neither complete nor forget this claim.
                int taken = this.jdbc.update(this.takeOver, token, eventToken.iss(), eventToken.jti(), row.since());
                return (taken == 1) ? Claim.granted(token) : Claim.inProgress();
            }
            return Claim.inProgress();
        }
    }

    @Override
    public void processed(SsfEventToken eventToken, Claim claim) {
        SsfAssert.notNull(claim, "claim must not be null");
        long now = this.clock.instant().toEpochMilli();
        int updated = this.jdbc.update(this.markProcessed, now, eventToken.iss(), eventToken.jti(), claim.token());
        if (updated == 0 && select(eventToken).isEmpty()) {
            // the claim was purged in between: record the success anyway. A duplicate
            // means
            // another instance claimed the SET since, which then owns it.
            try {
                this.jdbc.update(this.insert, eventToken.iss(), eventToken.jti(), PROCESSED, now);
            }
            catch (SsfJdbcDuplicateKeyException ex) {
                // taken over meanwhile
            }
        }
        // otherwise the claim was taken over after the lease: the SET belongs to the new
        // holder
    }

    @Override
    public void forget(SsfEventToken eventToken, Claim claim) {
        SsfAssert.notNull(claim, "claim must not be null");
        this.jdbc.update(this.delete, eventToken.iss(), eventToken.jti(), claim.token());
    }

    private List<Row> select(SsfEventToken eventToken) {
        return this.jdbc.query(this.select, (row) -> new Row(row.getString(1), row.getLong(2)), eventToken.iss(),
                eventToken.jti());
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
