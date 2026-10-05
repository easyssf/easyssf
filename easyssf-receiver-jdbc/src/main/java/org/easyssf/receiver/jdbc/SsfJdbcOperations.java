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
     * Runs a query and returns at most {@code maxRows} rows. The limit is applied with
     * {@code Statement.setMaxRows}, which every JDBC driver supports, rather than with
     * the SQL for it, which differs between databases ({@code FETCH FIRST},
     * {@code LIMIT}, {@code TOP}). This default runs the query without a limit and cuts
     * the result; implementations should override it.
     * @param sql the query with {@code ?} placeholders
     * @param maxRows the most rows to return, positive
     * @param mapper maps each row of the result
     * @param args the values of the placeholders
     * @return the mapped rows
     * @throws SsfJdbcException if the query fails
     */
    default <T> List<T> query(String sql, int maxRows, SsfJdbcRowMapper<T> mapper, Object... args) {
        List<T> rows = query(sql, mapper, args);
        return (rows.size() > maxRows) ? List.copyOf(rows.subList(0, maxRows)) : rows;
    }

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
