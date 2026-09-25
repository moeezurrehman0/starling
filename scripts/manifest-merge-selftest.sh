#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Two-sided self-test for scripts/manifest-merge.sh.
#
#   scripts/manifest-merge-selftest.sh
#
# WHY THIS EXISTS
#
# The script under test is the control for S69, where a single-platform manifest
# passed a digest read-back, an out-of-matrix verify job and a cosign signature
# because all three asked a question the broken image answered correctly. The
# failure was not that a check was absent; it was that every check present was
# satisfiable by the defect.
#
# So a platform assertion that cannot fail would be worse than no assertion at
# all -- it would convert a known unknown into a stated guarantee. Every branch
# below is therefore driven in the direction where it MUST fail, against a stub
# `docker` on PATH. Two of these cases are the exact shape S69 shipped in: a
# list containing only amd64, and a merge that reports success over a digest set
# that is quietly short an architecture.
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."

STUB=$(mktemp -d)
trap 'rm -rf "${STUB}"' EXIT

# The stub records what `imagetools create` was asked to do, then answers
# `inspect` from a platform list chosen by the tag. Recording the create call is
# what lets the passing case assert that both per-architecture digests actually
# reached the command, rather than only that the script exited 0.
cat > "${STUB}/docker" <<'STUB'
#!/usr/bin/env bash
if [ "${2:-}" = "imagetools" ] && [ "${3:-}" = "create" ]; then
  printf '%s\n' "$*" >> "${STUB_CREATE_LOG}"
  exit 0
fi
if [ "${2:-}" = "imagetools" ] && [ "${3:-}" = "inspect" ]; then
  image="${4:-}"
  echo "Name:      ${image}"
  echo "MediaType: application/vnd.oci.image.index.v1+json"
  echo "Digest:    sha256:aaaa000000000000000000000000000000000000000000000000000000000000"
  echo ""
  case "$image" in
    *:amd64-only)
      echo "Manifests:"
      echo "Platform:  linux/amd64"
      ;;
    *:no-platforms)
      echo "Manifests:"
      ;;
    *)
      echo "Manifests:"
      echo "Platform:  linux/amd64"
      echo "Platform:  linux/arm64"
      ;;
  esac
  exit 0
fi
echo "stub: unexpected docker invocation: $*" >&2
exit 127
STUB
chmod +x "${STUB}/docker"
export PATH="${STUB}:${PATH}"
export STUB_CREATE_LOG="${STUB}/create.log"
export INSPECT_DELAY=0

IMAGE=ghcr.io/example/starling/gateway
AMD=sha256:1111111111111111111111111111111111111111111111111111111111111111
ARM=sha256:2222222222222222222222222222222222222222222222222222222222222222

pass=0
fail=0

# Builds a digest directory from "name=digest" pairs, runs the script against
# it, and asserts the exit status and a substring of the combined output.
check() {
  local name="$1" want_rc="$2" want_text="$3" tag="$4"; shift 4
  local dir out rc=0 pair
  dir=$(mktemp -d "${STUB}/digests.XXXXXX")
  for pair in "$@"; do printf '%s' "${pair#*=}" > "${dir}/${pair%%=*}"; done
  : > "${STUB_CREATE_LOG}"
  out=$(IMAGE="$IMAGE" TAG="$tag" DIGEST_DIR="$dir" \
    ./scripts/manifest-merge.sh 2>&1) || rc=$?
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

check "two architectures merge and the list carries both" 0 "platforms:   linux/amd64 linux/arm64" \
  sha-abc amd64="$AMD" arm64="$ARM"

# Exiting 0 is not enough: the merge has to have been given both digests. A
# script that silently dropped one would pass every check above this line.
if grep -q "$AMD" "${STUB_CREATE_LOG}" && grep -q "$ARM" "${STUB_CREATE_LOG}"; then
  printf 'ok    %s\n' "both per-architecture digests reached imagetools create"
  pass=$((pass + 1))
else
  printf 'FAIL  %s\n%s\n' "both per-architecture digests reached imagetools create" \
    "$(sed 's/^/        /' "${STUB_CREATE_LOG}")"
  fail=$((fail + 1))
fi

check "the list digest is reported for signing" 0 "list digest: sha256:aaaa" \
  sha-abc amd64="$AMD" arm64="$ARM"

echo "-- failing direction (the point of this file) --"

# S69 exactly: the tag resolves, the digest is real, the signature would verify,
# and the image cannot run on half the fleet.
check "the merged list contains only amd64" 1 "missing platform(s): linux/arm64" \
  amd64-only amd64="$AMD" arm64="$ARM"

check "the merged list advertises no platforms at all" 1 "missing platform(s): linux/amd64 linux/arm64" \
  no-platforms amd64="$AMD" arm64="$ARM"

# A build job that was skipped or failed leaves its artifact absent. Merging
# what is present would publish a list that is quietly short an architecture --
# the S60 shape, where a job that never ran cannot fail its own assertion.
check "one architecture's digest never arrived" 1 "found 1" \
  sha-abc amd64="$AMD"

check "no digests arrived at all" 1 "found 0" \
  sha-abc

check "a digest file holds something that is not a digest" 1 "does not contain a digest" \
  sha-abc amd64="$AMD" arm64="not-a-digest"

# The directory itself missing is different from the directory being empty, and
# a script that conflated them would report a confusing count.
rc=0
out=$(IMAGE="$IMAGE" TAG=sha-abc DIGEST_DIR="${STUB}/nonexistent" \
  ./scripts/manifest-merge.sh 2>&1) || rc=$?
if [ "$rc" -eq 1 ] && grep -qF "no digest directory" <<<"$out"; then
  printf 'ok    %s\n' "a missing digest directory is named as such"
  pass=$((pass + 1))
else
  printf 'FAIL  %s: exit %s\n%s\n' "a missing digest directory is named as such" \
    "$rc" "$(sed 's/^/        /' <<<"$out")"
  fail=$((fail + 1))
fi

# The expected set is configurable, and the assertion has to follow it rather
# than being hard-coded to the two platforms that happen to be in use today.
rc=0
dir=$(mktemp -d "${STUB}/digests.XXXXXX")
printf '%s' "$AMD" > "${dir}/amd64"
out=$(IMAGE="$IMAGE" TAG=amd64-only DIGEST_DIR="$dir" \
  EXPECTED_PLATFORMS="linux/amd64" ./scripts/manifest-merge.sh 2>&1) || rc=$?
if [ "$rc" -eq 0 ]; then
  printf 'ok    %s\n' "a single-platform publish passes when that is what was asked for"
  pass=$((pass + 1))
else
  printf 'FAIL  %s: exit %s\n%s\n' \
    "a single-platform publish passes when that is what was asked for" \
    "$rc" "$(sed 's/^/        /' <<<"$out")"
  fail=$((fail + 1))
fi

echo ""
echo "${pass} passed, ${fail} failed"
[ "$fail" -eq 0 ]
