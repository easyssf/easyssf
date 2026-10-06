# easyssf Spring Boot starter

The receiver of [easyssf](../README.md) for Spring Boot 4.1 (Spring Security 7.1, servlet stack):
configuration properties, auto-configuration, the push endpoint, and resource server and OIDC client
integrations that need no code of yours. This README is the reference of the starter; the
[root README](../README.md) describes what every integration shares: the events, the subjects, SCIM
Events, the test support.

```xml
<dependency>
    <groupId>org.easyssf</groupId>
    <artifactId>easyssf-receiver-spring-boot-starter</artifactId>
    <version>0.3.0</version>
</dependency>
```

Reacting to a security event is a bean and one property:

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

Resource servers and OIDC clients do not even need the bean: with Spring Security on the classpath,
access tokens of a revoked session are rejected and local sessions are ended as the events arrive.

- **Resource server**: rejects access tokens once their session (or user) was revoked by a CAEP
  `session-revoked` event.
- **OIDC client**: terminates local sessions on CAEP `session-revoked` (by `sid` or user) and
  `credential-change` (all sessions of the user).

The [examples](../easyssf-receiver-spring-boot-examples) contain a resource server, an OIDC client
and a SCIM provisioning application; the first two run against a Keycloak with a pre-configured
realm.

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
  `phone_number`; `aliases` match if any of their identifiers does; a `scim` resource by the attributes
  and claims of `easyssf.receiver.oidc-client.scim-attribute-claims`, by default `externalId` and `id`
  against `sub` and `userName` against `preferred_username`. Matching can be customized with a
  `SsfSessionMatcher` bean.
- `credential-change` terminates sessions for every kind of change (including a newly added
  credential). To be more selective, set `easyssf.receiver.oidc-client.user-event-types` to an empty list
  and call `SsfSessionTerminator` from your own handler.

**SCIM Events** (RFC 9967, see the [root README](../README.md#scim-events)): the deactivation and
the deletion of a SCIM user usually mean the user may no longer be logged in. To end their local
sessions, add the events to the user events. Which SCIM resource is which logged-in user is not
something SCIM or OpenID Connect define: it is a decision of your deployment, about identifiers the
SCIM service provider and the OpenID Provider happen to share, and the mapping below is where you
state it. A `scim` subject is matched with the logged-in user by comparing attributes of the resource
with claims: by default `externalId` and `id` (the `id`
attribute, or the last segment of the `uri`) with `sub`, and `userName` with `preferred_username`. If
your SCIM service provider and your OpenID Provider share other identifiers, configure the pairs; a
multi-valued attribute such as `emails` matches if any of its values does, and values compared with
the `email` claim match ignoring case:

```yaml
easyssf:
  receiver:
    oidc-client:
      user-event-types: CaepCredentialChange, ScimProvDeactivate, ScimProvDelete
      scim-attribute-claims:
        externalId: sub
        emails: email
```

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
- **Acknowledgements** ride on the next poll request (RFC 8936, section 2.4) and wait in an
  `SsfPollAckStore` until a request has carried them. In memory by default; with a `JdbcTemplate` they
  are kept in the table `EASYSSF_POLL_ACK` (see [Keeping state in the database](#keeping-state-in-the-database)),
  so that a SET handled right before a restart is acknowledged afterwards instead of being delivered
  again and skipped as a duplicate. `stop()` sends the pending acknowledgements with a last request.
  The health details show them as `pendingAcks`, the gauge `easyssf.receiver.poll.pending-acks` too.
- **Long polling**: by default every request asks the transmitter to answer at once
  (`returnImmediately: true`) and the transmitter is polled every `poll.interval`. With
  `easyssf.receiver.poll.long-polling=true` the poller keeps one request outstanding instead: the
  transmitter holds it until SETs are available or its hold time elapses (section 2.5), and the next
  request goes out as soon as the response was handled. Set `poll.long-polling-hold` to the hold time
  agreed with the transmitter (30 seconds by default); the request waits that long plus a margin. A
  transmitter that answers an empty long poll at once is polled every `poll.interval` nevertheless.
  Whether Keycloak holds poll requests has not been verified; its default remains short polling.
  With long polling on, the poller calls the transmitter with the JDK HTTP client even if the
  application has a `RestClient`, because the `RestClient` has no timeout per request
  (`SsfTransmitterFactory.pollHttpClient`); a poller bean of your own, built with
  `SsfTransmitterFactory.poller(...)`, uses whatever client you pass.

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
| `EASYSSF_PROCESSED_SET` | `JdbcSsfJtiDedupStore` | the SETs that were processed or are being processed (`STATE`), forgotten after `easyssf.receiver.dedup.retention` (7 days) |
| `EASYSSF_REVOCATION` | `JdbcSsfTokenRevocationStore` | revoked sessions and subjects, per issuer, removed after `easyssf.receiver.resource-server.revocation-ttl` |
| `EASYSSF_POLL_ACK` | `JdbcSsfPollAckStore` | acknowledgements and error reports a polling receiver owes its transmitter until a poll request carried them, forgotten after `easyssf.receiver.jdbc.ack-retention` (7 days) |

- **Tables**: in an embedded database (H2, HSQLDB, Derby) the tables are created on startup, and
  columns a newer release added to an existing table as well. For any other database create them with
  your migration tool: the
  [`migration/`](../easyssf-receiver-jdbc/src/main/resources/org/easyssf/receiver/jdbc/migration)
  scripts (`classpath:org/easyssf/receiver/jdbc/migration/`, one per release that changed the schema,
  named for Flyway, plain SQL for Liquibase and others) keep an installation current from release to
  release, [`schema.sql`](../easyssf-receiver-jdbc/src/main/resources/org/easyssf/receiver/jdbc/schema.sql)
  is the current schema for a fresh one. Or set `easyssf.receiver.jdbc.initialize-schema=always` to have
  the receiver create and upgrade the tables itself. If a table or a column is missing the application
  fails on startup with the statements to run, rather than on the first event. The stores use plain SQL (`VARCHAR`,
  `BIGINT`, no vendor syntax; a result limit is applied by the JDBC driver) and are tested on H2,
  PostgreSQL and MySQL.
- **Several instances**: a SET is claimed before its handlers run and marked processed afterwards. An
  instance that receives a SET another one is handling leaves it for the transmitter's redelivery,
  neither handling nor acknowledging it, so a handler failure on the first instance is not masked by
  an acknowledgement of the second. A claim older than `easyssf.receiver.dedup.lease` (60 seconds)
  counts as abandoned by a crashed instance and the SET is handled again. The guarantee is therefore
  at-least-once processing with concurrent deliveries suppressed, not exactly once: handlers stay
  idempotent and key their side effects by `eventContext.idempotencyKey()`. Set the lease longer than
  your longest handler.
- **Upgrading**: from 0.1.0 or 0.2.0 run `migration/V0_3_0__dedup_state_and_poll_acks.sql`, which adds
  the column `STATE` to `EASYSSF_PROCESSED_SET` and creates `EASYSSF_POLL_ACK`; from 0.3.0 or earlier run
  `migration/V0_4_0__state_changed_at.sql`, which renames `PROCESSED_AT` to `STATE_CHANGED_AT`. With
  `initialize-schema` `embedded` or `always` the receiver does both on startup.
- **Opting out**: `easyssf.receiver.jdbc.enabled=false` keeps the state in memory although the
  application has a database. Your own `SsfJtiDedupStore`, `SsfTokenRevocationStore` or
  `SsfPollAckStore` bean takes precedence in any case.
- **Cleanup**: expired rows are deleted when a store is written to (the SET table at most once a
  minute) and every `easyssf.receiver.jdbc.cleanup-interval` (15 minutes) by `JdbcSsfStoreCleanup`
  on a thread of its own; `easyssf.receiver.jdbc.cleanup-interval=0` turns the periodic cleanup off.
  The stores offer `purgeExpired()` to do it from your own scheduler.
- **Cost**: a resource server checks every access token with one query on the primary key of
  `EASYSSF_REVOCATION`.
- The stores live in `easyssf-receiver-jdbc` and have no Spring dependency: `SsfJdbcOperations` runs
  their SQL, over a `DataSource` (`DataSourceSsfJdbcOperations`) or, in Spring Boot, the `JdbcTemplate`
  of the application, so its transactions and exception translation apply.
- The stores use plain SQL (timestamps are stored as milliseconds since the epoch) and were tested
  with H2, PostgreSQL and MySQL. The revocation table is only used by resource servers. Sessions of an
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
| `easyssf.receiver.sets` | counter | `transmitter` (the name of the transmitter, `default` for the one at `easyssf.receiver.*`), `delivery` (`push`, `poll`), `outcome` (`handled`, `duplicate`, `invalid`, `unauthenticated`, `unavailable`, `failed`, `in_progress`) |
| `easyssf.receiver.events` | counter | `transmitter`, `delivery`, `event` (for example `CaepSessionRevoked`) |
| `easyssf.receiver.poll` | timer | `transmitter`, `outcome` (`success`, `failure`) |
| `easyssf.receiver.poll.pending-acks` | gauge | `transmitter`; acknowledgements and error reports waiting for the next poll request, see [POLL delivery](#poll-delivery) |

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
| `easyssf.receiver.poll.long-polling` | `false` | Keep one request outstanding that the transmitter holds until SETs are available, instead of polling every `interval`. |
| `easyssf.receiver.poll.long-polling-hold` | `30s` | How long the transmitter holds a long poll request, agreed with it; the request waits that long plus a margin. |
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
| `easyssf.receiver.dedup.lease` | `60s` | How long a SET counts as being handled by the instance that claimed it; longer than the longest handler. |
| `easyssf.receiver.jdbc.enabled` | `true` | Keep state in the database if the application has a `JdbcTemplate`. |
| `easyssf.receiver.jdbc.initialize-schema` | `embedded` | When to create missing tables: `embedded`, `always` or `never`. |
| `easyssf.receiver.jdbc.table-prefix` | `EASYSSF_` | Prefix of the table names. |
| `easyssf.receiver.jdbc.cleanup-interval` | `15m` | How often expired rows are purged; `0` turns it off. |
| `easyssf.receiver.jdbc.ack-retention` | `7d` | How long the JDBC store keeps a poll acknowledgement no request managed to deliver. |
| `easyssf.receiver.jdbc.ack-delete-batch-size` | `100` | How many delivered acknowledgements one `DELETE` of the JDBC store removes at once. |
| `easyssf.receiver.resource-server.enabled` | `true` | |
| `easyssf.receiver.resource-server.event-types` | `CaepSessionRevoked` | Events that revoke access tokens. |
| `easyssf.receiver.resource-server.revocation-ttl` | `1h` | |
| `easyssf.receiver.oidc-client.enabled` | `true` | |
| `easyssf.receiver.oidc-client.session-event-types` | `CaepSessionRevoked` | Events that terminate the session (or user sessions) of their subject. |
| `easyssf.receiver.oidc-client.user-event-types` | `CaepCredentialChange` | Events that terminate all sessions of their subject's user. |
| `easyssf.receiver.oidc-client.scim-attribute-claims.*` | `externalId: sub`, `id: sub`, `userName: preferred_username` | Attributes of the `scim` subject of a SCIM Event and the claim of the logged-in user each is compared with. |

Beans you can replace: `SsfSetVerifier`, `SsfTransmitterMetadataResolver`, `SsfJtiDedupStore`,
`SsfTokenRevocationStore`, `SsfRevokedTokenValidator`, `SsfSessionTerminator`, `SsfSessionMatcher`,
`SsfTransmitterTokenProvider`, `SsfStreamClient`, `SsfStreamRegistrar`, `SsfPoller`, `SsfReceiverMetrics`,
`SsfHttpClient`, `SsfPushHandler`.

## Not included (yet)

- WebFlux applications.
