#!/usr/bin/env bash
# The release steps of CONTRIBUTING.md, one subcommand each. Releases go through a pull request like every
# other change to main; only the tag is pushed directly, and tags are not covered by the ruleset.
#
#   etc/release.sh prepare 0.3.0          from an up-to-date main: create the branch release/0.3.0 and set the version
#                                         in the POMs and README snippets, the release timestamp, and turn the
#                                         Unreleased section of CHANGELOG.md into the release's. Nothing is committed:
#                                         review the diff and write the lead paragraph of the section
#   etc/release.sh propose [0.4.0] [--dry-run]  on that branch: commit "Release 0.3.0", then set the next development version
#                                         (0.4.0-SNAPSHOT, by default the next minor) and commit "Start 0.4.0", push
#                                         the branch and open the pull request. Merge it with a merge commit or a
#                                         rebase, not a squash: the release commit has to reach main as it is
#   etc/release.sh release [0.3.0]        on main after the merge: create the signed tag v0.3.0 on the commit
#                                         "Release 0.3.0" and push the tag, which starts the release workflow
#
# Needs git, the Maven wrapper, python3 (for the changelog) and gh (for the pull request and the workflow).
set -euo pipefail

cd "$(dirname "$0")/.."

fail() { echo "error: $*" >&2; exit 1; }

version_of_pom() {
  ./mvnw -q -N org.apache.maven.plugins:maven-help-plugin:3.5.1:evaluate -Dexpression=project.version -DforceStdout
}

semver() {
  [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "'$1' is not a version of the form X.Y.Z"
}

next_minor() {
  local major minor
  IFS=. read -r major minor _ <<< "$1"
  echo "$major.$((minor + 1)).0"
}

require_clean() {
  [ -z "$(git status --porcelain --untracked-files=no)" ] || fail "the working tree has changes, commit or stash them first"
}

require_up_to_date_main() {
  [ "$(git branch --show-current)" = "main" ] || fail "run this on main (currently on $(git branch --show-current))"
  git fetch -q origin
  [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || fail "main differs from origin/main, pull or push first"
}

prepare() {
  local version=$1
  semver "$version"
  require_up_to_date_main
  require_clean
  local current; current=$(version_of_pom)
  [[ "$current" == *-SNAPSHOT ]] || fail "the POMs are at $current, not at a snapshot"
  grep -q '^## \[Unreleased\]' CHANGELOG.md || fail "CHANGELOG.md has no Unreleased section"
  local branch="release/$version"
  git rev-parse -q --verify "$branch" >/dev/null && fail "the branch $branch exists already"
  git rev-parse -q --verify "refs/tags/v$version" >/dev/null && fail "the tag v$version exists already"

  git checkout -q -b "$branch"
  echo "setting the version $version (from $current) on $branch"
  ./mvnw -q versions:set -DnewVersion="$version" -DgenerateBackupPoms=false
  local stamp; stamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  sed -i.bak "s|<project.build.outputTimestamp>[^<]*</project.build.outputTimestamp>|<project.build.outputTimestamp>$stamp</project.build.outputTimestamp>|" pom.xml && rm pom.xml.bak

  # the dependency snippets of the easyssf artifacts; other artifacts (the Quarkus extension) keep theirs.
  # The changelog carries the local date of the release, the POM timestamp UTC.
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
body = s[start + len('## [Unreleased]\n'):end].strip()
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
  echo "prepared $version on $branch:"
  git diff --stat | tail -1
  grep -rnE '^\s*<version>[0-9][^<]*</version>' --include='*.md' . | grep -v target | grep -v "<version>$version</version>" \
    | sed 's/^/  other dependency snippets, check by hand: /' || true
  echo
  echo "review the diff, write the lead paragraph of the new CHANGELOG section (it becomes the release notes),"
  echo "then: etc/release.sh propose [$(next_minor "$version")]"
}

propose() {
  local next="" dry_run=""
  for arg in "$@"; do
    case "$arg" in
      --dry-run) dry_run=$arg ;;
      *) next=$arg ;;
    esac
  done
  local branch; branch=$(git branch --show-current)
  [[ "$branch" == release/* ]] || fail "run this on the branch 'prepare' created (currently on $branch)"
  local version=${branch#release/}
  [ "$(version_of_pom)" = "$version" ] || fail "the POMs are at $(version_of_pom), the branch says $version"
  grep -q "^## \[$version\] - " CHANGELOG.md || fail "CHANGELOG.md has no section for $version"
  [ -n "$(git status --porcelain --untracked-files=no)" ] || fail "nothing to commit; was 'prepare' run?"
  next=${next:-$(next_minor "$version")}
  semver "$next"

  local notes; notes=$(release_notes "$version")
  [ -n "$notes" ] || fail "the CHANGELOG section of $version is empty"
  echo "$notes" | head -3 | grep -q '[a-z]' || echo "warning: the section of $version has no lead paragraph before its first heading"

  git commit -q -s -am "Release $version"
  ./mvnw -q versions:set -DnewVersion="$next-SNAPSHOT" -DgenerateBackupPoms=false
  git commit -q -s -am "Start $next"
  echo "committed on $branch:"
  git log --oneline -2 | sed 's/^/  /'
  if [ "$dry_run" = "--dry-run" ]; then
    echo "dry run: not pushing, not opening the pull request"
    return
  fi
  git push -u origin "$branch"
  local body
  body=$(printf 'Release %s, then %s-SNAPSHOT.\n\n**Merge with a merge commit or a rebase, not a squash**: the commit "Release %s" has to reach `main` unchanged, `etc/release.sh release` tags it there.\n\nRelease notes, from CHANGELOG.md:\n\n%s' "$version" "$next" "$version" "$notes")
  gh pr create --base main --head "$branch" --title "Release $version" --body "$body"
  echo
  echo "after the merge, on main: etc/release.sh release $version"
}

release() {
  local version=${1:-}
  require_up_to_date_main
  require_clean
  if [ -z "$version" ]; then
    # pipelines read to the end: an early exit would SIGPIPE git and, with pipefail, abort the script
    version=$(git log --format=%s origin/main | awk '/^Release [0-9.]+$/ && !found { print substr($0, 9); found = 1 }')
    [ -n "$version" ] || fail "no commit 'Release X.Y.Z' on main; name the version"
  fi
  semver "$version"
  git rev-parse -q --verify "refs/tags/v$version" >/dev/null && fail "the tag v$version exists already"
  local commit; commit=$(git log --format='%H %s' origin/main | awk -v s="Release $version" '$0 ~ " "s"$" && !found { print $1; found = 1 }')
  [ -n "$commit" ] || fail "no commit 'Release $version' on main. Was the pull request squashed? Then main has no commit with the version $version to tag; restore the two commits of the release branch with a new pull request (git cherry-pick) and merge it without squashing"
  local pom_version; pom_version=$(git show "$commit:pom.xml" | awk '/<version>[0-9.]+<\/version>/ && !found { sub(/.*<version>/, ""); sub(/<\/version>.*/, ""); print; found = 1 }')
  [ "$pom_version" = "$version" ] || fail "the commit $commit carries the version $pom_version in pom.xml, not $version"
  git show "$commit:CHANGELOG.md" | grep -q "^## \[$version\] - " || fail "CHANGELOG.md at $commit has no section for $version"

  echo "tagging $(git log --oneline -1 "$commit") as v$version (signed) and pushing the tag"
  git tag -s "v$version" -m "$version" "$commit"
  git push origin "v$version"
  echo "pushed; the release workflow builds, tests, signs and publishes $version"
  if command -v gh >/dev/null; then
    sleep 20
    gh run watch "$(gh run list --workflow release.yml --limit 1 --json databaseId --jq '.[0].databaseId')" --exit-status \
      && echo "release $version published" || fail "the release workflow failed, see 'gh run view'"
  fi
}

release_notes() {
  awk -v v="$1" '/^## \[/ { in_section = ($0 ~ "^## \\[" v "\\]") ; next } in_section && !/^\[/ { print }' CHANGELOG.md \
    | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}'
}

case "${1:-}" in
  prepare) [ $# -eq 2 ] || fail "usage: etc/release.sh prepare X.Y.Z"; prepare "$2" ;;
  propose) shift; propose "$@" ;;
  release) release "${2:-}" ;;
  *) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
