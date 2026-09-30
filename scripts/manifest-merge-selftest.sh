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
#
# THE OUTPUT BELOW IS COPIED FROM A REAL RUN, NOT WRITTEN FROM MEMORY.
#
# The first version of this file was written from memory and got the format
# wrong: it emitted `Platform:` flush-left, where buildx indents it by six
# spaces under `Manifests:`. The script's pattern was anchored the same wrong
# way, so the stub and the code agreed with each other and disagreed with
# reality. Ten tests passed, both directions, and the gate then rejected six
# perfectly good manifest lists on its first real run. See S75.
#
# It also omits nothing this time. A list built with `provenance: mode=max`
# carries `unknown/unknown` attestation manifests interleaved with the real
# ones, and a fixture without them cannot show that the assertion tolerates
# them. Transcribed from run 36183635154, job "Merge and sign (gateway)".
install_stub() {
cat > "${STUB}/docker" <<'STUB'
#!/usr/bin/env bash
if [ "${2:-}" = "imagetools" ] && [ "${3:-}" = "create" ]; then
  printf '%s\n' "$*" >> "${STUB_CREATE_LOG}"
  exit 0
fi
if [ "${2:-}" = "imagetools" ] && [ "${3:-}" = "inspect" ]; then
  image="${4:-}"
  emit_manifest() {
    echo "  Name:        ${image}@sha256:9999999999999999999999999999999999999999999999999999999999999999"
    echo "  MediaType:   application/vnd.oci.image.manifest.v1+json"
    echo "  Platform:    $1"
    echo ""
  }
  echo "Name:      ${image}"
  echo "MediaType: application/vnd.oci.image.index.v1+json"
  echo "Digest:    sha256:aaaa000000000000000000000000000000000000000000000000000000000000"
  echo ""
  echo "Manifests:"
  case "$image" in
    *:amd64-only)
      emit_manifest linux/amd64
      emit_manifest unknown/unknown
      ;;
    *:no-platforms)
      ;;
    *)
      # The real interleaving: each architecture is followed by its
      # attestation manifest, which reports unknown/unknown.
      emit_manifest linux/amd64
      emit_manifest unknown/unknown
      emit_manifest linux/arm64
      emit_manifest unknown/unknown
      ;;
  esac
  exit 0
fi
echo "stub: unexpected docker invocation: $*" >&2
exit 127
STUB
chmod +x "${STUB}/docker"
}
install_stub
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

# The regression test for S75, pinned against bytes rather than a description.
# The gate's first real run rejected six good manifest lists because the
# pattern was anchored flush-left and buildx indents `Platform:`. A stub can
# drift back to agreeing with a wrong pattern; a verbatim transcript cannot.
cat > "${STUB}/docker" <<'REAL'
#!/usr/bin/env bash
if [ "${2:-}" = "imagetools" ] && [ "${3:-}" = "create" ]; then exit 0; fi
cat <<'OUT'
Name:      ghcr.io/moeezurrehman0/starling/gateway:sha-a4dc66d
MediaType: application/vnd.oci.image.index.v1+json
Digest:    sha256:e58f13c747b8bba72819d4b54e8004773b7370335c075b0bc234e18156e4cba1

Manifests: 
  Name:        ghcr.io/moeezurrehman0/starling/gateway:sha-a4dc66d@sha256:91a06545
  MediaType:   application/vnd.oci.image.manifest.v1+json
  Platform:    linux/amd64

  Name:        ghcr.io/moeezurrehman0/starling/gateway:sha-a4dc66d@sha256:71d93a7f
  MediaType:   application/vnd.oci.image.manifest.v1+json
  Platform:    unknown/unknown
  Annotations: 
    vnd.docker.reference.digest: sha256:91a06545
    vnd.docker.reference.type:   attestation-manifest

  Name:        ghcr.io/moeezurrehman0/starling/gateway:sha-a4dc66d@sha256:fa846077
  MediaType:   application/vnd.oci.image.manifest.v1+json
  Platform:    linux/arm64
OUT
REAL
chmod +x "${STUB}/docker"

rc=0
dir=$(mktemp -d "${STUB}/digests.XXXXXX")
printf '%s' "$AMD" > "${dir}/amd64"; printf '%s' "$ARM" > "${dir}/arm64"
out=$(IMAGE="$IMAGE" TAG=sha-a4dc66d DIGEST_DIR="$dir" \
  ./scripts/manifest-merge.sh 2>&1) || rc=$?
if [ "$rc" -eq 0 ]; then
  printf 'ok    %s\n' "verbatim buildx output from run 36183635154 is accepted"
  pass=$((pass + 1))
else
  printf 'FAIL  %s: exit %s -- the pattern disagrees with real buildx output again\n%s\n' \
    "verbatim buildx output from run 36183635154 is accepted" \
    "$rc" "$(sed 's/^/        /' <<<"$out")"
  fail=$((fail + 1))
fi

# Restore the parameterised stub for the remaining cases.
install_stub

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
