# easyssf-receiver

The receiver without a framework. Frameworks (Spring Boot here, Quarkus elsewhere) wire these
classes; they must not need anything but SLF4J, Nimbus and the JDK HTTP client.

- Packages by concern: `set` (verification, de-duplication, `SsfSetProcessor` runs the
  handlers), `push` and `poll` (delivery), `stream` and `transmitter` (stream management,
  metadata, tokens), `event` (`SsfEventHandler`, `SsfEventContext`), `revocation`, `session`,
  `scim` (handlers for the common reactions), `metrics`, `http`.
- `SsfSetProcessor` hands every verified SET to every handler, catches their exceptions, and
  reports a failure so the transmitter delivers again. Handlers must therefore be idempotent, and
  the processor must never mark a SET as seen before all handlers succeeded.
- Verification is strict by default (`NimbusSsfSetVerifier`: `typ`, `iss`, `aud`, `jti`, `iat`
  with clock skew, `events`, the top-level `sub_id` of SSF 1.0). Every tolerance has a mode or
  setter and a README note. A SET that must be rejected gets a test before the feature does.
- Subject matching for session termination is in `SsfSubjectClaimsMatcher`; new identifier
  formats need a rule there and a test in `SsfSubjectClaimsMatcherTests`.
- Tests use `TestTransmitter` from `easyssf-test` for signed SETs and a JWKS endpoint; build a
  `SsfEventContext` directly for handler tests. `logback-test.xml` keeps the expected failures of
  negative tests at DEBUG; a noisy stack trace in a passing test is a sign the level is wrong.
- Micrometer is `requires static`; nothing outside `metrics` may reference it.
