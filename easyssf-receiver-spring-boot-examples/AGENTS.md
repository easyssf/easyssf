# easyssf-receiver-spring-boot-examples

A resource server and an OIDC client that receive events from a Keycloak transmitter, with a
Docker Compose setup for Keycloak. Not published. The README walks through running them.

- The examples contain no SSF code apart from configuration (and a logging handler). Keep them
  that way: a feature that needs code in an example is a feature the starter is missing.
- Before `docker compose up` in `keycloak/`, check for an existing compose project of the same
  name: `docker ps -a --filter label=com.docker.compose.project=easyssf-examples`. The compose
  file sets `name: easyssf-examples` for that reason; never remove the `name`, and never run
  `down -v` or delete volumes you did not create. Developer machines tend to have several
  unrelated projects in folders called `keycloak`.
- The realm `ssf-demo` is imported from `keycloak/`; change the realm export there, not in a
  running Keycloak.
- Changing the ports (8080 Keycloak, 8081 resource server, 8082 OIDC client) means changing the
  realm export, the application properties and the README together.
