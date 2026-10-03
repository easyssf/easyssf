package org.easyssf.receiver.spring.boot.jdbc;

/**
 * A store whose rows expire and are purged, by the store itself when it is written to and
 * periodically by {@link JdbcSsfStoreCleanup}.
 */
public interface JdbcSsfExpiringStore {

    /**
     * Deletes the rows that expired.
     * @return the number of rows deleted
     */
    int purgeExpired();

}
