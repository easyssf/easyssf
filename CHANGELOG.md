# Changelog

The notable changes of every release. The section of a version is the text of its
[GitHub release](https://github.com/easyssf/easyssf/releases). The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

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

[Unreleased]: https://github.com/easyssf/easyssf/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/easyssf/easyssf/releases/tag/v0.1.0
