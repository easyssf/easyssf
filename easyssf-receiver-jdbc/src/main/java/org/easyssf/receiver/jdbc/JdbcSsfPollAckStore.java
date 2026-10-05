package org.easyssf.receiver.jdbc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.easyssf.core.support.SsfAssert;
import org.easyssf.receiver.poll.SsfPendingAck;
import org.easyssf.receiver.poll.SsfPollAckStore;

/**
 * {@link SsfPollAckStore} that keeps the acknowledgements a poller owes its transmitter
 * in a database table, so that they survive a restart: a SET handled right before the
 * application stopped is acknowledged with the first poll after it started again, instead
 * of being delivered once more.
 *
 * <p>
 * Entries a transmitter never accepts are forgotten after the retention time (7 days by
 * default): the store deletes them when it is written to, at most once a minute, and
 * {@link #purgeExpired()} does so on demand, see {@link JdbcSsfStoreCleanup}.
 *
 * @see JdbcSsfSchema
 */
public class JdbcSsfPollAckStore implements SsfPollAckStore, JdbcSsfExpiringStore {

    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(1);

    private static final int MAX_DESCRIPTION_LENGTH = 1024;

    /**
     * How many entries one DELETE removes by default; databases limit the number of
     * parameters of a statement.
     */
    public static final int DEFAULT_DELETE_BATCH_SIZE = 100;

    private final SsfJdbcOperations jdbc;

    private final String insert;

    private final String select;

    private final String deletePrefix;

    private final String count;

    private final String deleteExpired;

    private Duration retention = Duration.ofDays(7);

    private int deleteBatchSize = DEFAULT_DELETE_BATCH_SIZE;

    private Clock clock = Clock.systemUTC();

    private volatile Instant nextCleanup = Instant.MIN;

    /**
     * @param jdbc used to access the database
     * @param tablePrefix the prefix of the table name, see
     * {@link JdbcSsfSchema#DEFAULT_TABLE_PREFIX}
     */
    public JdbcSsfPollAckStore(SsfJdbcOperations jdbc, String tablePrefix) {
        SsfAssert.notNull(jdbc, "jdbc must not be null");
        String table = JdbcSsfSchema.pollAckTable(tablePrefix);
        this.jdbc = jdbc;
        this.insert = "INSERT INTO " + table
                + " (ISSUER, JTI, ERROR_CODE, ERROR_DESCRIPTION, RECORDED_AT) VALUES (?, ?, ?, ?, ?)";
        // the limit is applied by the JDBC driver (Statement.setMaxRows), the SQL for it
        // differs
        // between databases
        this.select = "SELECT JTI, ERROR_CODE, ERROR_DESCRIPTION FROM " + table
                + " WHERE ISSUER = ? ORDER BY RECORDED_AT, JTI";
        this.deletePrefix = "DELETE FROM " + table + " WHERE ISSUER = ? AND JTI IN (";
        this.count = "SELECT COUNT(*) FROM " + table + " WHERE ISSUER = ?";
        this.deleteExpired = "DELETE FROM " + table + " WHERE RECORDED_AT < ?";
    }

    /**
     * Sets how long an entry the transmitter does not accept is kept.
     */
    public void setRetention(Duration retention) {
        SsfAssert.isTrue(retention != null && retention.isPositive(), "retention must be positive");
        this.retention = retention;
    }

    /**
     * Sets how many entries one DELETE statement removes ({@code JTI IN (...)}), 100 by
     * default. Databases limit the number of parameters of a statement (SQL Server to
     * 2100, Oracle to 1000 list members); a smaller batch means more statements per poll.
     */
    public void setDeleteBatchSize(int deleteBatchSize) {
        SsfAssert.isTrue(deleteBatchSize > 0, "deleteBatchSize must be positive");
        this.deleteBatchSize = deleteBatchSize;
    }

    public void setClock(Clock clock) {
        SsfAssert.notNull(clock, "clock must not be null");
        this.clock = clock;
    }

    @Override
    public void record(String issuer, SsfPendingAck ack) {
        Instant now = this.clock.instant();
        removeExpired(now);
        String description = ack.errorDescription();
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            description = description.substring(0, MAX_DESCRIPTION_LENGTH);
        }
        try {
            this.jdbc.update(this.insert, issuer, ack.jti(), ack.errorCode(), description, now.toEpochMilli());
        }
        catch (SsfJdbcDuplicateKeyException ex) {
            // recorded already, for example a SET delivered twice before it was
            // acknowledged
        }
    }

    @Override
    public List<SsfPendingAck> pending(String issuer, int limit) {
        return this.jdbc.query(this.select, limit,
                (row) -> new SsfPendingAck(row.getString(1), row.getString(2), row.getString(3)), issuer);
    }

    @Override
    public void remove(String issuer, Collection<String> jtis) {
        List<String> remaining = List.copyOf(jtis);
        for (int from = 0; from < remaining.size(); from += this.deleteBatchSize) {
            List<String> chunk = remaining.subList(from, Math.min(from + this.deleteBatchSize, remaining.size()));
            Object[] args = new Object[chunk.size() + 1];
            args[0] = issuer;
            for (int i = 0; i < chunk.size(); i++) {
                args[i + 1] = chunk.get(i);
            }
            this.jdbc.update(this.deletePrefix + "?, ".repeat(chunk.size() - 1) + "?)", args);
        }
    }

    @Override
    public int size(String issuer) {
        return this.jdbc.query(this.count, (row) -> row.getInt(1), issuer).get(0);
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

}
