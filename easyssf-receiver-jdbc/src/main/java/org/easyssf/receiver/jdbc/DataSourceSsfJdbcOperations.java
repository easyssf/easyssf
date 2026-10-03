package org.easyssf.receiver.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

import org.easyssf.core.support.SsfAssert;

/**
 * {@link SsfJdbcOperations} on plain JDBC over a {@link DataSource}. Every statement
 * takes a connection from the data source and closes it; a transaction the data source
 * enlists the connection in, as a JTA data source does, applies.
 */
public class DataSourceSsfJdbcOperations implements SsfJdbcOperations {

    /**
     * SQLSTATE classes of a statement the database did not understand, among them a
     * missing table: standard class 42, and the classes some drivers use instead.
     */
    private static final Set<String> BAD_GRAMMAR_CLASSES = Set.of("07", "21", "2A", "37", "42", "65", "S0");

    private final DataSource dataSource;

    public DataSourceSsfJdbcOperations(DataSource dataSource) {
        SsfAssert.notNull(dataSource, "dataSource must not be null");
        this.dataSource = dataSource;
    }

    @Override
    public int update(String sql, Object... args) {
        try (Connection connection = this.dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            return statement.executeUpdate();
        }
        catch (SQLException ex) {
            if (ex instanceof SQLIntegrityConstraintViolationException || isClass(ex, "23")) {
                throw new SsfJdbcDuplicateKeyException("Duplicate key: " + sql, ex.getSQLState(), ex);
            }
            throw new SsfJdbcException("Could not run " + sql + ": " + ex.getMessage(), ex.getSQLState(), ex);
        }
    }

    @Override
    public <T> List<T> query(String sql, SsfJdbcRowMapper<T> mapper, Object... args) {
        try (Connection connection = this.dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(mapper.map(resultSet));
                }
                return rows;
            }
        }
        catch (SQLException ex) {
            throw new SsfJdbcException("Could not run " + sql + ": " + ex.getMessage(), ex.getSQLState(), ex);
        }
    }

    @Override
    public void execute(String sql) {
        try (Connection connection = this.dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
        catch (SQLException ex) {
            throw new SsfJdbcException("Could not run " + sql + ": " + ex.getMessage(), ex.getSQLState(), ex);
        }
    }

    @Override
    public boolean tableExists(String table) {
        try {
            query("SELECT COUNT(*) FROM " + table + " WHERE 1 = 0", (resultSet) -> resultSet.getInt(1));
            return true;
        }
        catch (SsfJdbcException ex) {
            String sqlState = ex.getSqlState();
            if (sqlState != null && sqlState.length() >= 2 && BAD_GRAMMAR_CLASSES.contains(sqlState.substring(0, 2))) {
                return false;
            }
            throw ex;
        }
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }

    private static boolean isClass(SQLException ex, String sqlStateClass) {
        String sqlState = ex.getSQLState();
        return sqlState != null && sqlState.startsWith(sqlStateClass);
    }

}
