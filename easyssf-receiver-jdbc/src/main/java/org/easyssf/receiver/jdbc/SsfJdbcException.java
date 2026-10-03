package org.easyssf.receiver.jdbc;

/**
 * A statement of a store failed.
 */
public class SsfJdbcException extends RuntimeException {

    private final String sqlState;

    public SsfJdbcException(String message, String sqlState, Throwable cause) {
        super(message, cause);
        this.sqlState = sqlState;
    }

    /**
     * @return the SQLSTATE of the failure, {@code null} if the driver gave none
     */
    public String getSqlState() {
        return this.sqlState;
    }

}
