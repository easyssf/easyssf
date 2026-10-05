package org.easyssf.receiver.jdbc;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Several receivers on one PostgreSQL, in a Testcontainers container shared by the tests
 * of the class. Tagged {@code database}: the normal build excludes it,
 * {@code -Pdatabase-tests} runs it (Docker required).
 */
@Tag("database")
class PostgresJdbcSharedReceiversTests extends AbstractJdbcSharedReceiversTests {

    private static final String IMAGE = "postgres:18.4";

    private static final List<String> TABLES = List.of("EASYSSF_PROCESSED_SET", "EASYSSF_REVOCATION",
            "APP_SSF_PROCESSED_SET", "OTHER_REVOCATION", "EASYSSF_POLL_ACK");

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer(IMAGE);
        postgres.start();
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    @Override
    protected SsfJdbcOperations emptyDatabase() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        SsfJdbcOperations jdbc = new DataSourceSsfJdbcOperations(dataSource);
        for (String table : TABLES) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        return jdbc;
    }

}
