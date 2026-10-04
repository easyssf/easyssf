# Examples

Two applications that receive security events from a Keycloak SSF transmitter, and one that mirrors
the users of a SCIM service provider from its SCIM Events, with a demo transmitter of its own.

| | Port | |
|---|---|---|
| [`keycloak/`](keycloak) | 8080 | Docker Compose setup: Keycloak 26.8 with the `ssf` feature and a pre-configured `ssf-demo` realm |
| [`example-resource-server/`](example-resource-server) | 8081 | OAuth2 resource server. Rejects access tokens once their Keycloak session was revoked. Has a `poll` profile and metrics. |
| [`example-oidc-client/`](example-oidc-client) | 8082 | Web application with OIDC login. Ends the local session once the Keycloak session was revoked or the user's credentials changed. |
| [`example-scim-provisioning/`](example-scim-provisioning) | 8083 | Mirrors SCIM `Users` into a local directory from SCIM Events (RFC 9967). Runs without Keycloak, see [5.](#5-scim-provisioning) |

Neither application contains SSF specific code apart from configuration (and an optional
`SsfEventHandler` in the resource server that logs the events). Both manage their own stream: on
startup they authenticate with the service account of their Keycloak client and register a PUSH
stream for it (`Created SSF stream …`), after a restart they find it again (`Using existing SSF
stream …`). If you delete a stream in the admin console, the application creates a new one on its
next start.

## 1. Start Keycloak

```sh
cd easyssf-receiver-spring-boot-examples/keycloak
docker compose up
```

- Admin console: <http://localhost:8080> (`admin` / `admin`), realm `ssf-demo`
- Users: `tester` / `test` and `bob` / `bob`

## 2. Build

In the root of the repository:

```sh
./mvnw install -DskipTests
```

## 3. Resource server

```sh
./mvnw -pl easyssf-receiver-spring-boot-examples/example-resource-server spring-boot:run
```

Run the requests in
[`example-resource-server.http`](example-resource-server/example-resource-server.http) with the HTTP
client of IntelliJ IDEA, or use `curl`:

```sh
KC=http://localhost:8080/realms/ssf-demo/protocol/openid-connect

# log in as tester and call the API
TOKENS=$(curl -s $KC/token -d grant_type=password -d client_id=example-cli -d username=tester -d password=test)
ACCESS_TOKEN=$(echo "$TOKENS" | jq -r .access_token)
REFRESH_TOKEN=$(echo "$TOKENS" | jq -r .refresh_token)

curl -i localhost:8081/api/me -H "Authorization: Bearer $ACCESS_TOKEN"    # 200

# end the session in Keycloak ...
curl $KC/logout -d client_id=example-cli -d refresh_token=$REFRESH_TOKEN

# ... the access token has not expired, but is rejected within a few seconds
curl -i localhost:8081/api/me -H "Authorization: Bearer $ACCESS_TOKEN"    # 401, error="invalid_token"
```

The log of the resource server shows the stream it registered on startup and the event:

```
Created SSF stream d0200637-… (delivery urn:ietf:rfc:8935 http://host.docker.internal:8081/ssf/push, events [CaepSessionRevoked], audience [example-resource-server/d0200637-…])
Received SET b639c4e1-… with events [CaepSessionRevoked]
Security event CaepSessionRevoked for user 33bf4f70-… and session hPB29fvO…
CaepSessionRevoked: revoked access tokens of session hPB29fvO…
```

### Metrics

The resource server has Spring Boot Actuator on the classpath, the receiver records what it does
(`easyssf.receiver.*`). It also has `spring-boot-starter-restclient`, so the receiver calls Keycloak
with the `RestClient` of the application and these calls show up in `http.client.requests`:

```sh
# the Actuator endpoints need an access token like the API
ACCESS_TOKEN=$(curl -s $KC/token -d grant_type=password -d client_id=example-cli -d username=tester -d password=test | jq -r .access_token)
curl -s localhost:8081/actuator/metrics/easyssf.receiver.sets -H "Authorization: Bearer $ACCESS_TOKEN" | jq
curl -s localhost:8081/actuator/metrics/http.client.requests -H "Authorization: Bearer $ACCESS_TOKEN" | jq
```

### POLL delivery

With the profile `poll` the resource server does not register a PUSH stream for its own client.
It authenticates with the service account of the client `example-poll-receiver` instead, registers a
POLL stream for it and polls Keycloak for events every two seconds, see
[`application-poll.yaml`](example-resource-server/src/main/resources/application-poll.yaml):

```sh
./mvnw -pl easyssf-receiver-spring-boot-examples/example-resource-server spring-boot:run -Dspring-boot.run.profiles=poll
```

```
Created SSF stream ef50c4f3-… (delivery urn:ietf:rfc:8936 http://localhost:8080/…/poll, events [CaepSessionRevoked], audience [example-poll-receiver/ef50c4f3-…])
Received SET 52e22461-… (POLL) with events [CaepSessionRevoked]
```

The `curl` commands and the `.http` file work the same way. Keycloak keeps pushing to the PUSH
stream of `example-resource-server` in the meantime (streams survive a restart of the application),
which the application rejects in this profile.

## 4. OIDC client

```sh
./mvnw -pl easyssf-receiver-spring-boot-examples/example-oidc-client spring-boot:run
```

1. Open <http://localhost:8082> and log in as `tester` / `test`. The page shows the Keycloak session
   (`sid`) and the local session.
2. Sign out in Keycloak, not in the application: open the
   [account console](http://localhost:8080/realms/ssf-demo/account) (linked on the page) and sign out.
3. Switch back to the tab of the application: the page notices that its local session is gone and
   redirects to the login.

The page polls `GET /auth/check` every five seconds and whenever its tab becomes visible. The endpoint
does not call Keycloak (no token introspection): the receiver invalidates the local session when the
`session-revoked` event arrives, so the check simply answers 200 while the session exists and 401
once it is gone (see `AuthController` and `SecurityConfiguration`).

The client `example-oidc-client` has neither a back-channel nor a front-channel logout URL in
Keycloak. The application learns about the logout only through the `session-revoked` event, which
names the Keycloak session:

```
Received SET c7e34991-… with events [CaepSessionRevoked]
CaepSessionRevoked: terminated 1 session(s) of session QkNy7B8GqiyQ4nf92kutVw4w
```

Other things to try:

- In the admin console, sign the user out of all sessions or reset the password: all local sessions
  of the user end (`session-revoked` for the user, `credential-change`).
- *Log out* in the application logs the user out of Keycloak as well (RP-initiated logout), Keycloak
  then sends a `session-revoked` event for that session to both applications.
- Which events end local sessions is configured with `easyssf.receiver.oidc-client.session-event-types`
  and `easyssf.receiver.oidc-client.user-event-types`, see the `application.yaml` of the application.
- If the login fails, the login page only says "Invalid credentials". The reason is in the log of the
  application (`Login with Keycloak failed: [invalid_request] …`).

## 5. SCIM provisioning

[RFC 9967](https://www.rfc-editor.org/rfc/rfc9967) lets a SCIM service provider report the changes
of its resources as SETs. The example mirrors the `Users` into an in-memory directory:
`ScimProvisioningHandler` extends `SsfScimEventHandler` of the receiver and applies `create`, `put`
and `patch` events in `full` mode (the `data` is the resource, or the `PatchOp`), flips `active` on
`activate` and `deactivate`, removes the user on `delete`, and logs `notice` events, which carry no
data; `GET /users` shows the directory.

Keycloak does not emit SCIM Events, so the example brings its own transmitter: the in-process
`TestTransmitter` of `easyssf-test`, which `ExampleScimProvisioningApplication.main` starts before
the application and whose issuer it sets as `easyssf.receiver.transmitter-issuer`. The receiver
registers a POLL stream for the SCIM event types and polls every two seconds, exactly as it would
with a real transmitter. The `demo` profile that `main` activates adds the demo SCIM service
provider, `DemoScimProvider`: it plays the life of two users on startup, one SET every four seconds,
and takes requests under `/demo/scim`.

```sh
./mvnw -pl easyssf-receiver-spring-boot-examples/example-scim-provisioning spring-boot:run
```

```
Created SSF stream 3f0d…  (delivery urn:ietf:rfc:8936 http://127.0.0.1:…/realms/test/poll, events [ScimFeedAdd, …], audience [receiver/3f0d…])
The receiver registered its stream, the demo SCIM service provider starts

### Alice is created at the service provider and joins the feed: feed:add and prov:create:full in one SET, with her representation as data
Transmitted SET 2c6a… with [ScimFeedAdd, ScimProvCreateFull] for /Users/2b2f880af6674ac284bae9381673d462:
{
  "iss" : "http://127.0.0.1:53502/realms/test",
  "sub_id" : { "format" : "scim", "uri" : "/Users/2b2f880af6674ac284bae9381673d462", "externalId" : "alice" },
  "events" : { "urn:ietf:params:scim:event:feed:add" : { }, "urn:ietf:params:scim:event:prov:create:full" : { … } },
  …
}
ScimFeedAdd: /Users/2b2f880af6674ac284bae9381673d462 joined the feed
ScimProvCreateFull: created User[id=2b2f880af6674ac284bae9381673d462, externalId=alice, userName=alice, displayName=Alice Adams, emails=[alice@example.com], active=true, version=1]
ScimProvPatchFull: patched User[…, displayName=Alice Baker, emails=[alice@example.com, alice.baker@home.example], …, version=2]
ScimProvPatchNotice: /Users/2b2f880a… modified changed [phoneNumbers]; a notice event has no data, the application would GET the resource from the SCIM service provider
ScimProvDeactivate: deactivated User[…, active=false, version=2]
ScimProvDelete: deleted User[id=c3a6e1f0…, userName=robert, …]
```

```sh
curl -s localhost:8083/users | jq
```

### Walk through it yourself

The demo service provider is a tiny stand-in for the `/Users` endpoint of a SCIM server. Every
request to it transmits the SCIM Event about the change, the receiver mirrors it on its next poll,
and the response is the SET that was transmitted, so the event sits next to its cause:

| Request | Event |
|---|---|
| `POST /demo/scim/Users` with a SCIM user | `feed:add` and `prov:create:full` in one SET |
| `PUT /demo/scim/Users/{id}` with a SCIM user | `prov:put:full` |
| `PATCH /demo/scim/Users/{id}` with a `PatchOp` | `prov:patch:full`; with `?mode=notice` a `prov:patch:notice` that only names the paths |
| `POST /demo/scim/Users/{id}/activate`, `.../deactivate` | `prov:activate`, `prov:deactivate` |
| `DELETE /demo/scim/Users/{id}` | `prov:delete` |
| `GET /demo/scim/events` | the SETs transmitted so far, compact and decoded |

[`example-scim-provisioning.http`](example-scim-provisioning/example-scim-provisioning.http) runs
the life of a third user through these requests with the HTTP client of IntelliJ IDEA, checking the
directory after each step. Start the application with `--demo.autoplay=false`
(`-Dspring-boot.run.arguments=--demo.autoplay=false`) to begin with an empty directory.

### What a SCIM Event looks like

A SCIM Event is a SET like the CAEP events of Keycloak: the same JWT claims, verified the same
way. What is specific is the subject, a `scim` identifier with the relative `uri` of the resource and
its `externalId`, and the event types under `urn:ietf:params:scim:event:`. The demo logs every SET it
transmits decoded, and `GET /demo/scim/events` lists them; these are the claims of three of them.

A user is created and joins the feed. Two events in one SET, which RFC 9967 allows when they arise
from the same change of the same resource; `full` means the payload carries the resource as `data`:

```json
{
  "iss": "http://127.0.0.1:53502/realms/test",
  "jti": "858f32d4-6aa8-4263-b89f-c460da8c56e4",
  "iat": 1791114240,
  "aud": "receiver/f6d117de-5ec6-4f4c-bb4c-0da0f74f37a6",
  "txn": "9c1f3a6e2d8b4f0e8a7c5d6b4e3f2a10",
  "sub_id": {
    "format": "scim",
    "uri": "/Users/2b2f880af6674ac284bae9381673d462",
    "externalId": "alice"
  },
  "events": {
    "urn:ietf:params:scim:event:feed:add": {},
    "urn:ietf:params:scim:event:prov:create:full": {
      "version": "1",
      "data": {
        "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
        "userName": "alice",
        "displayName": "Alice Adams",
        "emails": [{"type": "work", "value": "alice@example.com", "primary": true}],
        "active": true
      }
    }
  }
}
```

The user is modified with SCIM PATCH. The `data` is the `PatchOp` the service provider applied, the
`version` the ETag of the resource afterwards:

```json
{
  "iss": "http://127.0.0.1:53502/realms/test",
  "jti": "165c8a92-c6ae-4390-855f-0a99d52753af",
  "iat": 1791114248,
  "aud": "receiver/f6d117de-5ec6-4f4c-bb4c-0da0f74f37a6",
  "txn": "4b7e9d2c1a3f4e5d8c6b7a9e0f1d2c3b",
  "sub_id": {
    "format": "scim",
    "uri": "/Users/2b2f880af6674ac284bae9381673d462",
    "externalId": "alice"
  },
  "events": {
    "urn:ietf:params:scim:event:prov:patch:full": {
      "version": "2",
      "data": {
        "schemas": ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
        "Operations": [
          {"op": "replace", "path": "displayName", "value": "Alice Baker"},
          {"op": "add", "path": "emails", "value": [
            {"type": "work", "value": "alice@example.com"},
            {"type": "home", "value": "alice.baker@home.example"}
          ]}
        ]
      }
    }
  }
}
```

The same change as a `notice`: the payload names the attributes that changed and nothing else, a
receiver that needs the values fetches the resource from the service provider with a GET of the
`uri`. Events without payload attributes, `prov:delete`, `prov:activate` and `prov:deactivate`, have
an empty object:

```json
{
  "iss": "http://127.0.0.1:53502/realms/test",
  "jti": "913ea597-421b-4cf1-8c8a-37b918e61c6c",
  "iat": 1791114256,
  "aud": "receiver/f6d117de-5ec6-4f4c-bb4c-0da0f74f37a6",
  "txn": "7d2a5c8e4b1f4a6d9e3c2b5a8f7e6d4c",
  "sub_id": {
    "format": "scim",
    "uri": "/Users/2b2f880af6674ac284bae9381673d462",
    "externalId": "alice"
  },
  "events": {
    "urn:ietf:params:scim:event:prov:patch:notice": {
      "version": "3",
      "attributes": ["phoneNumbers"]
    }
  }
}
```

On the wire each of these is a signed JWT with `typ: secevent+jwt` (the `set` member of
`GET /demo/scim/events`), delivered by poll here, by push with a real transmitter that can reach the
application. The receiver verifies the signature against the transmitter's keys, `iss`, `aud`,
`iat` and `jti`, requires the top-level `sub_id` (which RFC 9967 mandates), skips a `jti` it has
seen, and hands the events to the handler.

Against a real SCIM service provider: use `SpringApplication.run` and put its issuer, token and
delivery method into `application.yaml`; the handler and the directory stay as they are. The tests
run the application against the transmitter: `ExampleScimProvisioningApplicationTests` plays a user
through create, patch, activate and delete without the `demo` profile,
`DemoScimProviderTests` the walk-through of the `.http` file.

## What triggers an event in Keycloak

| Action | Event | Subject |
|---|---|---|
| A user logs out (logout in an application, `/logout` endpoint) | `session-revoked` | user and session |
| An admin signs a user out of all sessions | `session-revoked` | user, session `ALL` |
| A credential changes (for example an admin resets the password) | `credential-change` | user |

Removing a *single* session through the admin API (`DELETE /admin/realms/{realm}/sessions/{id}`) does
not produce an event in Keycloak 26.8.

## How the realm is set up

Everything is in [`keycloak/ssf-demo-realm.json`](keycloak/ssf-demo-realm.json) and
[`keycloak/compose.yaml`](keycloak/compose.yaml):

| | |
|---|---|
| `--features=ssf` | SSF is an experimental feature of Keycloak. |
| Realm attribute `ssf.transmitterEnabled` | Turns the realm into an SSF transmitter. |
| Clients `example-resource-server` and `example-oidc-client` | SSF receivers (`ssf.enabled`). A receiver is a client, so the resource server gets one too although it does not log users in. |
| Service account and optional client scopes `ssf.read`, `ssf.manage` | Let the application manage the stream of its client (`easyssf.receiver.stream.management=receiver` with `easyssf.receiver.oauth2.*`). Keycloak creates the two client scopes when the `ssf` feature is enabled. |
| Client attribute `ssf.defaultSubjects=ALL` | Deliver events of all users. By default a receiver only gets events of users that were subscribed explicitly. |
| Client attribute `ssf.validPushUrls` | The push URLs a stream of this client may use, here `http://host.docker.internal:<port>/ssf/push`, which the application registers (`easyssf.receiver.push.delivery-endpoint-url`). |
| Client attributes `ssf.stream.*` | The stream itself. Not in the realm file: the application creates it on startup, with the `Authorization` header from `easyssf.receiver.push.expected-auth-header`. Keycloak assigns the audience (`<clientId>/<streamId>`), which the application takes from the stream to validate SETs. In the admin console: *Clients* → client → *SSF*. |
| Client `example-poll-receiver` | Receiver for the `poll` profile of the resource server: a second client, because Keycloak allows one stream per client and the PUSH stream of `example-resource-server` stays. |
| Client `example-cli` | Public client to obtain access tokens with `curl`. |
| `allow-insecure-push-targets=true` | Keycloak only pushes to public `https` URLs by default. |
| `outbox-drainer-interval=2s` | Keycloak delivers events every 30 seconds by default. |
| `KC_HOSTNAME=http://localhost:8080` | Fixes the issuer, events are created outside of a request. |

Keycloak stores its database in `keycloak/data`, so streams, users and changes made in the admin
console survive `docker compose down`. The realm is only imported into an empty database: to pick up
changes to `ssf-demo-realm.json`, stop Keycloak and delete `keycloak/data`.

## Using another Keycloak

Both applications read the issuer from `KEYCLOAK_ISSUER` (default
`http://localhost:8080/realms/ssf-demo`), the secret of their own client from `KEYCLOAK_CLIENT_SECRET`
(the `poll` profile reads the secret of `example-poll-receiver` from `KEYCLOAK_POLL_CLIENT_SECRET`) and
the URL under which Keycloak reaches their push endpoint from `SSF_PUSH_URL` (default
`http://host.docker.internal:<port>/ssf/push`). The clients need a service account with the client
scopes `ssf.read` and `ssf.manage`, and the push URL has to be allowed in `ssf.validPushUrls` of
the client. The push authorization header is set in the `application.yaml` of each application.
