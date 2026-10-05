# easyssf-receiver-jdbc

The JDBC stores of the receiver in plain SQL, over the small `SsfJdbcOperations` interface that a
`DataSource` or a framework's template implements.

- The schema is `src/main/resources/org/easyssf/receiver/jdbc/schema.sql`, the current state for a
  fresh installation; it has to run on H2, PostgreSQL and MySQL unchanged. Every schema change also
  ships a migration script `migration/V<release>__<topic>.sql` (Flyway naming) and, for a new column,
  an entry in the table's `JdbcSsfSchema.*Upgrades` list so that `prepareTable` adds it where it may
  create tables. Keep changes additive with defaults; `migrationScriptsLeadToTheCurrentSchema` checks
  that the scripts end at the current schema. Prefer standard SQL to dialect features; a result limit goes
  through `SsfJdbcOperations.query(sql, maxRows, ...)` (the driver's `setMaxRows`), not through
  `FETCH FIRST` or `LIMIT`.
- Tests run on H2 in the normal build. `PostgresJdbcSsfStoresTests` and `MySqlJdbcSsfStoresTests`
  are tagged `database`, run in Testcontainers and are excluded by default:
  `./mvnw -pl easyssf-receiver-jdbc -Pdatabase-tests verify` (Docker). Run them when the SQL or the
  schema changes. All share `AbstractJdbcSsfStoresTests`; add store tests there.
  `AbstractJdbcSharedReceiversTests` (same three databases) runs two receivers against one
  `TestTransmitter` on shared stores: a SET handled once, acknowledgements handed over between
  instances, and the race where one instance acknowledges a SET another is still handling (covered
  by the in-progress state of the dedup store, see `scratch/plans/dedup-in-progress-state.md`).
- The module is an explicit Java module; test compilation adds `java.naming` because H2's data
  source implements a `javax.naming` interface (see `pom.xml`). Keep that if you touch the
  surefire or compiler configuration.
