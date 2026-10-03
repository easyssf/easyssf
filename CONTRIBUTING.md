# Contributing to easyssf

Thanks for your interest. The [README](README.md#build) describes how to build the project and
format the sources; this file describes what happens around a change.

## Build artifacts

- **Published POMs are flattened** (`flatten-maven-plugin`, mode `ossrh`): the POM that goes into a
  jar's artifact has no parent, no imported BOM and every version resolved, so consumers do not see the
  build structure of this repository. The generated `.flattened-pom.xml` files are ignored by git and
  removed by `./mvnw clean`. The parent POM itself is not published.

## Continuous integration

GitHub Actions, see [`.github/workflows`](.github/workflows):

- [`ci.yml`](.github/workflows/ci.yml) builds and tests every module on a push to `main` (and on
  demand). It is the normal build, so the conformance tests are not part of it. To run it for pull
  requests as well, enable the `pull_request` trigger in the file.
- [`conformance.yml`](.github/workflows/conformance.yml) runs the four conformance plans against the
  suite, one job per plan, on demand. The suite and nginx images are pinned by digest in the
  workflow (`release-v5.3.1`), a run can name other images. Once a suite release contains the fix
  for the poll race of `release-v5.3.1`, pin that release and enable the `schedule` trigger for a
  nightly run.
