package org.easyssf.receiver.jdbc;

import java.util.List;

import org.easyssf.core.support.SsfAssert;

/**
 * The tables of the JDBC stores. The same statements, with the default table prefix, are
 * available as {@code schema.sql} next to this class
 * ({@code classpath:org/easyssf/receiver/jdbc/schema.sql}) to create the tables with a
 * schema migration tool.
 */
public final class JdbcSsfSchema {

    /**
     * The default prefix of the table names.
     */
    public static final String DEFAULT_TABLE_PREFIX = "EASYSSF_";

    /**
     * Where the statements are, for messages.
     */
    public static final String SCHEMA_LOCATION = "classpath:org/easyssf/receiver/jdbc/schema.sql";

    private JdbcSsfSchema() {
    }

    /**
     * The name of the table of the {@link JdbcSsfJtiDedupStore}.
     */
    public static String processedSetTable(String tablePrefix) {
        return validPrefix(tablePrefix) + "PROCESSED_SET";
    }

    /**
     * The name of the table of the {@link JdbcSsfTokenRevocationStore}.
     */
    public static String revocationTable(String tablePrefix) {
        return validPrefix(tablePrefix) + "REVOCATION";
    }

    private static String validPrefix(String tablePrefix) {
        SsfAssert.isTrue(tablePrefix != null && tablePrefix.matches("[A-Za-z0-9_.]*"),
                "The table prefix may only consist of letters, digits, '_' and '.'");
        return tablePrefix;
    }

    /**
     * The statements that create the table of the {@link JdbcSsfJtiDedupStore}.
     */
    public static List<String> createProcessedSetTable(String tablePrefix) {
        String table = processedSetTable(tablePrefix);
        String name = table.replace('.', '_');
        return List.of("""
                CREATE TABLE %s (
                    ISSUER VARCHAR(255) NOT NULL,
                    JTI VARCHAR(255) NOT NULL,
                    PROCESSED_AT BIGINT NOT NULL,
                    CONSTRAINT %s_PK PRIMARY KEY (ISSUER, JTI)
                )""".formatted(table, name), "CREATE INDEX %s_IX1 ON %s (PROCESSED_AT)".formatted(name, table));
    }

    /**
     * The statements that create the table of the {@link JdbcSsfTokenRevocationStore}.
     */
    public static List<String> createRevocationTable(String tablePrefix) {
        String table = revocationTable(tablePrefix);
        String name = table.replace('.', '_');
        return List.of("""
                CREATE TABLE %s (
                    KIND VARCHAR(16) NOT NULL,
                    ISSUER VARCHAR(255) NOT NULL,
                    ID VARCHAR(255) NOT NULL,
                    REVOKED_AT BIGINT NOT NULL,
                    EXPIRES_AT BIGINT NOT NULL,
                    CONSTRAINT %s_PK PRIMARY KEY (KIND, ISSUER, ID)
                )""".formatted(table, name), "CREATE INDEX %s_IX1 ON %s (EXPIRES_AT)".formatted(name, table));
    }

    /**
     * Makes sure a table exists.
     * @param jdbc runs the statements
     * @param table the name of the table
     * @param createStatements the statements that create it
     * @param create whether to create the table if it does not exist
     * @param hint what else the application could do, appended to the message of the
     * exception, may be {@code null}
     * @throws IllegalStateException if the table does not exist and is not to be created
     */
    public static void prepareTable(SsfJdbcOperations jdbc, String table, List<String> createStatements, boolean create,
            String hint) {
        if (jdbc.tableExists(table)) {
            return;
        }
        if (!create) {
            throw new IllegalStateException("The table " + table + " of the easyssf receiver does not exist. "
                    + "Create it with the statements in " + SCHEMA_LOCATION
                    + ((hint != null && !hint.isBlank()) ? ", " + hint : "") + ".");
        }
        createStatements.forEach(jdbc::execute);
    }

}
