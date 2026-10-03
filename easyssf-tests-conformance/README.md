# easyssf conformance tests

Runs easyssf against the [OpenID conformance suite](https://gitlab.com/openid/conformance-suite).
The module consists of

- the **receiver under test** (`org.easyssf.conformance.receiver`, `CtsApplication`): a small Spring Boot
  application that acts as the SSF receiver the suite tests with its
  `openid-ssf-receiver-caep-test-plan` and `openid-ssf-receiver-test-plan`. The suite emulates a
  transmitter and an authorization server; the application plays the stream operations the tests
  expect and accepts the events they deliver. Every test of the suite emulates a transmitter with new
  signing keys under the same issuer, so every test run gets a fresh receiver assembled from
  `easyssf-receiver` (see `ConformanceRun`): token, verifier, de-duplication and stream are per run.
- the **automated conformance tests** (`org.easyssf.conformance`, JUnit): they start the suite,
  create the test plans, run every module and assert the result the suite reports, see
  [Automated tests](#automated-tests). The receiver can also be run by hand against a suite, see
  [Manual runs](#manual-runs).

## Automated tests

The tests are tagged `conformance` and skipped by the normal build. The `conformance` profile runs
only them; a plan takes a few minutes, as each module waits for the receiver to deliver, verify and
fall idle.

```sh
# all four plans (default and CAEP interop profile, push and poll delivery)
./mvnw -pl easyssf-tests-conformance -Pconformance verify
# one plan
./mvnw -pl easyssf-tests-conformance -Pconformance verify -Dtest=CaepInterop10PushReceiverConformanceTest
```

| Test | Plan | Variant | Suite configuration |
|---|---|---|---|
| `Ssf10PushReceiverConformanceTest` | `openid-ssf-receiver-test-plan` | `ssf_delivery_mode=push` | `suite-config/receiver-ssf1.0-push.json` |
| `Ssf10PollReceiverConformanceTest` | `openid-ssf-receiver-test-plan` | `ssf_delivery_mode=poll` | `suite-config/receiver-ssf1.0-poll.json` |
| `CaepInterop10PushReceiverConformanceTest` | `openid-ssf-receiver-caep-test-plan` | `ssf_delivery_mode=push` | `suite-config/receiver-ssf1.0-caepiop1.0-push.json` |
| `CaepInterop10PollReceiverConformanceTest` | `openid-ssf-receiver-caep-test-plan` | `ssf_delivery_mode=poll` | `suite-config/receiver-ssf1.0-caepiop1.0-poll.json` |

All plans use `ssf_auth_mode=dynamic` and `client_auth_type=client_secret_basic`. A test class
creates its plan in the suite and runs one parameterized test per module of the plan, so the modules
follow the suite version in use. `ReceiverModules` maps a module to the scenario the receiver plays
(see [Scenarios](#scenarios)) and the status its run has to end with; a failed module fails with the
failures and warnings the suite logged, the receiver's log and the link to the suite's log page.

### The suite

The suite is started with [Testcontainers](https://testcontainers.com) (MongoDB, the suite and its
nginx, like `docker-compose-prebuilt.yml` of the suite's repository), so Docker is all that is
needed. The suite has to know its own URL before it starts, so nginx is bound to a fixed host port
(`https://localhost:18443`, where the suite's UI shows the plans while the tests run), and the suite
reaches the receiver on the Docker host as `host.testcontainers.internal`.

| System property / environment variable | Default | |
|---|---|---|
| `cts.suite.image` / `CTS_SUITE_IMAGE` | `registry.gitlab.com/openid/conformance-suite:latest` (the suite's current master build, see below) | the suite image |
| `cts.suite.nginx-image` | `registry.gitlab.com/openid/conformance-suite/nginx:latest` | its nginx image |
| `cts.suite.mongodb-image` | `mongo@sha256:…` (`9.0.2-noble`, pinned by digest; the suite itself runs 6.0.13 in production) | its MongoDB image |
| `cts.suite.port` | `18443` | host port of the Testcontainers-started suite |
| `cts.receiver.port` | `9444` | port of the receiver under test |
| `cts.receiver.push-url` | `https://<host as seen from the suite>:<receiver port>/ssf/push` | the push URL the receiver registers |

The properties can be given to Maven (`-Dcts.suite.image=...`), which passes them on to the tests.

**Which suite.** The tests run the suite's current `latest` build to work around issues in the SSF
tests of the last release (`release-v5.3.1`: fewer modules, and a race in its emulated transmitter
that fails most poll modules). Once a release contains the fixes, `ConformanceSettings` pins it by
digest, the single place to change the images. To run a build of your own, build the image from a
checkout like the suite's CI does (`.gitlab-ci.yml`, job `build-image`: `mvn package`, then
`docker build -t conformance-suite .`) and name it; Testcontainers uses images of the local Docker
daemon as they are:

```sh
./mvnw -pl easyssf-tests-conformance -Pconformance verify -Dcts.suite.image=conformance-suite:master-949724883
```

The nginx image is independent of the suite version; the released one does for a locally built
suite. (The registry's `latest` tag is older than `release-v5.3.1`, not a build of `master`.) The
run configuration `Conformance tests (master image)` expects such an image tagged
`conformance-suite:latest`.

## Manual runs

### Setup (once)

1. **TLS certificate for this application** — the suite only accepts `https` push endpoints:

   ```sh
   cd easyssf-tests-conformance
   mkcert -cert-file certs/localhost.pem -key-file certs/localhost-key.pem localhost 127.0.0.1 ::1 host.docker.internal
   ```

2. **Certificate of the suite** — a locally running suite (`https://localhost.emobix.co.uk:8443`)
   serves a self-signed certificate. Save it so that the receiver trusts it:

   ```sh
   ./fetch-suite-certificate.sh          # writes certs/conformance-suite.pem
   ```

   The certificate is issued for `localhost`, not `localhost.emobix.co.uk`, so
   `cts.transmitter.verify-hostname` is `false` in `application.yaml`. Against a suite with a
   valid certificate remove `cts.transmitter.ssl-bundle` and set `verify-hostname` to `true`.

3. **Test plan in the suite** — create a plan with the configuration of the variant you want to run
   (`suite-config/`), and start the application with the run configuration of the same name
   (`.run/`), which points it at the alias of that configuration:

   | Configuration | Plan | Variants | Run configuration |
   |---|---|---|---|
   | `receiver-ssf1.0-push.json` | `openid-ssf-receiver-test-plan` | `ssf_delivery_mode=push` | `CtsApplication (receiver-ssf1.0-push)` |
   | `receiver-ssf1.0-poll.json` | `openid-ssf-receiver-test-plan` | `ssf_delivery_mode=poll` | `CtsApplication (receiver-ssf1.0-poll)` |
   | `receiver-ssf1.0-caepiop1.0-push.json` | `openid-ssf-receiver-caep-test-plan` | `ssf_delivery_mode=push` | `CtsApplication (receiver-ssf1.0-caepiop1.0-push)` |
   | `receiver-ssf1.0-caepiop1.0-poll.json` | `openid-ssf-receiver-caep-test-plan` | `ssf_delivery_mode=poll` | `CtsApplication (receiver-ssf1.0-caepiop1.0-poll)` |

   All of them use `ssf_auth_mode=dynamic` and `client_auth_type=client_secret_basic`, the defaults
   of the application. The alias of a configuration (`easyssf-receiver-ssf1_0-push` etc.; the suite
   allows no dots in aliases) makes the transmitter issuer
   `https://localhost.emobix.co.uk:8443/test/a/<alias>`; the run configuration sets it as
   `CTS_TRANSMITTER_ISSUER`. (`easyssf-receiver.json` with the generic `CtsApplication (push)` /
   `(poll)` run configurations is the same thing with a single alias.) Instead of a run configuration
   per variant, a run can also name its issuer and delivery method, see
   [Running a test](#running-a-test). The variants in detail:

   | Variant | Value | `application.yaml` |
   |---|---|---|
   | SSF Delivery Mode | `push` or `poll` | `cts.delivery.method` |
   | Authentication Variant | `dynamic` (client credentials) or `static` | `cts.auth.mode`, `cts.auth.access-token` |
   | Client Authentication Type | `client_secret_basic` or `client_secret_post` | `cts.auth.client-authentication-method` |
   | SSF Profile | `caep_interop` or `default` | see scenarios |

### Running a test

```sh
./mvnw -pl easyssf-tests-conformance spring-boot:run
# or: CTS_DELIVERY=poll ./mvnw -pl easyssf-tests-conformance spring-boot:run
```

In IntelliJ IDEA use the run configurations from the `.run` folder of the repository, see the table
above.

1. Start the test module in the suite; it waits for the receiver.
2. Start the run the module expects: `curl -sk -X POST https://localhost:9443/cts/runs/auto`. The
   application asks the suite (`/api/runner/running`, `/api/info`) which module waits for the
   receiver, and starts the scenario of that module (see [Scenarios](#scenarios)) against the
   module's test instance with the delivery method of its variant. Modules running under the
   configured alias come first; without one, any waiting module of the suite is taken. The
   response names the module and the run; with no waiting module it is a `404`, with several a
   `409` that lists them (`?issuer=https://localhost.emobix.co.uk:8443/test/a/<alias>` picks one).
   `curl -sk https://localhost:9443/cts/suite/modules | jq` shows what runs in the suite and the
   scenario each module would get.

   To choose the scenario yourself:
   `curl -sk -X POST 'https://localhost:9443/cts/runs?scenario=caep-interop'` — with
   `&issuer=https://localhost.emobix.co.uk:8443/test/a/<alias>&delivery=poll` the run targets another
   test instance or delivery method than configured.
3. Watch it: `curl -sk https://localhost:9443/cts/runs/current | jq` — the run logs every step and
   every SET, and ends by deleting the stream once no SET arrived for `cts.run.idle-timeout`
   (30 seconds, long enough for the pauses the tests make).

Starting a run stops the previous one. `POST /cts/runs/current/stop` ends a run early.

### Running a plan module by module

[`run-plan.sh`](run-plan.sh) does steps 1 to 3 for every module of a plan, waiting for a key press
before each (Enter runs the module, `s` skips it, `q` quits, `a` continues automatically from here
on with a pause of a few seconds between the modules, during which a key press still counts): it
starts the module in the suite,
calls `POST /cts/runs/auto` on the receiver, waits for the module to end and prints its result with
the failures and warnings the suite logged, and a summary at the end. It needs `curl` and `jq`.

```sh
./run-plan.sh                                   # the plan of the module running in the suite
./run-plan.sh sqqbZNqAZRQbv                     # a plan id, or its plan-detail URL
./run-plan.sh -c suite-config/receiver-ssf1.0-caepiop1.0-push.json   # a new plan from a configuration
./run-plan.sh -a 10 <plan>                      # continue automatically after 10 seconds, unless a key is pressed
./run-plan.sh -m verification -y <plan>         # only matching modules, without any pause
```

The suite defaults to `https://localhost.emobix.co.uk:8443` and the receiver to
`https://localhost:9443` (`-s`, `-r`, or `CTS_SUITE_URL`, `CTS_RECEIVER_URL`).

### Scenarios

| Scenario | What the receiver does | Test modules |
|---|---|---|
| `caep-interop` (default) | create stream, check its issuer, read configuration and status, verify (rejecting a wrong state or stream and requesting again), accept events, delete | all modules of the CAEP interop plan; in the default plan everything except the three below |
| `create-delete` | create and delete only | `stream-create-delete` (faster than `caep-interop`, which also passes) |
| `stream-management` | as `caep-interop`, plus update and replace the stream | `happypath` (default plan) |
| `status-update` | as `caep-interop`, plus pause and enable the stream after the verification | `stream-status-update` (default plan) |
| `remove-subject` | as `caep-interop`, plus remove `cts.stream.subject-to-remove` after the verification | `removed-subject-event` (default plan) |

The `stream-issuer-mismatch` module passes with any scenario: the receiver refuses the stream the
suite created with a foreign `iss`, deletes it and ends the run as `REFUSED_STREAM`. Which module
gets which scenario is defined in `ConformanceScenario.forModule`, used by `POST /cts/runs/auto` and
by the automated tests alike.

## Endpoints

| | |
|---|---|
| `POST /cts/runs/auto?issuer=…` | starts the run the test module waiting in the suite expects; `issuer` names a test instance of the suite, default `cts.transmitter.issuer` |
| `GET /cts/suite/modules?issuer=…` | the test modules running in the suite of that test instance, with the scenario the receiver plays for each |
| `POST /cts/runs?scenario=…&issuer=…&delivery=…` | starts a run; `issuer` and `delivery` default to `cts.transmitter.issuer` and `cts.delivery.method` |
| `GET /cts/runs/current` | the current run: status, stream, log, received SETs |
| `POST /cts/runs/current/stop` | stops the current run and deletes its stream |
| `POST /ssf/push` | the push endpoint the suite delivers to; each run expects its own `Authorization` header, registered with the stream |
