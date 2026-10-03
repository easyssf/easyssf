package org.easyssf.receiver.spring.boot.jdbc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.easyssf.receiver.revocation.SsfTokenRevocationStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.util.Assert;

/**
 * {@link SsfTokenRevocationStore} that keeps revocations in a database table, so that all
 * instances of a resource server reject the access tokens of a revoked session or
 * subject, whichever instance received the event. Sessions and subjects are keyed by the
 * issuer of their tokens.
 *
 * <p>
 * Checking an access token takes one query on the primary key of the table. Revocations
 * are removed after the time to live: the store deletes them when it is written to, and
 * {@link #purgeExpired()} does so on demand, see {@link JdbcSsfStoreCleanup}.
 *
 * @see JdbcSsfSchema
 */
public class JdbcSsfTokenRevocationStore implements SsfTokenRevocationStore, JdbcSsfExpiringStore {

    private static final String SESSION = "SESSION";

    private static final String SUBJECT = "SUBJECT";

    private final JdbcOperations jdbc;

    private final String table;

    private final Duration ttl;

    private Clock clock = Clock.systemUTC();

    public JdbcSsfTokenRevocationStore(JdbcOperations jdbc, String tablePrefix, Duration ttl) {
        Assert.notNull(jdbc, "jdbc must not be null");
        Assert.isTrue(ttl != null && ttl.isPositive(), "ttl must be positive");
        this.jdbc = jdbc;
        this.table = JdbcSsfSchema.revocationTable(tablePrefix);
        this.ttl = ttl;
    }

    public void setClock(Clock clock) {
        Assert.notNull(clock, "clock must not be null");
        this.clock = clock;
    }

    @Override
    public void revokeSession(String issuer, String sessionId) {
        revoke(SESSION, issuer, sessionId, this.clock.instant());
    }

    @Override
    public void revokeSubject(String issuer, String subject, Instant revokedAt) {
        revoke(SUBJECT, issuer, subject, revokedAt);
    }

    @Override
    public int purgeExpired() {
        return this.jdbc.update("DELETE FROM " + this.table + " WHERE EXPIRES_AT <= ?",
                this.clock.instant().toEpochMilli());
    }

    private void revoke(String kind, String issuer, String id, Instant revokedAt) {
        Assert.hasText(issuer, "issuer must not be empty");
        Assert.hasText(id, "id must not be empty");
        Instant now = this.clock.instant();
        purgeExpired();
        long expiresAt = now.plus(this.ttl).toEpochMilli();
        if (update(kind, issuer, id, revokedAt.toEpochMilli(), expiresAt)) {
            return;
        }
        try {
            this.jdbc.update(
                    "INSERT INTO " + this.table + " (KIND, ISSUER, ID, REVOKED_AT, EXPIRES_AT) VALUES (?, ?, ?, ?, ?)",
                    kind, issuer, id, revokedAt.toEpochMilli(), expiresAt);
        }
        catch (DuplicateKeyException ex) {
            // revoked by another instance at the same time
            update(kind, issuer, id, revokedAt.toEpochMilli(), expiresAt);
        }
    }

    private boolean update(String kind, String issuer, String id, long revokedAt, long expiresAt) {
        int updated = this.jdbc.update("UPDATE " + this.table
                + " SET REVOKED_AT = ?, EXPIRES_AT = ? WHERE KIND = ? AND ISSUER = ? AND ID = ? AND REVOKED_AT <= ?",
                revokedAt, expiresAt, kind, issuer, id, revokedAt);
        if (updated == 0) {
            // an event delivered late must not shorten a more recent revocation
            updated = this.jdbc.update(
                    "UPDATE " + this.table + " SET EXPIRES_AT = ? WHERE KIND = ? AND ISSUER = ? AND ID = ?", expiresAt,
                    kind, issuer, id);
        }
        return updated > 0;
    }

    @Override
    public boolean isSessionRevoked(String issuer, String sessionId) {
        return isRevoked(issuer, sessionId, null, null);
    }

    @Override
    public Instant getSubjectRevokedAt(String issuer, String subject) {
        if (issuer == null || subject == null) {
            return null;
        }
        List<Long> revokedAt = this.jdbc.queryForList(
                "SELECT REVOKED_AT FROM " + this.table + " WHERE KIND = ? AND ISSUER = ? AND ID = ? AND EXPIRES_AT > ?",
                Long.class, SUBJECT, issuer, subject, this.clock.instant().toEpochMilli());
        return revokedAt.isEmpty() ? null : Instant.ofEpochMilli(revokedAt.get(0));
    }

    @Override
    public boolean isRevoked(String issuer, String sessionId, String subject, Instant issuedAt) {
        if (issuer == null || (sessionId == null && subject == null)) {
            return false;
        }
        List<String> conditions = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        arguments.add(this.clock.instant().toEpochMilli());
        arguments.add(issuer);
        if (sessionId != null) {
            conditions.add("(KIND = ? AND ID = ?)");
            arguments.add(SESSION);
            arguments.add(sessionId);
        }
        if (subject != null) {
            conditions.add("(KIND = ? AND ID = ?)");
            arguments.add(SUBJECT);
            arguments.add(subject);
        }
        String query = "SELECT KIND, REVOKED_AT FROM " + this.table + " WHERE EXPIRES_AT > ? AND ISSUER = ? AND ("
                + String.join(" OR ", conditions) + ")";
        List<Boolean> revoked = this.jdbc.query(query, (row, index) -> SESSION.equals(row.getString(1))
                || issuedAt == null || issuedAt.toEpochMilli() <= row.getLong(2), arguments.toArray());
        return revoked.contains(Boolean.TRUE);
    }

}
