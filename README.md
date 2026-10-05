# easyssf

Building blocks for the
[OpenID Shared Signals Framework (SSF)](https://openid.net/specs/openid-sharedsignals-framework-1_0.html)
in Java, for applications that receive security events: a framework independent **receiver**
library, a **Spring Boot starter**, and the foundation of the **Quarkus** extension
[quarkus-openid-ssf](https://github.com/quarkiverse/quarkus-openid-ssf).

**Turn a Java application into an SSF receiver without implementing SET validation, metadata
discovery, PUSH and POLL delivery, stream management and replay protection yourself.** Reacting to a
security event is a handler:

```java
SsfEventHandler handler = (event) -> {
    if (event.hasEvent("CaepSessionRevoked")) {
        SsfSubject subject = event.subjectFor("CaepSessionRevoked");
        // end what you hold for subject.sessionId() or subject.subject()
    }
};
```

In Spring Boot and Quarkus the handler is a bean and the transmitter one property; in plain Java a
few lines wire the parts together, see [Getting started](#getting-started). The receiver is tested
against the OpenID conformance suite's SSF and CAEP receiver test plans, with PUSH and POLL delivery.

| | |
|---|---|
| **Status** | Experimental |
| **Java** | 21+ |
| **Group id** | `org.easyssf` |

## Integrations

| | Artifact | |
|---|---|---|
| **Plain Java** | `org.easyssf:easyssf-receiver` | The receiver as a library without framework dependencies: verifier, processor, push handler, poller and stream management, wired into your framework by you. See [Assembling the receiver in plain Java](#assembling-the-receiver-in-plain-java). |
| **Spring Boot** | `org.easyssf:easyssf-receiver-spring-boot-starter` | Auto-configuration for Spring Boot 4.1: properties, the push endpoint with Spring Security, resource server and OIDC client integration, JDBC stores, metrics and health. See the [README of the starter](easyssf-receiver-spring-boot-starter/README.md). |
| **Quarkus** | `io.quarkiverse.openid-ssf:quarkus-openid-ssf-receiver` | The Quarkiverse extension, built on `easyssf-receiver` since its 0.2.0: configuration, CDI, a Vert.x push route, Dev UI, native images. Maintained in [its own repository](https://github.com/quarkiverse/quarkus-openid-ssf), documented at [docs.quarkiverse.io](https://docs.quarkiverse.io/quarkus-openid-ssf/dev/index.html). |

The library and the starter are released together under the group id `org.easyssf`; the extension
follows every easyssf release.

| Module | | Depends on |
|---|---|---|
| `easyssf-core` | The data structures of SSF shared by receivers and (later) transmitters: SETs, subjects, event types, stream configuration, transmitter metadata. | nothing |
| `easyssf-receiver` | The receiver, independent of any framework: SET verification, de-duplication, event handlers, push handling, polling, stream management, token revocation and session termination logic. | `easyssf-core`, Nimbus JOSE + JWT, SLF4J |
| `easyssf-receiver-jdbc` | The database-backed stores of the receiver (processed SETs, revocations), independent of any framework: the SQL, the schema and a small `SsfJdbcOperations` interface, implemented over a `DataSource` or by a framework's template. | `easyssf-receiver` |
| [`easyssf-receiver-spring-boot-starter`](easyssf-receiver-spring-boot-starter/README.md) | The receiver for Spring Boot 4.1 (Spring Security 7.1, servlet stack): configuration properties, auto-configuration, push endpoint, resource server and OIDC client integration. | `easyssf-receiver`, Spring Boot |
| `easyssf-test` | Test support: a transmitter that signs and delivers SETs, for the tests of your receiver, see [Testing your receiver](#testing-your-receiver). | `easyssf-core`, Nimbus JOSE + JWT |
| [`easyssf-receiver-spring-boot-examples`](easyssf-receiver-spring-boot-examples) | Example resource server and OIDC client with a Keycloak setup, and a SCIM provisioning example. | |
| `easyssf-test-conformance` | Runs the OpenID conformance suite's SSF receiver test plans against a receiver under test, in any framework: the suite started with Testcontainers, the scenarios the receiver plays, and the plan tests a framework's test class extends. | `easyssf-receiver`, Testcontainers, JUnit |
| [`easyssf-receiver-spring-boot-conformance-tests`](easyssf-receiver-spring-boot-conformance-tests) | The Spring Boot receiver under test and the four plan tests for it. | |

## What it does

- Receives SETs the transmitter **pushes** (RFC 8935) to an endpoint of the application, or
  **polls** the transmitter for them (RFC 8936).
- **Verifies every SET** (RFC 8417): signature against the transmitter's JWK Set, `typ`, `iss`,
  `aud`, `jti`, `iat` and `events`. The JWK Set location is discovered from the transmitter's
  `.well-known/ssf-configuration`.
- Skips SETs it has already processed (`jti` de-duplication).
- Hands verified events to your `SsfEventHandler`s.
- **Manages its stream** at the transmitter if you want: looks it up on startup and creates or updates
  it, and offers an `SsfStreamClient` for the stream management API.
- Ready-made reactions: **token revocation** for resource servers and **session termination** for
  OIDC clients, which the Spring Boot starter wires up without a line of code.
- Records **metrics** with Micrometer.
- **SCIM Events** (RFC 9967): the event types and the `scim` subject, and an `SsfScimEventHandler`
  that hands you typed create, patch, put, delete, activate and deactivate events.

## Getting started

The releases are on Maven Central. Snapshots of `main` are on the
[Central snapshot repository](https://central.sonatype.com/repository/maven-snapshots/org/easyssf/),
which a build has to enable with `<snapshots><enabled>true</enabled></snapshots>`.

### Spring Boot

```xml
<dependency>
    <groupId>org.easyssf</groupId>
    <artifactId>easyssf-receiver-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

```yaml
easyssf:
  receiver:
    transmitter-issuer: https://idp.example/realms/demo   # required
    expected-audience: https://my-app.example             # recommended
    push:
      expected-auth-header: Bearer ${SSF_PUSH_SECRET}     # recommended
```

Every `SsfEventHandler` bean receives the events. Then create a stream with PUSH delivery at the
transmitter that points to `https://my-app.example/ssf/push` and sends the configured
`Authorization` header, or let the application create the stream itself. Resource servers and OIDC
clients do not even need a handler: with Spring Security on the classpath, access tokens of a revoked
session are rejected and local sessions are ended as the events arrive. The
[README of the starter](easyssf-receiver-spring-boot-starter/README.md) describes all of it, the
[examples](easyssf-receiver-spring-boot-examples) run against a Keycloak with a pre-configured realm.

### Quarkus

```xml
<dependency>
    <groupId>io.quarkiverse.openid-ssf</groupId>
    <artifactId>quarkus-openid-ssf-receiver</artifactId>
    <version>0.2.0</version>
</dependency>
```

```properties
quarkus.openid-ssf.receiver.transmitter-issuer=https://idp.example/realms/demo
```

Every `SsfEventHandler` CDI bean receives the events. The extension is maintained in the
[quarkiverse/quarkus-openid-ssf](https://github.com/quarkiverse/quarkus-openid-ssf) repository; its
README has a quick start against the public caep.dev transmitter, and
[docs.quarkiverse.io](https://docs.quarkiverse.io/quarkus-openid-ssf/dev/index.html) the
configuration reference.

### Plain Java

```xml
<dependency>
    <groupId>org.easyssf</groupId>
    <artifactId>easyssf-receiver</artifactId>
    <version>0.2.0</version>
</dependency>
```

Create the verifier, the processor with your handlers, and the push handler or the poller, see
[Assembling the receiver in plain Java](#assembling-the-receiver-in-plain-java). Every other framework integrates the library the same way.

## Handling events yourself

Every `SsfEventHandler` is invoked for every verified SET: in the Spring Boot starter and in the
Quarkus extension every bean of that type, in plain Java the handlers given to the
`SsfSetProcessor`.

```java
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

**A SET is handled at least once, not exactly once**, so handlers must be idempotent. If a handler
throws, the SET is not acknowledged and the transmitter is expected to deliver it again, in which case
all handlers run again, possibly on another instance of the application. With a shared dedup store a
SET is claimed while its handlers run and another instance leaves it alone meanwhile, and a redelivery
of a handled SET is skipped; but an instance that dies after a handler's side effects and before the
success is recorded hands the SET to the next instance after a lease. For side effects outside the
receiver's stores, a database row, a message, an email, key them by `eventContext.idempotencyKey()`,
the issuer and `jti` of the SET.

`SsfSubject` is the `sub_id` of the SET: a simple subject identifier (RFC 9493: `iss_sub`, `email`,
`opaque`, `account`, `phone_number`, `did`, `uri`, `aliases`; RFC 9967: `scim`) or a complex subject whose members
(`user`, `session`, `device`, `tenant`, `application`, `org_unit`, `group`) are identifiers.
`subject.userIdentifier()` and `subject.session()` are the members the receiver acts on,
`subject.member("device")` gives the others, and `subject.raw()` the claim as received. Each
`SsfSubjectIdentifier` has a `format()`, a `value()` (the `sub`, `email`, `id`, ... of its format) and
its `claims()`. The shortcuts `subject()`, `sessionId()`, `email()` and `opaqueId()` cover the common
cases.

Event types are identified by their URIs; the aliases (`CaepSessionRevoked`, ...) are a convenience
of easyssf, use the URIs where you persist event types. Your own aliases, for vendor specific event
types, are registered with `SsfEventTypes.registerAlias` (the starter has the property
`easyssf.receiver.event-aliases` for it) and work wherever
an event type is named; they cannot redefine a built-in alias. `eventTimestamp()` returns the
`event_timestamp` of the event, which CAEP defines in seconds; a value that is clearly milliseconds is
accepted as well, as some transmitters send that.

**Critical subject members**: a transmitter can declare in its metadata (`critical_subject_members`)
members of a complex subject that a receiver must interpret. A SET whose subject has such a member
is rejected (`invalid_request`) unless the member is one the application understands. By default
these are the members `SsfSubject` gives access to: `user`, `session`, `device`, `application`,
`tenant`, `org_unit` and `group`. If the application interprets others, or fewer, tell the processor
(`SsfSetProcessor.setUnderstoodSubjectMembers`, in the starter the property
`easyssf.receiver.understood-subject-members`).

## SCIM Events

[RFC 9967](https://www.rfc-editor.org/rfc/rfc9967) delivers provisioning changes of a SCIM service
provider as SETs, over the same push and poll delivery. They pass the receiver like any other SET:
RFC 9967 requires the top-level `sub_id` that the strict SSF 1.0 mode checks for. What is new is the
vocabulary, and easyssf knows it:

- The event types under `urn:ietf:params:scim:event:` with the aliases `ScimFeedAdd`, `ScimFeedRemove`,
  `ScimProvCreateNotice`, `ScimProvCreateFull`, `ScimProvPatchNotice`, `ScimProvPatchFull`,
  `ScimProvPutNotice`, `ScimProvPutFull`, `ScimProvDelete`, `ScimProvActivate`, `ScimProvDeactivate` and
  `ScimMiscAsyncResponse`, usable in `events-requested` and wherever else an event type is named.
- The `scim` subject identifier: `SsfScimSubject` gives you the relative `uri()` of the resource, its
  `resourceType()` (`Users`, `Groups`), `id()`, `externalId()` and any further `attribute("userName")`.
- `SsfScimEvent`, the typed payload: `operation()`, `isFull()` with `data()` (the resource, or the
  `PatchOp`), `isNotice()` with `attributes()` (the paths that changed), `version()` (the ETag), and for
  an asynchronous response `method()`, `status()` and `response()`. The `txn` that groups the SETs of a
  transaction is `eventToken().txn()`.

`SsfScimEventHandler` dispatches every SCIM Event of a SET to the method of its operation; override the
ones you act on:

```java
@Component
class ProvisioningHandler extends SsfScimEventHandler {

    @Override
    protected void onCreate(SsfScimEvent event, SsfEventContext eventContext) {
        if (event.isFull() && "Users".equals(event.subject().resourceType())) {
            users.create(event.subject().id(), event.data());
        }
        // a notice event only names event.attributes(): fetch the resource with a SCIM GET of
        // event.subject().uri() at the service provider if you need its state
    }

    @Override
    protected void onDeactivate(SsfScimEvent event, SsfEventContext eventContext) {
        users.disable(event.subject().id());
    }

}
```

`event.subject()` is `null` if the SET carries a subject that is not a `scim` identifier, which RFC
9967 does not allow but a handler should expect. The handler methods have to be idempotent like any
`SsfEventHandler`.

Deactivation and deletion usually mean the user may no longer be logged in. `SsfSubjectClaimsMatcher`
matches a `scim` subject with a logged-in user by comparing attributes of the resource with claims,
so `SsfSessionTerminationEventHandler` can end their sessions: by default `externalId` and `id` (the
`id` attribute, or the last segment of the `uri`) with `sub`, and `userName` with
`preferred_username`. If your SCIM service provider and your OpenID Provider share other
identifiers, pass other pairs to `SsfSubjectClaimsMatcher.matches(subject, claims, name, pairs)`
(`DEFAULT_SCIM_ATTRIBUTE_CLAIMS` is the default); a multi-valued attribute such as `emails` matches
if any of its values does, and values compared with the `email` claim match ignoring case. In the
Spring Boot starter this is the property `easyssf.receiver.oidc-client.scim-attribute-claims`, see
[its README](easyssf-receiver-spring-boot-starter/README.md#use-case-oidc-client).

The receiver does not call the SCIM service provider itself: fetching a resource after a notice event,
the asynchronous SCIM requests of RFC 9967 and the `Set-Txn` header are the business of a SCIM client.
The [SCIM provisioning example](easyssf-receiver-spring-boot-examples/README.md#5-scim-provisioning) mirrors
users into a local directory this way, with a demo transmitter because Keycloak does not emit SCIM
Events.

## Assembling the receiver in plain Java

`easyssf-receiver` has no framework dependencies. Assemble the parts you need and call them from
your framework. `easyssf-core`, `easyssf-receiver`, `easyssf-receiver-jdbc` and `easyssf-test` are
Java modules (`org.easyssf.core`, `org.easyssf.receiver`, ...) and work on the module path; the
Spring Boot modules are automatic modules.

```xml
<dependency>
    <groupId>org.easyssf</groupId>
    <artifactId>easyssf-receiver</artifactId>
    <version>0.2.0</version>
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
poller.setAckStore(ackStore);              // optional: JdbcSsfPollAckStore keeps acknowledgements across restarts
poller.setLongPolling(Duration.ofSeconds(30)); // optional: hold requests instead of polling every interval
poller.start();
```

Ready-made pieces for the common reactions:

- `SsfTokenRevocationEventHandler` + `SsfTokenRevocationStore.isRevoked(sid, sub, iat)` to reject
  access tokens of revoked sessions and users.
- `SsfSessionTerminationEventHandler` + your `SsfSessionTerminator` to end local sessions;
  `SsfSubjectClaimsMatcher` tells whether the subject of an event matches the claims of a user.
- `SsfScimEventHandler` to react to SCIM Events (RFC 9967) by operation, with `SsfScimEvent` and
  `SsfScimSubject` from `easyssf-core` for the payload and the resource.
- `SsfStreamClient` and `SsfStreamRegistrar` for stream management,
  `ClientCredentialsSsfTransmitterTokenProvider` for the access token, `MicrometerSsfReceiverMetrics`
  for metrics.

The Spring Boot starter (`easyssf-receiver-spring-boot-autoconfigure`) and the Quarkus extension are
such integrations; read either to see the parts assembled.

## Keycloak

Tested with the SSF transmitter of Keycloak 26.8 (`--features=ssf`), see the [examples](easyssf-receiver-spring-boot-examples).

- Keycloak publishes its metadata at both locations the receiver looks at, no explicit metadata URL
  is needed.
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
  subject has a `session` member with the identifier `ALL`. The receiver treats this like a subject
  that names only the user (`SsfSubject.sessionId()` is `null`).
- Keycloak delivers events with its outbox drainer, by default every 30 seconds
  (`--spi-ssf-transmitter--default--outbox-drainer-interval`).

## Testing your receiver

`easyssf-test` contains `TestTransmitter`, a transmitter on a loopback port of the JVM that signs SETs,
serves its metadata and JWK Set, hands out access tokens for its client credentials and emulates the
stream management and poll endpoints. The tests of easyssf itself use it.

```xml
<dependency>
    <groupId>org.easyssf</groupId>
    <artifactId>easyssf-test</artifactId>
    <version>0.2.0</version>
    <scope>test</scope>
</dependency>
```

```java
// a Spring Boot test; in plain Java, pass transmitter.issuer() and transmitter.jwksUri() to the verifier
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

## Not included (yet)

- A transmitter. `easyssf-core` is written to be shared with one.

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
