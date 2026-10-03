package org.easyssf.receiver.jdbc;

import java.util.List;

/**
 * Runs the SQL of the stores. Implement it over whatever the application uses to access
 * its database: {@link DataSourceSsfJdbcOperations} over a {@code DataSource}, or a
 * framework's template so that its transactions and exception translation apply.
 */
public interface SsfJdbcOperations {

    /**
     * Runs an insert, update or delete.
     * @param sql the statement with {@code ?} placeholders
     * @param args the values of the placeholders
     * @return the number of rows affected
     * @throws SsfJdbcDuplicateKeyException if the statement violates a unique constraint
     * @throws SsfJdbcException if the statement fails otherwise
     */
    int update(String sql, Object... args);

    /**
     * Runs a query.
     * @param sql the query with {@code ?} placeholders
     * @param mapper maps each row of the result
     * @param args the values of the placeholders
     * @return the mapped rows
     * @throws SsfJdbcException if the query fails
     */
    <T> List<T> query(String sql, SsfJdbcRowMapper<T> mapper, Object... args);

    /**
     * Runs a statement that returns nothing, for example DDL.
     * @throws SsfJdbcException if the statement fails
     */
    void execute(String sql);

    /**
     * @return whether the table exists
     */
    boolean tableExists(String table);

}
