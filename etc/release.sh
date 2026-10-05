#!/usr/bin/env bash
# The release steps of CONTRIBUTING.md, one subcommand each. Run from the root of the repository, on main.
#
#   etc/release.sh prepare 0.3.0     set the version in the POMs and README snippets, the release timestamp,
#                                    and turn the Unreleased section of CHANGELOG.md into the release's; nothing
#                                    is committed, review the diff
#   etc/release.sh release           commit "Release X.Y.Z", create the signed tag vX.Y.Z and push both together;
#                                    the release workflow does the rest (asks before pushing, --yes skips that)
#   etc/release.sh next 0.4.0        set the next development version X.Y.Z-SNAPSHOT, commit "Start X.Y.Z", push
#
# Needs git, the Maven wrapper, python3 (for the changelog) and, for watching the workflow, gh.
set -euo pipefail

cd "$(dirname "$0")/.."

fail() { echo "error: $*" >&2; exit 1; }

version_of_pom() {
  ./mvnw -q -N org.apache.maven.plugins:maven-help-plugin:3.5.1:evaluate -Dexpression=project.version -DforceStdout
}

require_main() {
  [ "$(git branch --show-current)" = "main" ] || fail "run this on main (currently on $(git branch --show-current))"
  git fetch -q origin
  [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || fail "main differs from origin/main, pull or push first"
}

require_clean() {
  [ -z "$(git status --porcelain --untracked-files=no)" ] || fail "the working tree has changes, commit or stash them first"
}

semver() {
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "'$1' is not a version of the form X.Y.Z"
}

prepare() {
  local version=$1
  semver "$version"
  require_main
  require_clean
  local current; current=$(version_of_pom)
  [[ "$current" == *-SNAPSHOT ]] || fail "the POMs are at $current, not at a snapshot; is a release already prepared?"
  grep -q '^## \[Unreleased\]' CHANGELOG.md || fail "CHANGELOG.md has no Unreleased section"

  echo "setting the version $version (from $current)"
  ./mvnw -q versions:set -DnewVersion="$version" -DgenerateBackupPoms=false
  local stamp; stamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  sed -i.bak "s|<project.build.outputTimestamp>[^<]*</project.build.outputTimestamp>|<project.build.outputTimestamp>$stamp</project.build.outputTimestamp>|" pom.xml && rm pom.xml.bak

  # the dependency snippets of the easyssf artifacts; other artifacts (the Quarkus extension) keep theirs
  local previous=${current%-SNAPSHOT}
  # the changelog carries the local date of the release, the POM timestamp UTC
  python3 - "$version" "$(date +%Y-%m-%d)" <<'PY'
import re, sys, pathlib
version, day = sys.argv[1], sys.argv[2]
for path in ['README.md', 'easyssf-receiver-spring-boot-starter/README.md']:
    p = pathlib.Path(path)
    s = p.read_text()
    s = re.sub(r'(<artifactId>easyssf-[a-z-]+</artifactId>\n\s*<version>)[^<]+(</version>)', r'\g<1>' + version + r'\g<2>', s)
    p.write_text(s)

p = pathlib.Path('CHANGELOG.md')
s = p.read_text()
start = s.index('## [Unreleased]\n')
end = s.index('\n## [', start + 1)
section = s[start:end]
body = section[len('## [Unreleased]\n'):].strip()
if not body:
    sys.exit('error: the Unreleased section of CHANGELOG.md is empty, nothing to release')
s = s[:start] + f'## [Unreleased]\n\n## [{version}] - {day}\n\n' + body + '\n' + s[end:]
m = re.search(r'^\[Unreleased\]: (https://github\.com/[^/]+/[^/]+)/compare/v([^.]+\.[^.]+\.[^.]+)\.\.\.HEAD$', s, re.M)
if not m:
    sys.exit('error: CHANGELOG.md has no [Unreleased] compare link')
repo, previous = m.group(1), m.group(2)
s = s.replace(m.group(0), f'[Unreleased]: {repo}/compare/v{version}...HEAD\n[{version}]: {repo}/compare/v{previous}...v{version}')
p.write_text(s)
print(f'CHANGELOG.md: Unreleased is now {version} ({day}), previous release v{previous}')
PY

  echo
  echo "prepared $version:"
  git diff --stat | tail -1
  grep -rnE '^\s*<version>[0-9][^<]*</version>' --include='*.md' . | grep -v target | grep -v "<version>$version</version>" \
    | sed 's/^/  other dependency snippets, check by hand: /' || true
  echo
  echo "review the diff and the lead paragraph of the new CHANGELOG section (the release notes), then: etc/release.sh release"
}

release() {
  local yes=${1:-}
  require_main
  local version; version=$(version_of_pom)
  [[ "$version" != *-SNAPSHOT ]] || fail "the POMs are at $version; run 'prepare' first"
  semver "$version"
  grep -q "^## \[$version\] - " CHANGELOG.md || fail "CHANGELOG.md has no section for $version"
  git rev-parse -q --verify "refs/tags/v$version" >/dev/null && fail "the tag v$version exists already"
  [ -n "$(git status --porcelain)" ] || fail "nothing to commit; was 'prepare' run?"

  echo "about to commit and push the release $version:"
  git status --short | sed 's/^/  /'
  echo "  tag v$version (signed), pushed together with main"
  if [ "$yes" != "--yes" ]; then
    read -r -p "continue? [y/N] " answer
    [ "$answer" = "y" ] || fail "aborted, nothing was committed"
  fi
  git commit -q -s -am "Release $version"
  git tag -s "v$version" -m "$version"
  git push origin main "v$version"
  echo "pushed; the release workflow builds, tests, signs and publishes $version"
  if command -v gh >/dev/null; then
    sleep 20
    gh run watch "$(gh run list --workflow release.yml --limit 1 --json databaseId --jq '.[0].databaseId')" --exit-status \
      && echo "release $version published" || fail "the release workflow failed, see 'gh run view'"
  fi
}

next() {
  local version=$1
  semver "$version"
  require_main
  require_clean
  local current; current=$(version_of_pom)
  [[ "$current" != *-SNAPSHOT ]] || fail "the POMs are at $current already"
  ./mvnw -q versions:set -DnewVersion="$version-SNAPSHOT" -DgenerateBackupPoms=false
  git commit -q -s -am "Start $version"
  git push origin main
  echo "main is at $version-SNAPSHOT"
}

case "${1:-}" in
  prepare) [ $# -eq 2 ] || fail "usage: etc/release.sh prepare X.Y.Z"; prepare "$2" ;;
  release) release "${2:-}" ;;
  next) [ $# -eq 2 ] || fail "usage: etc/release.sh next X.Y.Z"; next "$2" ;;
  *) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
