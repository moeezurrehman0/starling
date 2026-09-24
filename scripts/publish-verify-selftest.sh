#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Two-sided self-test for scripts/publish-verify.sh.
#
#   scripts/publish-verify-selftest.sh
#
# WHY THIS EXISTS
#
# The check this tests exists because a green tick meant nothing in S60. A check
# written to catch that, which itself cannot fail, would be strictly worse than
# no check -- it would convert an unknown into a false assurance.
#
# This session has already produced two assertions that were accepted, ran, and
# could never have fired:
#
#   * `gh pr list --label 'autorelease: pending'` returns 0 while the label is
#     demonstrably attached, because --label routes through a lagging index.
#   * `docker buildx imagetools inspect --format '{{.Manifest.Digest}}'` is
#     accepted and ignored, printing the default block.
#
# Both looked correct and both passed. Neither would ever have failed. So every
# branch below is asserted in the direction where it MUST fail, against a stub
# `docker` placed ahead of the real one on PATH.
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."

STUB=$(mktemp -d)
trap 'rm -rf "${STUB}"' EXIT

# A registry in which gateway and user-service exist and tweet-service does not.
cat > "${STUB}/docker" <<'STUB'
#!/usr/bin/env bash
# Only `buildx imagetools inspect <image>` is used by the script under test.
image="${4:-}"
case "$image" in
  *[/]gateway:*|*[/]user-service:*)
    cat <<OUT
Name:      ${image}
MediaType: application/vnd.oci.image.index.v1+json
Digest:    sha256:1111111111111111111111111111111111111111111111111111111111111111
OUT
    exit 0;;
  *[/]no-digest-service:*)
    # The failure mode that matters: a command that succeeds and prints
    # something plausible that is not a digest.
    echo "Name:      ${image}"
    echo "MediaType: application/vnd.oci.image.index.v1+json"
    exit 0;;
  *)
    echo "ERROR: ${image}: not found" >&2
    exit 1;;
esac
STUB
chmod +x "${STUB}/docker"
export PATH="${STUB}:${PATH}"

BASE=ghcr.io/example/starling
SHA=deadbeef
pass=0
fail=0

# Runs the script and asserts its exit status and, optionally, that its combined
# output contains a string. `run` never aborts the suite on a non-zero exit.
check() {
  local name="$1" want_rc="$2" want_text="$3"; shift 3
  local out rc=0
  out=$(env "$@" IMAGE_BASE="$BASE" SHA="$SHA" \
    ./scripts/publish-verify.sh 2>&1) || rc=$?
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

echo "-- passing direction --"

check "a selected service that really is in the registry" 0 "resolve in the registry" \
  SERVICES='["gateway"]' MATRIX_RESULT=success

check "several selected services, all present" 0 "All 2 selected" \
  SERVICES='["gateway","user-service"]' MATRIX_RESULT=success

check "an empty selection with a correctly skipped matrix" 0 "nothing to publish" \
  SERVICES='[]' MATRIX_RESULT=skipped

echo "-- failing direction (the point of this file) --"

# The S60 case exactly: services were selected, the matrix did not run, and the
# workflow would otherwise be green.
check "selected services but the matrix was skipped" 1 "reported 'skipped'" \
  SERVICES='["gateway"]' MATRIX_RESULT=skipped

check "selected services but the matrix was cancelled" 1 "reported 'cancelled'" \
  SERVICES='["gateway"]' MATRIX_RESULT=cancelled

check "selected services but the matrix failed" 1 "reported 'failure'" \
  SERVICES='["gateway"]' MATRIX_RESULT=failure

# The matrix claims success, but the registry has never heard of the image.
check "matrix claims success for an image that does not exist" 1 "published nothing" \
  SERVICES='["tweet-service"]' MATRIX_RESULT=success

check "one of several services is missing" 1 "MISSING  tweet-service" \
  SERVICES='["gateway","tweet-service"]' MATRIX_RESULT=success

# inspect exits 0 and prints a plausible block containing no digest. This is the
# shape that defeated the --format flag; a laxer parse would call this a pass.
check "inspect succeeds but prints no digest" 1 "MISSING  no-digest-service" \
  SERVICES='["no-digest-service"]' MATRIX_RESULT=success

# An empty selection must not excuse a matrix that ran anyway -- that would mean
# the job guard and this check disagree about what was selected.
check "empty selection but the matrix ran" 1 "rather than 'skipped'" \
  SERVICES='[]' MATRIX_RESULT=success

check "SERVICES is not a JSON array" 1 "not a JSON array" \
  SERVICES='gateway' MATRIX_RESULT=success

echo
printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
