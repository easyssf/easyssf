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

    /**
     * Where the versioned migration scripts are, one per release that changed the schema,
     * named for Flyway ({@code V0_1_0__...sql}) and plain SQL for any other tool.
     */
    public static final String MIGRATION_LOCATION = "classpath:org/easyssf/receiver/jdbc/migration/";

    /**
     * A column a current table has and the statement that adds it to a table of an older
     * release.
     *
     * @param column the name of the column
     * @param statement the {@code ALTER TABLE} that adds it, with a default for the rows
     * that exist
     */
    public record Upgrade(String column, String statement) {
    }

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

    /**
     * The name of the table of the {@link JdbcSsfPollAckStore}.
     */
    public static String pollAckTable(String tablePrefix) {
        return validPrefix(tablePrefix) + "POLL_ACK";
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
                    STATE VARCHAR(16) NOT NULL,
                    STATE_CHANGED_AT BIGINT NOT NULL,
                    CONSTRAINT %s_PK PRIMARY KEY (ISSUER, JTI)
                )""".formatted(table, name), "CREATE INDEX %s_IX1 ON %s (STATE_CHANGED_AT)".formatted(name, table));
    }

    /**
     * The columns later releases added to the table of the {@link JdbcSsfJtiDedupStore}:
     * {@code STATE} in 0.3.0, and {@code STATE_CHANGED_AT} in 0.4.0, which is
     * {@code PROCESSED_AT} renamed ({@code RENAME COLUMN}, which H2, PostgreSQL and MySQL
     * 8 support) because it records the claim as well as the completion.
     */
    public static List<Upgrade> processedSetUpgrades(String tablePrefix) {
        String table = processedSetTable(tablePrefix);
        return List.of(
                new Upgrade("STATE", "ALTER TABLE " + table + " ADD STATE VARCHAR(16) DEFAULT 'PROCESSED' NOT NULL"),
                new Upgrade("STATE_CHANGED_AT",
                        "ALTER TABLE " + table + " RENAME COLUMN PROCESSED_AT TO STATE_CHANGED_AT"));
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
     * The statements that create the table of the {@link JdbcSsfPollAckStore}.
     */
    public static List<String> createPollAckTable(String tablePrefix) {
        String table = pollAckTable(tablePrefix);
        String name = table.replace('.', '_');
        String tableDdl = """
                CREATE TABLE %s (
                    ISSUER VARCHAR(255) NOT NULL,
                    JTI VARCHAR(255) NOT NULL,
                    ERROR_CODE VARCHAR(64),
                    ERROR_DESCRIPTION VARCHAR(1024),
                    RECORDED_AT BIGINT NOT NULL,
                    CONSTRAINT %s_PK PRIMARY KEY (ISSUER, JTI)
                )""".formatted(table, name);
        String indexDdl = "CREATE INDEX %s_IX1 ON %s (RECORDED_AT)".formatted(name, table);
        return List.of(tableDdl, indexDdl);
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
        prepareTable(jdbc, table, createStatements, List.of(), create, hint);
    }

    /**
     * Makes sure a table exists and has the columns of this release.
     * @param jdbc runs the statements
     * @param table the name of the table
     * @param createStatements the statements that create it
     * @param upgrades the columns older releases lack and the statements that add them
     * @param create whether to create a missing table, and to add missing columns to an
     * existing one
     * @param hint what else the application could do, appended to the message of the
     * exception, may be {@code null}
     * @throws IllegalStateException if the table does not exist or lacks a column and is
     * not to be changed; the message carries the statements to run
     */
    public static void prepareTable(SsfJdbcOperations jdbc, String table, List<String> createStatements,
            List<Upgrade> upgrades, boolean create, String hint) {
        String orElse = (hint != null && !hint.isBlank()) ? ", " + hint : "";
        if (!jdbc.tableExists(table)) {
            if (!create) {
                throw new IllegalStateException("The table " + table + " of the easyssf receiver does not exist. "
                        + "Create it with the statements in " + SCHEMA_LOCATION + orElse + ".");
            }
            createStatements.forEach(jdbc::execute);
            return;
        }
        List<Upgrade> missing = missingUpgrades(jdbc, table, upgrades);
        if (missing.isEmpty()) {
            return;
        }
        if (!create) {
            throw new IllegalStateException("The table " + table + " of the easyssf receiver predates this release "
                    + "and lacks the column(s) " + missing.stream().map(Upgrade::column).toList() + ". Run "
                    + missing.stream().map(Upgrade::statement).toList() + " or the migration scripts in "
                    + MIGRATION_LOCATION + orElse + ".");
        }
        for (Upgrade upgrade : missing) {
            try {
                jdbc.execute(upgrade.statement());
            }
            catch (SsfJdbcException ex) {
                // another instance starting at the same time may have added the column
                if (!columnExists(jdbc, table, upgrade.column())) {
                    throw ex;
                }
            }
        }
    }

    /**
     * @return the upgrades whose column the existing table lacks
     */
    public static List<Upgrade> missingUpgrades(SsfJdbcOperations jdbc, String table, List<Upgrade> upgrades) {
        return upgrades.stream().filter((upgrade) -> !columnExists(jdbc, table, upgrade.column())).toList();
    }

    private static boolean columnExists(SsfJdbcOperations jdbc, String table, String column) {
        try {
            jdbc.query("SELECT " + column + " FROM " + table + " WHERE 1 = 0", (row) -> row.getString(1));
            return true;
        }
        catch (SsfJdbcException ex) {
            return false;
        }
    }

}
