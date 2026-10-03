# easyssf

Building blocks for the
[OpenID Shared Signals Framework (SSF)](https://openid.net/specs/openid-sharedsignals-framework-1_0.html)
in Java: a framework independent **receiver** library and a **Spring Boot starter** that turns a
Spring Boot application into an SSF receiver. Inspired by the Quarkus extension
[quarkus-openid-ssf](https://github.com/quarkiverse/quarkus-openid-ssf).

**Turn an ordinary Java or Spring application into an SSF receiver without implementing SET
validation, metadata discovery, PUSH and POLL delivery, stream management and replay protection
yourself.** With the starter, reacting to a security event is a bean and one property:

```java
@Bean
SsfEventHandler securityEvents() {
    return (event) -> {
        if (event.hasEvent("CaepSessionRevoked")) {
            SsfSubject subject = event.subjectFor("CaepSessionRevoked");
            // end what you hold for subject.sessionId() or subject.subject()
        }
    };
}
```

```yaml
easyssf:
  receiver:
    transmitter-issuer: https://idp.example/realms/demo
```

Resource servers and OIDC clients do not even need the bean: with Spring Security on the classpath,
access tokens of a revoked session are rejected and local sessions are ended as the events arrive.
The receiver is tested against the OpenID conformance suite's SSF and CAEP receiver test plans, with
PUSH and POLL delivery.

| | |
|---|---|
| **Status** | Experimental |
| **Java** | 21+ |
| **Group id** | `org.easyssf` |

| Module | | Depends on |
|---|---|---|
| `easyssf-core` | The data structures of SSF shared by receivers and (later) transmitters: SETs, subjects, event types, stream configuration, transmitter metadata. | nothing |
| `easyssf-receiver` | The receiver, independent of any framework: SET verification, de-duplication, event handlers, push handling, polling, stream management, token revocation and session termination logic. | `easyssf-core`, Nimbus JOSE + JWT, SLF4J |
| `easyssf-receiver-jdbc` | The database-backed stores of the receiver (processed SETs, revocations), independent of any framework: the SQL, the schema and a small `SsfJdbcOperations` interface, implemented over a `DataSource` or by a framework's template. | `easyssf-receiver` |
| `easyssf-receiver-spring-boot-starter` | The receiver for Spring Boot 4.1 (Spring Security 7.1, servlet stack): configuration properties, auto-configuration, push endpoint, resource server and OIDC client integration. | `easyssf-receiver`, Spring Boot |
| `easyssf-test` | Test support: a transmitter that signs and delivers SETs, for the tests of your receiver, see [Testing your receiver](#testing-your-receiver). | `easyssf-core`, Nimbus JOSE + JWT |
| [`easyssf-receiver-spring-boot-examples`](easyssf-receiver-spring-boot-examples) | Example resource server and OIDC client with a Keycloak setup. | |
| `easyssf-test-conformance` | Runs the OpenID conformance suite's SSF receiver test plans against a receiver under test, in any framework: the suite started with Testcontainers, the scenarios the receiver plays, and the plan tests a framework's test class extends. | `easyssf-receiver`, Testcontainers, JUnit |
| [`easyssf-receiver-spring-boot-conformance-tests`](easyssf-receiver-spring-boot-conformance-tests) | The Spring Boot receiver under test and the four plan tests for it. | |

Most of this document describes the Spring Boot starter. For other environments see
[Using the receiver without Spring Boot](#using-the-receiver-without-spring-boot).

## What it does

- Mounts a **push endpoint** (RFC 8935) on a configurable route, `/ssf/push` by default, or
  **polls** the transmitter for events (RFC 8936).
- **Verifies every SET** (RFC 8417): signature against the transmitter's JWK Set, `typ`, `iss`,
  `aud`, `jti`, `iat` and `events`. The JWK Set location is discovered from the transmitter's
  `.well-known/ssf-configuration`.
- Skips SETs it has already processed (`jti` de-duplication).
- Hands verified events to your `SsfEventHandler` beans.
- **Manages its stream** at the transmitter if you want: looks it up on startup and creates or updates
  it, and offers an `SsfStreamClient` for the stream management API.
- Records **metrics** with Micrometer.
- **Resource server**: rejects access tokens once their session (or user) was revoked by a CAEP
  `session-revoked` event.
- **OIDC client**: terminates local sessions on CAEP `session-revoked` (by `sid` or user) and
  `credential-change` (all sessions of the user).

## Getting started

```xml
<dependency>
    <groupId>org.easyssf</groupId>
    <artifactId>easyssf-receiver-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

The releases are on Maven Central. Snapshots of `main` are on the
[Central snapshot repository](https://central.sonatype.com/repository/maven-snapshots/org/easyssf/),
which a build has to enable with `<snapshots><enabled>true</enabled></snapshots>`.

```yaml
easyssf:
  receiver:
    transmitter-issuer: https://idp.example/realms/demo   # required
    expected-audience: https://my-app.example             # recommended
    push:
      expected-auth-header: Bearer ${SSF_PUSH_SECRET}     # recommended
```

Then create a stream with PUSH delivery at the transmitter that points to
`https://my-app.example/ssf/push` and sends the configured `Authorization` header, or let the
application [create the stream itself](#stream-management).

The application starts even if the transmitter is not reachable: metadata and keys are fetched when
the first SET arrives.

The [examples](easyssf-receiver-spring-boot-examples) contain a resource server and an OIDC client that run against a Keycloak
with a pre-configured realm.

## Use case: resource server

With `spring-boot-starter-security-oauth2-resource-server` on the classpath, nothing else is needed:

| Event | Subject of the event | Effect |
|---|---|---|
| `session-revoked` | names a session (`complex` subject with a `session` member) | access tokens with that `sid` claim are rejected |
| `session-revoked` | names only a user (`iss_sub`) | access tokens with that `sub` claim issued up to the time of the event are rejected |

Rejected tokens get the usual `401` with `WWW-Authenticate: Bearer error="invalid_token"`.

How it works: `SsfTokenRevocationEventHandler` records revocations in the `SsfTokenRevocationStore`,
and `SsfRevokedTokenValidator` (an `OAuth2TokenValidator<Jwt>`) consults it for every request. Spring
Boot adds that validator to the `JwtDecoder` it auto-configures.

Things to know:

- **Custom `JwtDecoder`**: if you define your own `JwtDecoder` bean, add the `SsfRevokedTokenValidator`
  bean to its validators yourself.
- **Multiple instances**: a SET is pushed to one instance only. Without a database the revocations
  are kept in memory of that instance. If the application has a database they are kept there, see
  [Keeping state in the database](#keeping-state-in-the-database). For any other shared store
  (Redis, ...) provide a `SsfTokenRevocationStore` bean.
- **`easyssf.receiver.resource-server.revocation-ttl`** (default `1h`) must be at least the maximum
  lifetime of your access tokens. Revocations are forgotten after that time.
- **Issuers**: a revocation applies to the tokens of one issuer: the `iss` of the event's `iss_sub`
  identifier, for a session the issuer of the user it belongs to, else the transmitter. It is matched
  against the `iss` claim of the access token, so the transmitter must be the issuer of the tokens, or
  name it in its events.
- Opaque tokens are not covered, introspection already asks the authorization server.

## Use case: OIDC client

With `spring-boot-starter-security-oauth2-client` on the classpath and users logging in with
`oauth2Login()`, nothing else is needed:

| Event | Subject of the event | Effect |
|---|---|---|
| `session-revoked` | names a session | the local session whose ID token has that `sid` is invalidated |
| `session-revoked` | names only a user (`iss_sub` or `email`) | all local sessions of that user are invalidated |
| `credential-change` | names a user | all local sessions of that user are invalidated |

How it works: `HttpSessionSsfSessionTerminator` tracks the live `HttpSession`s of the servlet container
and invalidates those whose authenticated `OidcUser` matches the subject of the event (`sid`, `sub` +
`iss`, or `email`).

Things to know:

- **Spring Session / multiple instances**: the default only sees the container sessions of the
  instance that received the SET. Provide your own `SsfSessionTerminator` bean for an external session
  store.
- **Matching**: a session by its `sid`, and by the issuer of its user if the event names one; a user by
  `iss` and `sub` of an `iss_sub` identifier (both have to match), by `email` (case-insensitive) or by
  `phone_number`; `aliases` match if any of their identifiers does. Matching can be customized with a
  `SsfSessionMatcher` bean.
- `credential-change` terminates sessions for every kind of change (including a newly added
  credential). To be more selective, set `easyssf.receiver.oidc-client.user-event-types` to an empty list
  and call `SsfSessionTerminator` from your own handler.

## Handling events yourself

Every `SsfEventHandler` bean is invoked for every verified SET:

```java
@Component
class StepUpHandler implements SsfEventHandler {

    @Override
    public void handle(SsfEventContext eventContext) {
        // aliases and full event type URIs are both accepted, see SsfEventTypes
        if (eventContext.hasEvent("CaepAssuranceLevelChange")) {
            SsfSubject subject = eventContext.subjectFor("CaepAssuranceLevelChange");
            Map<String, Object> event = eventContext.eventFor("CaepAssuranceLevelChange");
            // subject.subject(), subject.sessionId(), subject.email(), subject.raw() ...
        }
        SsfEventToken token = eventContext.eventToken(); // jti, iss, iat, aud, events, subjectId, txn, claims
    }

}
```

Handlers must be idempotent. If a handler throws, the SET is not acknowledged and the transmitter is
expected to deliver it again, in which case all handlers run again.

`SsfSubject` is the `sub_id` of the SET: a simple subject identifier (RFC 9493: `iss_sub`, `email`,
`opaque`, `account`, `phone_number`, `did`, `uri`, `aliases`) or a complex subject whose members
(`user`, `session`, `device`, `tenant`, `application`, `org_unit`, `group`) are identifiers.
`subject.userIdentifier()` and `subject.session()` are the members the receiver acts on,
`subject.member("device")` gives the others, and `subject.raw()` the claim as received. Each
`SsfSubjectIdentifier` has a `format()`, a `value()` (the `sub`, `email`, `id`, ... of its format) and
its `claims()`. The shortcuts `subject()`, `sessionId()`, `email()` and `opaqueId()` cover the common
cases.

Event types are identified by their URIs; the aliases (`CaepSessionRevoked`, ...) are a convenience
of easyssf, use the URIs where you persist event types. Your own aliases, for vendor specific event
types, go into `easyssf.receiver.event-aliases` (or `SsfEventTypes.registerAlias`) and work wherever
an event type is named; they cannot redefine a built-in alias. `eventTimestamp()` returns the
`event_timestamp` of the event, which CAEP defines in seconds; a value that is clearly milliseconds is
accepted as well, as some transmitters send that.

**Critical subject members**: a transmitter can declare in its metadata (`critical_subject_members`)
members of a complex subject that a receiver must interpret. A SET whose subject has such a member
is rejected (`invalid_request`) unless the member is one the application understands. By default
these are the members `SsfSubject` gives access to: `user`, `session`, `device`, `application`,
`tenant`, `org_unit` and `group`. If the application interprets others, or fewer, list them in
`easyssf.receiver.understood-subject-members` (or `SsfSetProcessor.setUnderstoodSubjectMembers`).

## Push endpoint

```yaml
easyssf.receiver.push.endpoint-path: /ssf/push   # relative to the DispatcherServlet
```

A SET is verified and handled *before* it is acknowledged:

| Condition | Response |
|---|---|
| SET verified and handled, or a duplicate | `202 Accepted` |
| `expected-auth-header` configured and `Authorization` header does not match | `401` with `{"err":"authentication_failed"}` |
| SET malformed, badly signed, not typed `secevent+jwt`, missing claims | `400` with `{"err":"invalid_request"}` |
| wrong `iss` / `aud` | `400` with `{"err":"invalid_issuer"}` / `{"err":"invalid_audience"}` |
| transmitter metadata or keys cannot be retrieved | `503`, the transmitter should retry |
| an `SsfEventHandler` failed | `500`, the transmitter should retry |
| not a `POST` | `405` |

### Spring Security

The transmitter has no session, CSRF token or access token for your application. The starter
therefore registers a dedicated `SecurityFilterChain` for the push endpoint path only (stateless, no
CSRF, `permitAll`); it takes precedence over your own filter chains and does not replace Spring Boot's
default security. The SET itself is authenticated by its signature and, if configured, the
`Authorization` header.

Set `easyssf.receiver.push.security.enabled=false` to secure the endpoint in your own filter chain
instead.

## POLL delivery

With `easyssf.receiver.delivery-method=poll` the application has no push endpoint. Instead the `SsfPoller`
fetches SETs from the poll endpoint of the stream (RFC 8936):

```yaml
easyssf:
  receiver:
    delivery-method: poll
    stream:
      id: 7d9a4e13-…            # a stream created at the transmitter, or management: receiver
    poll:
      interval: 30s
    oauth2: …                    # see "Calling the transmitter"
```

- The poll endpoint is taken from the stream (`stream.id` or a stream managed by the receiver), or set
  it with `easyssf.receiver.poll.endpoint-url`.
- A SET is acknowledged after it was handled. An invalid SET is reported to the transmitter (`setErrs`).
  A SET that could not be handled is neither acknowledged nor reported and is delivered again.
- If more SETs are available than `poll.max-events`, they are fetched right away.
- `429` / `503` responses with a `Retry-After` header (in seconds) pause the polling, for at most
  `easyssf.receiver.poll.rate-limit.max-backoff` (5 minutes). A `429` without the header pauses for
  `easyssf.receiver.poll.rate-limit.fallback-backoff`, if set.
- With `easyssf.receiver.poll.auto-startup=false` nothing is polled until you call `SsfPoller.pollNow()`.
- Long polling is not used, requests always ask the transmitter to return immediately.

## Stream management

By default the stream is created at the transmitter (`easyssf.receiver.stream.management=transmitter`).
If you configure its identifier with `easyssf.receiver.stream.id`, the application looks the stream up on
startup to learn its audience and poll endpoint.

With `easyssf.receiver.stream.management=receiver` the application manages the stream itself:

```yaml
easyssf:
  receiver:
    stream:
      management: receiver
      events-requested: CaepSessionRevoked, CaepCredentialChange
    push:
      # how the transmitter reaches the push endpoint (not needed for delivery-method: poll)
      delivery-endpoint-url: https://my-app.example/ssf/push
      expected-auth-header: Bearer ${SSF_PUSH_SECRET}   # the transmitter is told to send it
    oauth2: …                                           # see "Calling the transmitter"
```

On startup the `SsfStreamRegistrar`

1. reuses the stream of the receiver that has the desired delivery (same method, for PUSH the same
   endpoint), and updates its requested events if they differ,
2. otherwise creates the stream,
3. and if the transmitter refuses a second stream (`409`, Keycloak allows one per receiver), changes
   the existing one to the desired delivery.

This happens in the background and is retried with a growing delay (up to 30s), a transmitter that
is down does not keep the application from starting. As long as `easyssf.receiver.expected-audience` is
not set, the audience of the stream is used to validate SETs once the stream is known.
`easyssf.receiver.stream.delete-on-shutdown=true` deletes the stream when the application stops.

The `SsfStreamClient` bean gives access to the whole stream management API (SSF 1.0, section 8.1):

```java
streamClient.getStreams();
streamClient.updateStatus(streamId, SsfStreamStatus.PAUSED, "maintenance");
streamClient.addSubject(streamId, Map.of("format", "email", "email", "user@example.com"), true);
streamClient.requestVerification(streamId, "my-state");
```

The identifier of the current stream is available from the `SsfReceiverStream` bean. To verify the
stream, request the verification through the `SsfStreamVerification` bean: it generates the `state`
and rejects a verification event that echoes another state, or names another stream, with
`invalid_state` / `invalid_request`:

```java
streamVerification.requestVerification(streamClient, receiverStream.getStreamId());
```

## Several transmitters

The settings at `easyssf.receiver.*` implicitly configure one transmitter, named `default`. An application that
receives events from several transmitters, several identity providers, or OAuth authorizationsServers for instance, 
configures the others under `easyssf.receiver.transmitters.<name>.*`, each with the same settings a transmitter has
(issuer, metadata and JWK Set URLs, audience, delivery method, push header, poll, stream, token
credentials, SET validation) and each complete on its own, nothing is inherited from the default one:

```yaml
easyssf:
  receiver:
    transmitter-issuer: https://idp.example/realms/employees       # the default transmitter, push
    push:
      expected-auth-header: Bearer ${EMPLOYEES_PUSH_SECRET}
    transmitters:
      customers:                                                   # a second one, polled
        transmitter-issuer: https://idp.example/realms/customers
        delivery-method: poll
        stream:
          management: receiver
        oauth2:
          token-uri: https://idp.example/realms/customers/protocol/openid-connect/token
          client-id: my-receiver
          client-secret: ${CUSTOMERS_RECEIVER_CLIENT_SECRET}
```

- The `iss` claim of a SET selects the transmitter that verifies it, so one push endpoint serves all
  of them; each transmitter authenticates with its own `expected-auth-header`. Polled transmitters
  are polled independently.
- The beans (`SsfStreamClient`, `SsfReceiverStream`, `SsfPoller`, ...) belong to the default
  transmitter, so replacing them works as before. The `SsfTransmitters` bean holds every transmitter
  by name and by issuer, with its stream client, stream, registrar and poller; `SsfTransmitterCustomizer`
  beans customize transmitters before they are built. Only named transmitters, without a default one,
  are fine as well.
- Handlers see the issuer in `eventContext.eventToken().iss()`. Revocations and sessions are scoped
  to the issuer anyway. The metrics carry a `transmitter` tag with the name of the transmitter, and
  the health indicator lists every transmitter under its name.
- Dedup, the database, metrics, HTTP timeouts, the push endpoint path and the resource server and
  OIDC client integrations are shared by all transmitters.

## Keeping state in the database

By default the receiver remembers in memory which SETs it processed and which sessions and subjects
were revoked. That state is lost on restart and not shared between instances.

If the application has a `JdbcTemplate` (with `spring-boot-starter-jdbc`, Spring Data JDBC or JPA),
the state is kept in its database instead, without further configuration:

| Table | Store | Content |
|---|---|---|
| `EASYSSF_PROCESSED_SET` | `JdbcSsfJtiDedupStore` | the SETs that were processed, forgotten after `easyssf.receiver.dedup.retention` (7 days) |
| `EASYSSF_REVOCATION` | `JdbcSsfTokenRevocationStore` | revoked sessions and subjects, per issuer, removed after `easyssf.receiver.resource-server.revocation-ttl` |

- **Tables**: in an embedded database (H2, HSQLDB, Derby) the tables are created on startup. For any
  other database create them with your migration tool, the statements are in
  [`schema.sql`](easyssf-receiver-jdbc/src/main/resources/org/easyssf/receiver/jdbc/schema.sql)
  (`classpath:org/easyssf/receiver/jdbc/schema.sql`), or set
  `easyssf.receiver.jdbc.initialize-schema=always`. If a table is missing the application fails on
  startup and says so, rather than on the first event. The stores use plain SQL (`VARCHAR`,
  `BIGINT`, no vendor syntax) and are tested on H2 and PostgreSQL.
- **Opting out**: `easyssf.receiver.jdbc.enabled=false` keeps the state in memory although the
  application has a database. Your own `SsfJtiDedupStore` or `SsfTokenRevocationStore` bean takes
  precedence in any case.
- **Cleanup**: expired rows are deleted when a store is written to (the SET table at most once a
  minute) and every `easyssf.receiver.jdbc.cleanup-interval` (15 minutes) by `JdbcSsfStoreCleanup`
  on a thread of its own; `easyssf.receiver.jdbc.cleanup-interval=0` turns the periodic cleanup off.
  Both stores offer `purgeExpired()` to do it from your own scheduler.
- **Cost**: a resource server checks every access token with one query on the primary key of
  `EASYSSF_REVOCATION`.
- The stores live in `easyssf-receiver-jdbc` and have no Spring dependency: `SsfJdbcOperations` runs
  their SQL, over a `DataSource` (`DataSourceSsfJdbcOperations`) or, in Spring Boot, the `JdbcTemplate`
  of the application, so its transactions and exception translation apply.
- The stores use plain SQL (timestamps are stored as milliseconds since the epoch) and were tested
  with H2 and PostgreSQL. The revocation table is only used by resource servers. Sessions of an
  OIDC client are not affected, see `SsfSessionTerminator`.

## Calling the transmitter

Stream management and polling call the transmitter and usually need an access token:

```yaml
easyssf:
  receiver:
    oauth2:                                   # client credentials grant
      token-uri: https://idp.example/realms/demo/protocol/openid-connect/token
      client-id: my-receiver
      client-secret: ${SSF_RECEIVER_CLIENT_SECRET}
      scopes: ssf.read, ssf.manage
    # or, for transmitters that hand out long-lived tokens:
    # transmitter-access-token: ${SSF_TRANSMITTER_TOKEN}
```

For anything else provide a `SsfTransmitterTokenProvider` bean. Metadata and JWK Set are fetched
without authentication.

All calls to the transmitter go through the `SsfHttpClient` bean:

- If the application has a `RestClient.Builder` (`spring-boot-starter-restclient`), the calls are
  made with a `RestClient` built from it. They then use the HTTP client library, the
  `spring.http.clients.*` settings (SSL bundle, redirects) and the `RestClientCustomizer`s of the
  application, and are observed like its other HTTP calls (`http.client.requests` metric, tracing).
  `easyssf.receiver.http.use-rest-client=false` switches this off.
- Otherwise the HTTP client of the JDK is used.
- Timeouts: `easyssf.receiver.http.connect-timeout` / `read-timeout` if set, else
  `spring.http.clients.connect-timeout` / `read-timeout` (for the `RestClient`), else 5 seconds.
- Provide your own `SsfHttpClient` bean for anything else.

## Metrics

If the application has a Micrometer `MeterRegistry` (for example with
`spring-boot-starter-actuator`), the receiver records:

| Meter | Type | Tags |
|---|---|---|
| `easyssf.receiver.sets` | counter | `transmitter` (the name of the transmitter, `default` for the one at `easyssf.receiver.*`), `delivery` (`push`, `poll`), `outcome` (`handled`, `duplicate`, `invalid`, `unauthenticated`, `unavailable`, `failed`) |
| `easyssf.receiver.events` | counter | `transmitter`, `delivery`, `event` (for example `CaepSessionRevoked`) |
| `easyssf.receiver.poll` | timer | `transmitter`, `outcome` (`success`, `failure`) |

Switch it off with `easyssf.receiver.metrics.enabled=false`, or provide your own `SsfReceiverMetrics` bean.

## Health

With Spring Boot Actuator the receiver contributes the health indicator `easyssf`
(`/actuator/health/easyssf`), from what it already knows; it never calls the transmitter for it:

| Status | When |
|---|---|
| `UP` | The transmitter was reached: its metadata was retrieved (PUSH) or the last poll succeeded (POLL), and the stream, if it is looked up or managed, is registered. |
| `DOWN` | The last poll failed, or the stream cannot be used (it was created with another issuer). |
| `UNKNOWN` | No contact with the transmitter yet. The application starts without it. |

The details name the transmitter, the delivery method, whether the metadata was retrieved, the stream and
its registration state, and for POLL the last poll, the last successful poll and the error of the last
poll. With [several transmitters](#several-transmitters) the details of each are listed under its
name, with its own status. Switch it off with `management.health.easyssf.enabled=false`.

## Configuration

| Property | Default | |
|---|---|---|
| `easyssf.receiver.enabled` | `true` | Switches the receiver off entirely when `false`. |
| `easyssf.receiver.transmitter-issuer` | | Issuer of the transmitter, must match `iss` of every SET. An `https` URL without query or fragment; `http` only on loopback addresses or with `allow-insecure-http`. Required unless transmitters are configured by name. |
| `easyssf.receiver.transmitters.<name>.*` | | Further transmitters, see [Several transmitters](#several-transmitters): every property of a transmitter, from `transmitter-issuer` to `set-validation.*`, under its name. |
| `easyssf.receiver.transmitter-metadata-url` | derived | By default `<host>/.well-known/ssf-configuration<issuer-path>` (SSF 1.0, section 7.2), falling back to `<issuer>/.well-known/ssf-configuration`. |
| `easyssf.receiver.transmitter-jwks-url` | from metadata | Skips metadata discovery when set. |
| `easyssf.receiver.allow-insecure-http` | `false` | Accepts `http` for the transmitter issuer and the endpoints it publishes on any host, with a warning on startup. For development only. |
| `easyssf.receiver.expected-audience` | | When set, every SET must contain it in `aud`. |
| `easyssf.receiver.delivery-method` | `push` | `push` or `poll`. |
| `easyssf.receiver.understood-subject-members` | `user, session, device, application, tenant, org_unit, group` | The members of a complex subject the application interprets; a SET with a member the transmitter declared critical that is not listed is rejected. |
| `easyssf.receiver.event-aliases.*` | | Aliases for event type URIs, e.g. `AcmeLogin: https://events.acme.example/login`, usable wherever an event type is named. |
| `easyssf.receiver.http.use-rest-client` | `true` | Call the transmitter with the `RestClient` of the application if it has a `RestClient.Builder`. |
| `easyssf.receiver.http.connect-timeout` / `read-timeout` | `5s` | Calls to the transmitter. Unset, `spring.http.clients.*` applies to the `RestClient` before the 5 seconds do. |
| `easyssf.receiver.http.user-agent` | default of the HTTP client | `User-Agent` header for calls to the transmitter. |
| `easyssf.receiver.transmitter-access-token` | | Static access token for stream management and polling. |
| `easyssf.receiver.oauth2.token-uri` | | Token endpoint for the client credentials grant. |
| `easyssf.receiver.oauth2.client-id` / `client-secret` | | |
| `easyssf.receiver.oauth2.scopes` | | |
| `easyssf.receiver.oauth2.client-authentication-method` | `basic` | `basic` or `post`. |
| `easyssf.receiver.oauth2.expiry-safety-window` | `30s` | How long before its expiry an access token is renewed, at most a quarter of its lifetime. |
| `easyssf.receiver.oauth2.additional-parameters.*` | | Form parameters sent with the token request in addition to the grant. |
| `easyssf.receiver.stream.management` | `transmitter` | `receiver` makes the application create or update its stream on startup. |
| `easyssf.receiver.stream.id` | | Stream created at the transmitter, looked up on startup. |
| `easyssf.receiver.stream.events-requested` | `CaepSessionRevoked`, `CaepCredentialChange` | For a stream managed by the receiver. |
| `easyssf.receiver.stream.description` | | For a stream managed by the receiver. |
| `easyssf.receiver.stream.delete-on-shutdown` | `false` | For a stream managed by the receiver. |
| `easyssf.receiver.poll.endpoint-url` | from the stream | |
| `easyssf.receiver.poll.auto-startup` | `true` | |
| `easyssf.receiver.poll.interval` | `30s` | |
| `easyssf.receiver.poll.initial-delay` | `1s` | |
| `easyssf.receiver.poll.max-events` | `100` | SETs per request. |
| `easyssf.receiver.poll.rate-limit.fallback-backoff` | | Pause after a `429` without `Retry-After`; unset, the next poll comes at the regular interval. |
| `easyssf.receiver.poll.rate-limit.max-backoff` | `5m` | Longest pause a `Retry-After` header or the fallback can cause. |
| `easyssf.receiver.metrics.enabled` | `true` | |
| `easyssf.receiver.set-validation.accepted-algorithms` | `RS256` | JWS algorithms accepted for SETs. |
| `easyssf.receiver.set-validation.min-rsa-key-size` | `2048` | `0` disables the check. |
| `easyssf.receiver.set-validation.require-type-header` | `true` | Requires `typ: secevent+jwt`. |
| `easyssf.receiver.set-validation.subject-compatibility` | `strict-ssf-1-0` | Requires the top-level `sub_id` claim of SSF 1.0. `legacy` accepts SETs of transmitters following earlier drafts, which put the subject into the event. |
| `easyssf.receiver.set-validation.clock-skew` | `60s` | Tolerance for `iat` in the future. |
| `easyssf.receiver.push.enabled` | `true` | |
| `easyssf.receiver.push.endpoint-path` | `/ssf/push` | |
| `easyssf.receiver.push.expected-auth-header` | | Exact `Authorization` header value the transmitter must send. |
| `easyssf.receiver.push.delivery-endpoint-url` | | How the transmitter reaches the push endpoint, for a stream managed by the receiver. |
| `easyssf.receiver.push.security.enabled` | `true` | Dedicated `SecurityFilterChain` for the push endpoint. |
| `easyssf.receiver.dedup.enabled` | `true` | |
| `easyssf.receiver.dedup.capacity` | `10000` | Size of the in-memory `jti` store. |
| `easyssf.receiver.dedup.retention` | `7d` | How long the JDBC store remembers a processed SET. |
| `easyssf.receiver.jdbc.enabled` | `true` | Keep state in the database if the application has a `JdbcTemplate`. |
| `easyssf.receiver.jdbc.initialize-schema` | `embedded` | When to create missing tables: `embedded`, `always` or `never`. |
| `easyssf.receiver.jdbc.table-prefix` | `EASYSSF_` | Prefix of the table names. |
| `easyssf.receiver.jdbc.cleanup-interval` | `15m` | How often expired rows are purged; `0` turns it off. |
| `easyssf.receiver.resource-server.enabled` | `true` | |
| `easyssf.receiver.resource-server.event-types` | `CaepSessionRevoked` | Events that revoke access tokens. |
| `easyssf.receiver.resource-server.revocation-ttl` | `1h` | |
| `easyssf.receiver.oidc-client.enabled` | `true` | |
| `easyssf.receiver.oidc-client.session-event-types` | `CaepSessionRevoked` | Events that terminate the session (or user sessions) of their subject. |
| `easyssf.receiver.oidc-client.user-event-types` | `CaepCredentialChange` | Events that terminate all sessions of their subject's user. |

Beans you can replace: `SsfSetVerifier`, `SsfTransmitterMetadataResolver`, `SsfJtiDedupStore`,
`SsfTokenRevocationStore`, `SsfRevokedTokenValidator`, `SsfSessionTerminator`, `SsfSessionMatcher`,
`SsfTransmitterTokenProvider`, `SsfStreamClient`, `SsfStreamRegistrar`, `SsfPoller`, `SsfReceiverMetrics`,
`SsfHttpClient`, `SsfPushHandler`.

## Keycloak

Tested with the SSF transmitter of Keycloak 26.8 (`--features=ssf`), see the [examples](easyssf-receiver-spring-boot-examples).

- Keycloak publishes its metadata at both locations the starter looks at, no
  `transmitter-metadata-url` is needed.
- A receiver is a client in Keycloak. The audience of its SETs is `<client-id>/<stream-id>` unless
  the stream is given an explicit audience.
- Stream management and polling need an access token of the service account of that client with the
  scopes `ssf.read` and `ssf.manage` (optional client scopes of the client). Keycloak allows one
  stream per receiver.
- The client attribute `ssf.defaultSubjects` decides which users a stream covers by default: `ALL` for
  every user, `NONE` for none. With `NONE` the receiver is still notified about a user once the
  subject was added to the stream, with `SsfStreamClient.addSubject(...)` or in Keycloak's
  administration console.
- When an admin signs a user out of all sessions, Keycloak sends a `session-revoked` event whose
  subject has a `session` member with the identifier `ALL`. The starter treats this like a subject
  that names only the user (`SsfSubject.sessionId()` is `null`).
- Keycloak delivers events with its outbox drainer, by default every 30 seconds
  (`--spi-ssf-transmitter--default--outbox-drainer-interval`).

## Not included (yet)

- WebFlux applications
- Long polling, and a durable store for acknowledgements that were not sent yet (a SET whose
  acknowledgement is lost is delivered again and skipped as a duplicate)

## Using the receiver without Spring Boot

`easyssf-receiver` has no framework dependencies. Assemble the parts you need and call them from
your framework. `easyssf-core`, `easyssf-receiver`, `easyssf-receiver-jdbc` and `easyssf-test` are
Java modules (`org.easyssf.core`, `org.easyssf.receiver`, ...) and work on the module path; the
Spring Boot modules are automatic modules.

```xml
<dependency>
    <groupId>org.easyssf</groupId>
    <artifactId>easyssf-receiver</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
SsfHttpClient httpClient = new JdkSsfHttpClient();   // or the HTTP client of your framework
String issuer = "https://idp.example/realms/demo";

SsfTransmitterMetadataResolver metadata = new SsfTransmitterMetadataResolver(issuer, null, httpClient);
NimbusSsfSetVerifier verifier = new NimbusSsfSetVerifier(issuer,
        () -> metadata.resolve().jwksUri().toString(), httpClient);
verifier.setExpectedAudience("https://my-app.example");
// metadata.resolve() also tells specVersion(), deliveryMethodsSupported(), criticalSubjectMembers(),
// authorizationSchemes() and defaultSubjects(). The resolver requires https for the issuer and the
// endpoints of the metadata (http on loopback addresses only); pass SsfTransmitterUriPolicy.INSECURE
// as a fourth argument for a development setup.

SsfEventHandler handler = (eventContext) -> {
    if (eventContext.hasEvent("CaepSessionRevoked")) {
        SsfSubject subject = eventContext.subjectFor("CaepSessionRevoked");
        // end the session subject.sessionId() of the user subject.subject()
    }
};
SsfSetProcessor processor = new SsfSetProcessor(verifier, new InMemorySsfJtiDedupStore(10_000), List.of(handler));

// PUSH: call this from the endpoint the transmitter posts SETs to
SsfPushHandler pushHandler = new SsfPushHandler(processor, "Bearer " + pushSecret);
SsfPushResponse response = pushHandler.handle(authorizationHeader, requestBody);
// send response.status() and, if not null, response.body() as application/json

// POLL: fetch SETs from the transmitter instead
SsfPoller poller = new SsfPoller(httpClient, tokenProvider, () -> pollEndpoint, processor);
poller.start();
```

Ready-made pieces for the common reactions:

- `SsfTokenRevocationEventHandler` + `SsfTokenRevocationStore.isRevoked(sid, sub, iat)` to reject
  access tokens of revoked sessions and users.
- `SsfSessionTerminationEventHandler` + your `SsfSessionTerminator` to end local sessions;
  `SsfSubjectClaimsMatcher` tells whether the subject of an event matches the claims of a user.
- `SsfStreamClient` and `SsfStreamRegistrar` for stream management,
  `ClientCredentialsSsfTransmitterTokenProvider` for the access token, `MicrometerSsfReceiverMetrics`
  for metrics.

The Spring Boot starter is such an integration, see `easyssf-receiver-spring-boot-autoconfigure`.

## Testing your receiver

`easyssf-test` contains `TestTransmitter`, a transmitter on a loopback port of the JVM that signs SETs,
serves its metadata and JWK Set, hands out access tokens for its client credentials and emulates the
stream management and poll endpoints. The tests of easyssf itself use it.

```xml
<dependency>
    <groupId>org.easyssf</groupId>
    <artifactId>easyssf-test</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

```java
static final TestTransmitter transmitter = new TestTransmitter();

@DynamicPropertySource
static void transmitter(DynamicPropertyRegistry registry) {
    registry.add("easyssf.receiver.transmitter-issuer", transmitter::issuer);
}

@Test
void revokedSessionIsRejected() {
    JWTClaimsSet set = transmitter
        .setClaims("CaepSessionRevoked", complex(issSub(transmitter.issuer(), "alice"), opaque("session-1")))
        .audience("https://my-app.example")
        .build();
    String encodedSet = transmitter.signSet(set);
    // POST encodedSet to /ssf/push, or transmitter.queueSet(encodedSet) for a polling receiver
}
```

`transmitter.setAvailable(false)` simulates an outage, `transmitter.acknowledgedSets()` and
`transmitter.reportedErrors()` show what a polling receiver acknowledged, `transmitter.streams()` the
streams a receiver registered. The subject helpers are the static methods of `SsfSubjectIdentifiers`.

## Build

```sh
./mvnw install
```

The build checks the formatting of the Java sources with [Spotless](https://github.com/diffplug/spotless)
(the [Spring Java Format](https://github.com/spring-io/spring-javaformat) conventions, see
[`etc/eclipse-formatter.prefs`](etc/eclipse-formatter.prefs)). To format them:

```sh
./mvnw spotless:apply
```

The tests against the OpenID conformance suite are not part of the normal build; they need Docker
and take a few minutes per plan:

```sh
./mvnw -pl easyssf-receiver-spring-boot-conformance-tests -Pconformance verify
```

See [`easyssf-receiver-spring-boot-conformance-tests`](easyssf-receiver-spring-boot-conformance-tests) for the details, the test plans and how to run
them against a newer suite than the released one. The CI builds are described in
[`CONTRIBUTING.md`](CONTRIBUTING.md).
