# easyssf for coding agents

Read this first, then the [README](README.md) for what the library does and
[CONTRIBUTING.md](CONTRIBUTING.md) for how changes are built, tested and released. This file holds
what is not obvious from those two: how the code is organised, the conventions it follows, and the
commands that matter. A module with rules of its own has an `AGENTS.md` of its own; the closest
one applies.

## What this is

Java building blocks for the OpenID Shared Signals Framework (SSF 1.0) with CAEP, RISC and SCIM
Events (RFC 9967): a framework independent **receiver** library and a **Spring Boot starter**. A
receiver verifies Security Event Tokens (SETs, RFC 8417) a transmitter pushes (RFC 8935) or it
polls (RFC 8936), de-duplicates them, manages its stream at the transmitter and hands the events
to handlers. The code sits in the authentication path of its users, so correctness of
verification beats convenience everywhere. Status: experimental, pre-1.0, breaking changes are
acceptable when the CHANGELOG says so.

Maven multi-module build, Java 21, group id `org.easyssf`. Modules, in dependency order:

| Module | Holds | Depends on |
|---|---|---|
| `easyssf-core` | The vocabulary: SETs, subjects, event types, stream configuration, transmitter metadata, as immutable value objects | JDK only |
| `easyssf-test` | `TestTransmitter`, an in-process transmitter that signs and delivers SETs | core, Nimbus |
| `easyssf-receiver` | The receiver without a framework: verification, de-duplication, push and poll, stream management, handlers for revocation, session termination and SCIM | core, Nimbus JOSE + JWT, SLF4J; Micrometer optional |
| `easyssf-receiver-jdbc` | The JDBC stores (processed SETs, revocations) in plain SQL | receiver |
| `easyssf-test-conformance` | Testcontainers harness that runs the OpenID conformance suite's receiver plans against any receiver | receiver, Testcontainers, JUnit |
| `easyssf-receiver-spring-boot-autoconfigure` | Properties, auto-configuration, push endpoint, resource server and OIDC client integration | receiver, Spring Boot 4.1, Spring Security 7.1 |
| `easyssf-receiver-spring-boot-starter` | Only the dependency and a `package-info.java` | autoconfigure |
| `easyssf-receiver-spring-boot-examples` | Resource server and OIDC client against Keycloak, not published | starter |
| `easyssf-receiver-spring-boot-conformance-tests` | The Spring Boot receiver under test and the four plan tests, not published | starter, test-conformance |

The Spring Boot modules are the reference integration; the receiver library is what other
frameworks build on. New protocol behaviour goes into `easyssf-receiver`, new vocabulary into
`easyssf-core`, Spring only into the autoconfigure module.

## Downstream consumers

The Quarkiverse extension [quarkus-openid-ssf](https://github.com/quarkiverse/quarkus-openid-ssf)
is built on `easyssf-receiver` (its `SsfReceiverProducers` wires the processor, verifier,
registrar and poller; `SsfReceiverConfig` maps its properties onto the library's setters) and
pins a released version. Consequences for a change here:

- The public API of `easyssf-core` and `easyssf-receiver` is consumed outside this repository.
  A renamed or removed type, method or setter, a changed default, or a new mandatory setting is a
  breaking change: say so in the CHANGELOG under its own heading, and prefer adding over
  changing. Additions the extension should pick up (a new event family, a new property of the
  Spring Boot starter that has a Quarkus counterpart) are worth a sentence in the CHANGELOG too,
  so the extension's next update knows what to mirror.
- The extension inherits the conformance results of the library, so the conformance tests here
  are its safety net as well.

## Build and test

```sh
./mvnw install                                   # the normal build, formatting check included
./mvnw spotless:apply                            # format; the build fails on unformatted sources
./mvnw -q -o -pl easyssf-receiver -am test       # one module and what it depends on, offline
./mvnw -o -pl easyssf-receiver-spring-boot-autoconfigure -am test
```

- Always pass `-am` when building a subset. Without it Maven takes the modules upstream from the
  local repository, where they may predate your change, and the compiler reports packages that do
  not exist.
- `-o` (offline) is fine for everything but dependency updates; `-q` keeps the output short, then
  check `target/surefire-reports/*.xml` for `failures="0"` and `errors="0"`.
- Two test groups are excluded from the normal build and need Docker: the `database` tag of
  `easyssf-receiver-jdbc` (PostgreSQL, `-Pdatabase-tests`) and the `conformance` tag of the
  conformance tests (`-Pconformance`, minutes per plan). Run them only when a change touches the
  stores or the conformance harness, and say so.
- Spotless applies the Spring Java Format conventions (`etc/eclipse-formatter.prefs`): 4 spaces,
  120 columns of code and 90 of javadoc, `this.` for fields, import order `java|javax|jakarta, org, com, others, static`.
  Run `spotless:apply` rather than formatting by hand.

## Code conventions

- **Names.** Every public type is prefixed `Ssf` (`SsfSubject`, `SsfEventHandler`,
  `SsfScimEvent`) and lives in a package named after its concern (`event`, `set`, `stream`,
  `session`, `revocation`, `scim`, ...). Tests are `<Type>Tests`, JUnit 5 with AssertJ, test
  method names are sentences (`subjectFallsBackToEventSubjectOfEarlyDrafts`).
- **Value objects are records**, immutable, with collections defensively copied in the compact
  constructor through `SsfCollections.copyOf` and nulls normalised to empty. Arguments are checked
  with `SsfAssert`. Factories are static `from(...)` (lenient, returns `null` or an empty value for
  input that does not fit) and `of(...)`.
- **Protocol facts carry their source.** Javadoc and comments cite the specification and section
  (`SSF 1.0 8.1.4.1`, `RFC 9967, section 2.4`) and name the real-world deviation they tolerate
  (`// CAEP defines seconds since epoch, some transmitters send milliseconds`). Keep that habit;
  a reader must be able to check a rule against the spec.
- **Event types are URIs; aliases are a convenience.** `SsfEventTypes` maps aliases such as
  `CaepSessionRevoked` to URIs and back; everything that takes an event type accepts both and
  resolves with `SsfEventTypes.resolve`. A new event family gets constants, aliases registered in
  the static block, and a mention in the class javadoc.
- **Strict by default, lenient by configuration.** The receiver rejects what the specification
  forbids (`invalid_request`), and tolerates older drafts or known transmitter quirks only behind
  an explicit mode (`SubjectCompatibilityMode.LEGACY`, `SsfTransmitterUriPolicy.INSECURE`), each
  documented in the README.
- **Handlers are idempotent** and a SET that any handler fails on is delivered again. State that
  must survive a restart belongs in a store interface with an in-memory and a JDBC implementation.
- **Java modules.** `core`, `test`, `receiver` and `receiver-jdbc` have a `module-info.java`; a new
  package needs an `exports` line or the dependants do not compile. Micrometer stays
  `requires static`.
- **Logging** is SLF4J with string concatenation in the receiver (the messages are built once per
  event) and named by the event alias, e.g. `CaepSessionRevoked: terminated 2 session(s) of ...`.
- No Lombok, no reflection-based mapping, no new runtime dependencies in `core` and `receiver`
  without discussion in the issue.

## Documentation that changes with the code

- `README.md` is the single user document. Its configuration table lists every Spring Boot
  property with its default; a new property, alias, subject format or handler is documented there
  in the same change.
- `CHANGELOG.md` follows Keep a Changelog. Add to the `Unreleased` section in the voice of the
  existing entries; the release workflow publishes that section as the release notes.
- `CONTRIBUTING.md` for build structure, CI and the release procedure. Do not change versions or
  the release procedure as part of a feature change.
- Module READMEs exist for the examples and the conformance tests.

## Working practice

- Every pull request refers to a GitHub issue (`Closes #N`); open one if there is none.
  Branches are named `gh-<issue>-<topic>`. Commits have a short imperative subject, a wrapped
  body that says what and why, and are signed off (`git commit -s`).
- `scratch/` is ignored by git: put plans, notes and throwaway scripts there, not into the tree.
- Do not commit, push, tag or publish unless asked. Do not run `docker compose down -v`, remove
  volumes or networks you did not create, or start the examples' Keycloak without checking for an
  existing compose project of the same name (see the examples' `AGENTS.md`).
- When a change touches verification, push handling or de-duplication, add the negative test
  (the SET that must be rejected) before the positive one, and run the full module test suite,
  not only the new test.
