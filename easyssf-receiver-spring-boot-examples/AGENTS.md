# easyssf-receiver-spring-boot-examples

A resource server and an OIDC client that receive events from a Keycloak transmitter, with a
Docker Compose setup for Keycloak, and a SCIM provisioning example that runs without Keycloak
(which emits no SCIM Events) against the in-process `TestTransmitter` of `easyssf-test`, fed by its
`DemoScimProvider`. Not published. The README walks through running them.

- The Keycloak examples contain no SSF code apart from configuration (and a logging handler). Keep
  them that way: a feature that needs code in an example is a feature the starter is missing. The
  SCIM example's handler is application logic (what to do with a provisioning event), that is fine.
- `example-scim-provisioning` has the only tests among the examples; they start the application
  against a `TestTransmitter` with `@DynamicPropertySource` and poll every 200 ms. Its `demo`
  profile adds a request-driven stand-in for a SCIM server under `/demo/scim` (and a scripted
  play, `demo.autoplay`); `example-scim-provisioning.http` walks through it and the README shows
  the decoded SETs. Keep the three in step when the events or payloads change.
- Before `docker compose up` in `keycloak/`, check for an existing compose project of the same
  name: `docker ps -a --filter label=com.docker.compose.project=easyssf-examples`. The compose
  file sets `name: easyssf-examples` for that reason; never remove the `name`, and never run
  `down -v` or delete volumes you did not create. Developer machines tend to have several
  unrelated projects in folders called `keycloak`.
- The realm `ssf-demo` is imported from `keycloak/`; change the realm export there, not in a
  running Keycloak.
- Changing the ports (8080 Keycloak, 8081 resource server, 8082 OIDC client, 8083 SCIM
  provisioning) means changing the realm export, the application properties and the README together.
