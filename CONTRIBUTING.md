# Contributing to easyssf

Thanks for your interest. The [README](README.md#build) describes how to build the project and
format the sources; this file describes what happens around a change. [`AGENTS.md`](AGENTS.md)
summarises the layout and conventions of the code for coding agents (and is a fine start for
humans too); the modules with rules of their own have one as well.

## Pull requests

Every pull request refers to a [GitHub issue](https://github.com/easyssf/easyssf/issues) that
describes the problem or the feature, so that the discussion of what to do and why happens before
the code is written and stays findable afterwards. Open the issue first if there is none, and name it
in the description of the pull request (`Closes #123`). The pull requests Dependabot opens are the
exception.

`main` is protected by a [ruleset](.github/rulesets/main.json) (Settings > Rules): every change
arrives through a pull request whose `status` check of the CI is green, force pushes and deletion are
blocked, and the head branch is deleted on merge. No review is required while the project has one
maintainer. Repository admins may bypass the ruleset, which the release steps below use for the
version commits; everything else goes through a pull request as well. The JSON file in the
repository is the documentation of the ruleset, update it when the rules change (the Rules page can
import it).

Every commit carries a [Developer Certificate of Origin](https://developercertificate.org) sign-off,
the `Signed-off-by: Name <email>` trailer that `git commit -s` adds: it states that you have the right
to submit the change under the project's license. The CI checks it for every commit of a pull request
(merge commits and Dependabot's commits excepted) and tells you how to add missing ones
(`git rebase --signoff`).

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
- **Maven 3.9.x**: the wrapper (`.mvn/wrapper/maven-wrapper.properties`) stays on Maven 3.9 for now.
  Maven 3.10 uses Resolver 2, which writes `_remote.repositories` marker files (and their checksums)
  into the staging directory the `central-publishing-maven-plugin` bundles, and Central rejects the
  bundle with "Bundle has content that does NOT have a .pom file"; the first attempt to release 0.2.0
  failed that way. Dependabot ignores minor and major Maven updates for that reason. Try 3.10 again
  once the plugin (0.11.0 as of 2026-10-04) excludes those files: build the bundle locally with
  `./mvnw -Prelease -Dgpg.skip -DskipTests deploy` against dummy credentials and check
  `target/central-publishing/central-bundle.zip` for `_remote.repositories`. The settings step of the
  workflows that scopes the Central credentials (`repositoryOrigins`, needed by 3.10) stays: Maven 3.9
  only warns about the unknown tag.
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

- [`ci.yml`](.github/workflows/ci.yml) runs for every push to `main` and every pull request, the
  cheap checks first so that a failure costs little:
  1. `changes` classifies the files the change touches; `sign-off` checks the DCO trailers of a pull
     request; `docs` checks the links ([lychee](https://github.com/lycheeverse/lychee),
     [`.lychee.toml`](.lychee.toml)) and the spelling ([typos](https://github.com/crate-ci/typos),
     [`_typos.toml`](_typos.toml)) of the Markdown files when any changed.
  2. `build`, only when something but documentation changed: the formatting check, then the
     framework-free modules with their tests on Java 21. Its jars go to the jobs behind as an
     artifact, so they do not build them again.
  3. Behind `build`: `spring` (the Spring Boot modules on Java 21), `jdk` (every module on Java 25,
     and on the newest JDK as a job that does not fail the build; it passes
     `-Dbytebuddy.experimental=true` in case Mockito's Byte Buddy does not know the class file
     version yet), and `database` (the stores of `easyssf-receiver-jdbc` on PostgreSQL).
  4. `status` is green when every job that had to run succeeded; skipped jobs count as green. It is
     the one check to require in the branch protection of `main`, the others come and go with the
     change.
  A pull request from a fork runs with a read-only token and without secrets. Who may trigger a run
  is decided under Settings > Actions > General ("Fork pull request workflows", "Approval for running
  workflows from outside collaborators"). The conformance tests are not part of it.
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
   `./mvnw versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false`. Turn the `Unreleased`
   section of `CHANGELOG.md` into the section of `X.Y.Z` with the date; the workflow publishes
   that section as the release notes and fails without it.
2. Commit, tag `vX.Y.Z` and push the tag: `git tag -s vX.Y.Z -m "X.Y.Z" && git push origin vX.Y.Z`.
   The push to `main` uses the admin bypass of the ruleset.
   The workflow checks that the tag matches the POM version, runs the tests, signs, publishes, and
   creates the GitHub release with generated notes and the SBOMs attached. The artifacts are on
   Central a few minutes after the workflow finishes; search indexes them within hours.
3. Set the next development version, `./mvnw versions:set -DnewVersion=X.Y+1.0-SNAPSHOT
   -DgenerateBackupPoms=false`, commit and push.

Snapshots of `main` are published by `ci.yml` to the
[Central snapshot repository](https://central.sonatype.com/repository/maven-snapshots/) while the
repository variable `PUBLISH_SNAPSHOTS` is `true`. Consumers add that repository with
`<snapshots><enabled>true</enabled></snapshots>`.

Both workflows let `setup-java` write the `settings.xml` with the Central token as the server
`central`, and then add `<repositoryOrigins>https://central.sonatype.com</repositoryOrigins>` to that
server. Maven 3.10 offers the credentials of a settings server only to repositories whose origin it
can associate with the server id, and `central` is also the id of Maven Central, so without the
origin the uploads to `central.sonatype.com` get no credentials and fail with `401`. (The alternative,
`-Dmaven.repository.credentialScope=id`, restores the id-only matching of Maven 3.9 for every
server.)

To try the release build locally without signing or uploading:
`./mvnw -Prelease -Dgpg.skip verify`. With the key in the local keyring, set `MAVEN_GPG_PASSPHRASE`
to sign as well; the passphrase never goes on the command line.
