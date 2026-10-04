# easyssf-receiver-jdbc

The JDBC stores of the receiver in plain SQL, over the small `SsfJdbcOperations` interface that a
`DataSource` or a framework's template implements.

- The schema is `src/main/resources/org/easyssf/receiver/jdbc/schema.sql`; it has to run on H2 and
  PostgreSQL unchanged. Prefer standard SQL to dialect features.
- Tests run on H2 in the normal build. `PostgresJdbcSsfStoresTests` is tagged `database`, runs in
  Testcontainers and is excluded by default:
  `./mvnw -pl easyssf-receiver-jdbc -Pdatabase-tests verify` (Docker). Run it when the SQL or the
  schema changes. Both share `AbstractJdbcSsfStoresTests`; add store tests there.
- The module is an explicit Java module; test compilation adds `java.naming` because H2's data
  source implements a `javax.naming` interface (see `pom.xml`). Keep that if you touch the
  surefire or compiler configuration.
