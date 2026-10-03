package org.easyssf.receiver.jdbc;

/**
 * An insert violated a unique constraint: the row exists already.
 */
public class SsfJdbcDuplicateKeyException extends SsfJdbcException {

    public SsfJdbcDuplicateKeyException(String message, String sqlState, Throwable cause) {
        super(message, sqlState, cause);
    }

}
