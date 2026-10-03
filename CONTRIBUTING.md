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
- **Every module ships an SBOM**, `target/bom.json` in CycloneDX format, attached to the artifact with
  the classifier `cyclonedx`. It lists the compile and runtime dependencies.
- **`.mvn/jvm.config`** silences the schema validator inside the CycloneDX plugin, which warns about
  keywords of the CycloneDX schema it does not know (`meta:enum`, `deprecated`) on every build. The file
  takes JVM arguments only, so this is where its one line is explained.
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
- [`conformance.yml`](.github/workflows/conformance.yml) runs the four conformance plans against the
  suite, one job per plan, on demand. The suite and nginx images are pinned by digest in the
  workflow (`release-v5.3.1`), a run can name other images. Once a suite release contains the fix
  for the poll race of `release-v5.3.1`, pin that release and enable the `schedule` trigger for a
  nightly run.
