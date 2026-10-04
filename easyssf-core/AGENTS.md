# easyssf-core

The vocabulary of SSF, shared by receivers and, later, transmitters. Depends on the JDK only;
keep it that way.

- Everything here is an immutable value object or a constants holder. Records copy their
  collections in the compact constructor (`SsfCollections.copyOf`) and keep the raw claims as
  received next to the typed accessors (`SsfSubject.raw()`, `SsfSubjectIdentifier.claims()`), so
  nothing a transmitter sends is lost.
- `SsfEventTypes` holds the event type URIs of SSF, CAEP, RISC and SCIM Events and their aliases.
  `SsfSubjectIdentifier` knows the formats of RFC 9493 and `scim` by name and represents unknown
  ones unchanged. `SsfSubjectIdentifiers` builds identifiers for tests and stream requests.
- `org.easyssf.core.scim` is what is *in* a SCIM Event SET (subject, operation, typed payload).
  Behaviour against a SCIM service provider does not belong here; it would be a module of its own.
- `module-info.java` exports every package; add the line for a new one.
- Tests: `SsfValueObjectsTests` checks immutability of the records, each type has `<Type>Tests`.
