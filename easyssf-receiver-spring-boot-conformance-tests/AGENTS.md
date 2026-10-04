# easyssf-receiver-spring-boot-conformance-tests

The receiver under test (`CtsApplication`) and the four tests that run the OpenID conformance
suite's receiver plans against it. The harness itself (suite containers, plans, scenarios) is
`easyssf-test-conformance`; this module only extends its plan tests. See the module README for
the plans, the suite images and manual runs.

- The tests are tagged `conformance` and skipped by the normal build. They need Docker, bind the
  suite to `https://localhost:18443`, and take minutes per plan:
  `./mvnw -pl easyssf-receiver-spring-boot-conformance-tests -Pconformance verify
  -Dtest=<PlanTest>`. Do not run them as part of an ordinary change; run the plan that covers the
  behaviour you changed and report the suite's result, not only the JUnit outcome.
- The suite images are named in one place, `ConformanceSettings` in `easyssf-test-conformance`.
  The tests currently run the suite's `latest` build because the last release has a poll race and
  fewer SSF modules; do not pin a release without checking that it contains the fixes.
- A module that fails prints the suite's failures and warnings, the receiver's log and a link to
  the suite's log page. Start from the suite log, the receiver is usually right.
- IntelliJ run configurations for the plans and for `CtsApplication` are in `.run/`.
