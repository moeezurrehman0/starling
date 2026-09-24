#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Two-sided self-test for scripts/release-verify.sh.
#
#   scripts/release-verify-selftest.sh
#
# WHY THIS EXISTS
#
# The assertion under test was written because release-please was silently
# broken for the entire life of this repository and every run was green. A test
# that only exercises the healthy path would reproduce that failure one level
# up: a check that passes because it cannot fail.
#
# `gh` is stubbed on PATH. `git` is not stubbed -- each case builds a real
# throwaway repository, because the progress assertion turns on what a commit
# actually changed, and a stub would let the test agree with a wrong answer.
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="${ROOT}/scripts/release-verify.sh"

WORK=$(mktemp -d)
trap 'rm -rf "${WORK}"' EXIT

STUB="${WORK}/bin"
mkdir -p "$STUB"
export PATH="${STUB}:${PATH}"

# The stub reads its verdicts from the environment so each case can pose a
# different repository without rewriting the file.
#   STUB_PENDING_PR=1   a merged release PR is still labelled pending
#   STUB_LATEST=vX.Y.Z  newest release ("" for a repo that never released)
#   STUB_RELEASES=...   space-separated tags that `gh release view` will find
cat > "${STUB}/gh" <<'STUB'
#!/usr/bin/env bash
case "$1 $2" in
  "pr list")
    if [ "${STUB_PENDING_PR:-0}" = "1" ]; then
      echo '[{"number":18,"title":"chore: release main","labels":[{"name":"autorelease: pending"}]}]'
    else
      echo '[{"number":17,"title":"fix: something","labels":[{"name":"bug"}]}]'
    fi
    exit 0;;
  "release list")
    if [ -n "${STUB_LATEST:-}" ]; then
      printf '%s\n' "${STUB_LATEST}"
    fi
    exit 0;;
  "release view")
    for t in ${STUB_RELEASES:-}; do
      [ "$t" = "$3" ] && exit 0
    done
    echo "release not found" >&2; exit 1;;
esac
exit 0
STUB
chmod +x "${STUB}/gh"

pass=0
fail=0

# Builds a throwaway repo whose HEAD either does or does not bump the manifest.
#   make_repo <version> <bump|no-bump>
make_repo() {
  local version="$1" mode="$2"
  local dir; dir=$(mktemp -d "${WORK}/repo.XXXXXX")
  (
    cd "$dir"
    git init -q .
    git config user.email t@example.com
    git config user.name test
    mkdir -p .github
    if [ "$mode" = "bump" ]; then
      printf '{".":"0.0.1"}\n' > .github/.release-please-manifest.json
      echo seed > seed.txt
      git add -A && git commit -qm "chore: seed"
      printf '{".":"%s"}\n' "$version" > .github/.release-please-manifest.json
      git add -A && git commit -qm "chore: release main"
    else
      printf '{".":"%s"}\n' "$version" > .github/.release-please-manifest.json
      git add -A && git commit -qm "chore: seed"
      echo change > other.txt
      git add -A && git commit -qm "fix: unrelated"
    fi
  )
  echo "$dir"
}

# check <name> <repo-dir> <want-rc> <want-text> [ENV=VAL ...]
check() {
  local name="$1" dir="$2" want_rc="$3" want_text="$4"; shift 4
  local out rc=0
  out=$(cd "$dir" && env REPO=example/starling "$@" "$SCRIPT" 2>&1) || rc=$?
  if [ "$rc" -ne "$want_rc" ]; then
    printf 'FAIL  %s: expected exit %s, got %s\n%s\n' \
      "$name" "$want_rc" "$rc" "$(sed 's/^/        /' <<<"$out")"
    fail=$((fail + 1)); return
  fi
  if [ -n "$want_text" ] && ! grep -qF "$want_text" <<<"$out"; then
    printf 'FAIL  %s: exit %s was right but output lacked %q\n%s\n' \
      "$name" "$rc" "$want_text" "$(sed 's/^/        /' <<<"$out")"
    fail=$((fail + 1)); return
  fi
  printf 'ok    %s\n' "$name"
  pass=$((pass + 1))
}

BUMP=$(make_repo 0.3.0 bump)
PLAIN=$(make_repo 0.2.1 no-bump)

echo "-- passing direction --"

check "a release commit that really produced its tag" "$BUMP" 0 "Progress confirmed" \
  RELEASES_CREATED=true RELEASE_TAG=v0.3.0 \
  STUB_LATEST=v0.3.0 STUB_RELEASES=v0.3.0

check "an ordinary commit, manifest agrees with newest release" "$PLAIN" 0 "agrees with release" \
  RELEASES_CREATED=false RELEASE_TAG= STUB_LATEST=v0.2.1

check "an ordinary commit on a repo that has never released" "$PLAIN" 0 "never released" \
  RELEASES_CREATED=false RELEASE_TAG= STUB_LATEST=

echo "-- failing direction (the point of this file) --"

# S59 exactly: the version was cut, the manifest moved, nothing was released,
# and every consistency assertion is satisfied because there is no release to
# disagree with. Only the progress assertion speaks here.
check "S59: manifest bumped, no release created" "$BUMP" 1 "This is S59 exactly" \
  RELEASES_CREATED=false RELEASE_TAG= STUB_LATEST= STUB_RELEASES=

check "S59 variant: bumped, nothing released, older release exists" "$BUMP" 1 "This is S59 exactly" \
  RELEASES_CREATED=false RELEASE_TAG= STUB_LATEST=v0.2.1 STUB_RELEASES=v0.2.1

check "the tag created does not match the version cut" "$BUMP" 1 "not 'v0.3.0'" \
  RELEASES_CREATED=true RELEASE_TAG=v0.2.9 \
  STUB_LATEST=v0.2.9 STUB_RELEASES=v0.2.9

# The action claims it created a release; GitHub disagrees. Trusting the step's
# own output is what this guards against.
check "action claims a release GitHub does not have" "$BUMP" 1 "GitHub has no" \
  RELEASES_CREATED=true RELEASE_TAG=v0.3.0 \
  STUB_LATEST=v0.3.0 STUB_RELEASES=

check "a merged release PR is still labelled pending" "$PLAIN" 1 "autorelease: pending" \
  RELEASES_CREATED=false RELEASE_TAG= STUB_PENDING_PR=1 STUB_LATEST=v0.2.1

check "manifest and newest release have diverged" "$PLAIN" 1 "have diverged" \
  RELEASES_CREATED=false RELEASE_TAG= STUB_LATEST=v0.9.9

echo
printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
