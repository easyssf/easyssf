# Contributing to easyssf

Thanks for your interest. The [README](README.md#build) describes how to build the project and
format the sources; this file describes what happens around a change.

## Pull requests

Every pull request refers to a [GitHub issue](https://github.com/easyssf/easyssf/issues) that
describes the problem or the feature, so that the discussion of what to do and why happens before
the code is written and stays findable afterwards. Open the issue first if there is none, and name it
in the description of the pull request (`Closes #123`). The pull requests Dependabot opens are the
exception.

## Build artifacts

- **Published POMs are flattened** (`flatten-maven-plugin`, mode `oss`): the POM that goes into a
  jar's artifact has no parent, no imported BOM and every version resolved, so consumers do not see the
  build structure of this repository. The generated `.flattened-pom.xml` files are ignored by git and
  removed by `./mvnw clean`. The parent POM itself is not published.
- **Java modules**: `easyssf-core`, `easyssf-test`, `easyssf-receiver` and `easyssf-receiver-jdbc`
  are explicit modules (`module-info.java`, names `org.easyssf.core`, `org.easyssf.test`,
  `org.easyssf.receiver`, `org.easyssf.receiver.jdbc`); Micrometer is `requires static`. The Spring
  Boot modules and the conformance harness are automatic modules, named by the `Automatic-Module-Name`
  manifest entry, which every module sets through the `automatic.module.name` property. A new
  package in an explicit module needs an `exports` line. Surefire runs the tests of the explicit
  modules on the module path; `easyssf-receiver-jdbc` adds `java.naming` for test compilation
  because H2's data source implements a `javax.naming` interface.
- **Every module ships an SBOM**, `target/bom.json` in CycloneDX format, attached to the artifact with
  the classifier `cyclonedx`. It lists the compile and runtime dependencies.
- **`.mvn/jvm.config`** silences the schema validator inside the CycloneDX plugin, which warns about
  keywords of the CycloneDX schema it does not know (`meta:enum`, `deprecated`) on every build. The file
  takes JVM arguments only, so this is where its one line is explained.
- **Database tests**: the JDBC stores are tested on H2 by the normal build and on PostgreSQL in a
  Testcontainers container by the tests tagged `database`, which the normal build excludes:
  `./mvnw -pl easyssf-receiver-jdbc -Pdatabase-tests verify` runs them (Docker required). CI runs
  them in the `database` job.
- **Test logging**: `easyssf-receiver/src/test/resources/logback-test.xml` keeps the tests of the plain
  receiver at INFO; the expected failures of the negative tests are logged with their stack traces at
  DEBUG. The Spring Boot modules use Spring Boot's defaults.
- **Mockito** is attached to the test JVM of the autoconfigure module as a Java agent (surefire
  `argLine`, path from the dependency plugin), so it does not attach itself at runtime, which the JDK
  warns about. Tests started from an IDE do not get the agent and show that warning; it is harmless.

## Continuous integration

GitHub Actions, see [`.github/workflows`](.github/workflows):

- [`ci.yml`](.github/workflows/ci.yml) builds and tests every module on a push to `main` (and on
  demand), on Java 21 and 25, and on the newest JDK (27) as a job that does not fail the build; it
  passes `-Dbytebuddy.experimental=true` to the tests in case Mockito's Byte Buddy does not know the
  class file version yet. It is the normal build, so the conformance tests are not part of it. To run
  it for pull requests as well, enable the `pull_request` trigger in the file.
- `ci.yml` also runs the database tests of `easyssf-receiver-jdbc` against PostgreSQL, in a job of
  its own.
- [`release.yml`](.github/workflows/release.yml) publishes a release, see below.
- [`conformance.yml`](.github/workflows/conformance.yml) runs the four conformance plans against the
  suite, one job per plan, on demand. The suite and nginx images are the ones `ConformanceSettings` of
  `easyssf-receiver-spring-boot-conformance-tests` names, the single place to change them (the suite's `latest` build until
  a release contains the SSF test fixes, then that release pinned by digest); a run can name other
  images. Once a suite release contains the fix
  for the poll race of `release-v5.3.1`, pin that release and enable the `schedule` trigger for a
  nightly run.

## Releasing

Releases go to [Maven Central](https://central.sonatype.com) under the namespace `org.easyssf`. The
`release` profile of the root POM builds what Central requires: sources and javadoc jars, GPG
signatures for every file, and `deploy` uploads the whole build as one deployment through the
[`central-publishing-maven-plugin`](https://central.sonatype.org/publish/publish-portal-maven/),
which waits until Central has validated and published it. The parent POM, the examples and the
conformance tests (`maven.deploy.skip`) are built but not published.

The workflow needs four repository secrets: `CENTRAL_USERNAME` and `CENTRAL_PASSWORD`, a user token
generated on the portal (account menu, "Generate User Token"), and `GPG_PRIVATE_KEY` and
`GPG_PASSPHRASE`, the ASCII-armored private key of `oss@easyssf.org` and its
passphrase. The public key is on `keys.openpgp.org` and `keyserver.ubuntu.com`.

To release version `X.Y.Z` from `main`:

1. Set the version in every POM and in the README and website snippets, and update
   `project.build.outputTimestamp` in the root POM to the release date:
   `./mvnw versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false`.
2. Commit, tag `vX.Y.Z` and push the tag: `git tag -s vX.Y.Z -m "X.Y.Z" && git push origin vX.Y.Z`.
   The workflow checks that the tag matches the POM version, runs the tests, signs, publishes, and
   creates the GitHub release with generated notes and the SBOMs attached. The artifacts are on
   Central a few minutes after the workflow finishes; search indexes them within hours.
3. Set the next development version, `./mvnw versions:set -DnewVersion=X.Y+1.0-SNAPSHOT
   -DgenerateBackupPoms=false`, commit and push.

Snapshots of `main` are published by `ci.yml` to the
[Central snapshot repository](https://central.sonatype.com/repository/maven-snapshots/) while the
repository variable `PUBLISH_SNAPSHOTS` is `true`. Consumers add that repository with
`<snapshots><enabled>true</enabled></snapshots>`.

To try the release build locally without signing or uploading:
`./mvnw -Prelease -Dgpg.skip verify`. With the key in the local keyring, set `MAVEN_GPG_PASSPHRASE`
to sign as well; the passphrase never goes on the command line.
