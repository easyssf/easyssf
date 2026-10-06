# Changelog

The notable changes of every release. The section of a version is the text of its
[GitHub release](https://github.com/easyssf/easyssf/releases). The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- The column `PROCESSED_AT` of `EASYSSF_PROCESSED_SET` is now `STATE_CHANGED_AT`: since 0.3.0 it has
  recorded the time of the claim as well as that of the completion, and it fences the claim. Existing
  tables need `ALTER TABLE EASYSSF_PROCESSED_SET RENAME COLUMN PROCESSED_AT TO STATE_CHANGED_AT` (the
  migration script `V0_4_0__state_changed_at.sql`; `RENAME COLUMN` works on H2, PostgreSQL and MySQL 8),
  applied on startup where tables may be created and named in the error otherwise.

## [0.3.0] - 2026-10-06

POLL delivery that survives restarts and runs on several instances: acknowledgements wait in a
store, optionally in the database, long polling keeps a request outstanding, and a SET is claimed
while its handlers run so that two instances never acknowledge a SET one of them failed on.
The JDBC schema gained a column and a table; migration scripts and startup upgrades come with it.
Handlers get typed access to CAEP and RISC events with `SsfCaepEventHandler` and `SsfRiscEventHandler`,
and the documentation now says in plain words that processing is at least once, with `idempotencyKey()`
as the key for a handler's side effects.

### Added

- POLL delivery keeps the acknowledgements and error reports it owes the transmitter in an
  `SsfPollAckStore` until a poll request has carried them: in memory by default, or durably with
  `JdbcSsfPollAckStore` (table `EASYSSF_POLL_ACK`, auto-configured with the other JDBC stores,
  `easyssf.receiver.jdbc.ack-retention`), so that a SET handled right before a restart is
  acknowledged afterwards instead of being delivered again. `SsfPoller.stop()` sends the pending
  acknowledgements with a last request; the health details show them as `pendingAcks` and the
  gauge `easyssf.receiver.poll.pending-acks` counts them.
- Long polling (RFC 8936, section 2.5): `SsfPoller.setLongPolling(hold)` keeps one request
  outstanding that the transmitter holds until SETs are available; `easyssf.receiver.poll.long-polling`
  and `poll.long-polling-hold` in the Spring Boot starter, off by default. `SsfHttpRequest` carries an
  optional timeout per request. `TestTransmitter` holds long polls for `setLongPollHold`.
  `SsfPoller.setThreadFactory` lets a framework create the polling thread, or use a virtual one.

- A SET is claimed in the `SsfJtiDedupStore` while its handlers run (`Claim.IN_PROGRESS`): another
  instance that receives it meanwhile neither handles nor acknowledges it (`SsfSetInProgressException`,
  metrics outcome `in_progress`), so a handler failure on the first instance is not masked. A claim
  older than the lease (`easyssf.receiver.dedup.lease`, 60 seconds) counts as abandoned and the SET is
  handled again. The claim carries a token that fences it: an instance whose handlers outlived the
  lease can neither complete nor forget the claim of the instance that took the SET over.

- `SsfEventToken.idempotencyKey()` and `SsfEventContext.idempotencyKey()`, the issuer and `jti` of a
  SET, as the key for the side effects of a handler: processing is at least once, which the
  documentation now says in those words.
- Typed CAEP and RISC events: `SsfCaepEventHandler` and `SsfRiscEventHandler` dispatch the events of a
  SET to a method per event (`onSessionRevoked`, `onAssuranceLevelChange`, `onAccountDisabled`, ...),
  with `SsfCaepEvent` and `SsfRiscEvent` from `easyssf-core` for the claims the specifications define
  (`eventTimestamp()`, `initiatingEntity()`, the localized `reasonAdmin()` and `reasonUser()`,
  `credentialType()`, `currentLevel()`, `changeDirection()`, `reason()`, `newValue()`, ...). An event
  type in the CAEP or RISC namespace that easyssf does not know yet is `SsfCaepEventKind.OTHER` or
  `SsfRiscEventKind.OTHER` and reaches `onOtherCaepEvent` or `onOtherRiscEvent`. `SsfEventContext.eventFor`
  stays for raw access. `SsfEventTypes` gained `RiscSessionsRevoked` (deprecated by RISC, still sent) and
  `isCaepEvent`/`isRiscEvent`; `SsfEventTimestamps` reads an `event_timestamp` in seconds or milliseconds.
- Schema migration: `easyssf-receiver-jdbc` ships versioned migration scripts in
  `org/easyssf/receiver/jdbc/migration/` (Flyway naming, plain SQL), and `JdbcSsfSchema.prepareTable`
  adds the columns a release introduced to an existing table where it may create tables
  (`initialize-schema` `embedded` or `always`); otherwise it names the statements to run.

### Changed

- `SsfJtiDedupStore` has `claim`, which returns a `Claim` with the `State` of the SET and the token of
  the claim granted, and `processed` and `forget`, which take that claim; `seenBefore` is a deprecated
  default method. The table `EASYSSF_PROCESSED_SET` gained the column `STATE`; existing tables need
  `ALTER TABLE EASYSSF_PROCESSED_SET ADD STATE VARCHAR(16) DEFAULT 'PROCESSED' NOT NULL` (the migration
  script `V0_3_0__dedup_state_and_poll_acks.sql`), applied on startup where tables may be created and
  named in the error otherwise.
- The schema of `easyssf-receiver-jdbc` has a third table, `EASYSSF_POLL_ACK`; create it with the
  statements of `schema.sql` where the tables are not created on startup.
- `SsfPoller` polls on a thread of its own instead of a scheduled executor, and the in-memory stores
  lock with `ReentrantLock` instead of `synchronized`, which pins virtual threads before JDK 24.
- `SsfJdbcOperations.query(sql, maxRows, mapper, args)` limits a result through the JDBC driver, so
  the stores need no dialect-specific SQL; the JDBC stores are now tested on MySQL as well as on H2
  and PostgreSQL.

## [0.2.0] - 2026-10-04

SCIM Events (RFC 9967) for the receiver: the event types, the `scim` subject, a typed payload and a
handler that dispatches by operation, with a provisioning example. Pull requests now build on CI,
and the README presents plain Java, Spring Boot and Quarkus side by side, with a README of its own
for the Spring Boot starter.

### Added

- SCIM Events (RFC 9967): the event types under `urn:ietf:params:scim:event:` with aliases
  (`ScimProvCreateFull`, `ScimProvDeactivate`, ...), the `scim` subject identifier format with
  `SsfScimSubject` for the resource it names, `SsfScimEvent` for the typed payload (`data`,
  `attributes`, `version`, the asynchronous response) and `SsfScimEventHandler`, which dispatches
  the SCIM Events of a SET to a method per operation. `SsfSubjectClaimsMatcher` matches a `scim`
  resource with a logged-in user by configurable pairs of SCIM attribute and claim
  (`easyssf.receiver.oidc-client.scim-attribute-claims`), by default `externalId` and `id` against
  `sub` and `userName` against `preferred_username`, so `ScimProvDeactivate` and `ScimProvDelete`
  can terminate sessions.
- `example-scim-provisioning`: a Spring Boot example that mirrors SCIM `Users` into a local directory
  with `SsfScimEventHandler`, driven by the `TestTransmitter` of `easyssf-test` because Keycloak does
  not emit SCIM Events.

## [0.1.0] - 2026-10-03

The first release: a receiver for the OpenID Shared Signals Framework 1.0, as a framework-free
Java library and as a Spring Boot 4.1 starter.

### Added

- `easyssf-core`: the vocabulary of the protocol. Security Event Tokens, the event types of
  SSF, CAEP and RISC with aliases (and your own), subjects with every RFC 9493 identifier
  format and the members of complex subjects, transmitter metadata and stream configurations,
  all as immutable value objects.
- `easyssf-receiver`: SET verification with Nimbus (RS256 by default, any asymmetric algorithm
  by configuration), push delivery (RFC 8935) and polling (RFC 8936) with rate-limit handling,
  stream management and verification against the transmitter, de-duplication by `jti`,
  issuer-scoped session and token revocation, several transmitters per receiver routed by
  issuer, Micrometer metrics, and virtual-thread friendly locking.
- Strict by default: the top-level `sub_id` of SSF 1.0 is required (`SubjectCompatibilityMode.LEGACY`
  accepts transmitters following earlier drafts), verification events must name the stream,
  subject members a transmitter declares critical must be understood by the application,
  transmitter issuers and endpoints must use HTTPS (`SsfTransmitterUriPolicy.INSECURE` for
  tests).
- `easyssf-receiver-jdbc`: the processed-SET and revocation stores on JDBC, plain SQL, tested
  on H2 and PostgreSQL.
- `easyssf-receiver-spring-boot-starter`: auto-configuration for the servlet stack, the push
  endpoint with Spring Security, resource-server token validation and OIDC client session
  termination on CAEP events, `JdbcTemplate` stores, an Actuator health indicator, and
  configuration for several transmitters under `easyssf.receiver.transmitters.<name>.*`.
- `easyssf-test`: `TestTransmitter`, an in-process transmitter for the tests of a receiver.
- `easyssf-test-conformance`: a Testcontainers harness that runs the receiver plans of the
  OpenID conformance suite; the Spring Boot starter passes the SSF 1.0 and CAEP Interop
  Profile plans, push and poll.
- Examples: a resource server and an OIDC client against Keycloak.
- The framework-free modules are Java modules; every artifact is signed, carries a CycloneDX
  SBOM and a self-contained POM.

[Unreleased]: https://github.com/easyssf/easyssf/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/easyssf/easyssf/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/easyssf/easyssf/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/easyssf/easyssf/releases/tag/v0.1.0
