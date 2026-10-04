# easyssf-receiver-spring-boot-autoconfigure

The Spring Boot integration: properties, auto-configurations, push endpoint, resource server and
OIDC client support. The starter module only depends on this one.

- Properties live in `SsfReceiverProperties` (receiver-wide, `easyssf.receiver.*`) and
  `SsfTransmitterProperties` (per transmitter, repeated under
  `easyssf.receiver.transmitters.<name>.*`). A new property gets a javadoc sentence (it becomes
  the configuration metadata description), a default that matches the receiver's, and a row in
  the README configuration table.
- One auto-configuration class per concern in `autoconfigure`, registered in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Every bean
  is `@ConditionalOnMissingBean` so applications can replace it; optional integrations are
  guarded by `@ConditionalOnClass` and a `*.enabled` property. Spring Security, OAuth2 client and
  JDBC are optional dependencies; the module must start without them.
- `SsfTransmitterFactory` is where receiver settings are applied to the library objects; a new
  setter in `easyssf-receiver` is wired there.
- Tests use `ApplicationContextRunner` (`SsfReceiverAutoConfigurationTests`) with
  `TestTransmitter`. Mockito is attached as a Java agent through surefire; an attach warning when
  running from an IDE is harmless.
- The Spring Boot version is managed by the root POM (`spring-boot.version`); Dependabot bumps it.
