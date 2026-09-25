#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Join the per-architecture images pushed by digest into one manifest list, then
# assert the list contains exactly the platforms we meant to publish.
#
#   IMAGE=ghcr.io/owner/repo/svc TAG=sha-abc DIGEST_DIR=/tmp/digests \
#     scripts/manifest-merge.sh
#
# Writes the list digest to stdout and, when running under Actions, to
# $GITHUB_OUTPUT as `digest`. That digest -- not any per-architecture one -- is
# what gets signed and attested, because it is what a `docker pull` of the tag
# actually resolves to.
#
# WHY THIS EXISTS, AND WHY IT IS A SCRIPT
#
# S69: every image this repository had ever published was amd64-only, because
# neither build-push-action step set `platforms:` and buildx defaults to the
# runner's architecture. The kind cluster is Apple Silicon, so nothing could be
# rehearsed locally. Three separate controls -- the digest read-back, the
# outside-the-matrix verify job, and a cosign signature -- all passed, because
# all three asked whether the tag resolved to the digest we pushed, and it did.
# A manifest with one platform in it, and the wrong one, resolves perfectly.
#
# So the fix is not only "build both architectures". It is to make the platform
# set a thing something asserts. The S69 entry in the gap register was written
# admitting that assertion did not exist and that pinning the expected set was
# the real fix; this is that fix. The expected platforms are named here, in the
# pipeline, where the publish can be failed -- not inferred from whatever the
# build happened to produce, which would agree with itself no matter what.
#
# It is a script rather than a `run:` block for the reason given at the top of
# image-digest-verify.sh: inline YAML under `set -euo pipefail` dies at the
# first failed command substitution and skips every line of error handling
# written below it, which is exactly the case that handling was written for.
# A script can also be run offline against a fixture, and
# manifest-merge-selftest.sh does -- including against a stub registry that
# returns a list containing only amd64, which is the exact state S69 shipped.
# ---------------------------------------------------------------------------
set -euo pipefail

IMAGE="${IMAGE:?IMAGE is required (repository, no tag)}"
TAG="${TAG:?TAG is required}"
DIGEST_DIR="${DIGEST_DIR:?DIGEST_DIR is required}"
EXPECTED_PLATFORMS="${EXPECTED_PLATFORMS:-linux/amd64 linux/arm64}"

# shellcheck source=scripts/registry-lib.sh
. "$(dirname "$0")/registry-lib.sh"

die() { echo "::error::$*" >&2; exit 1; }

[ -d "$DIGEST_DIR" ] || die "no digest directory at ${DIGEST_DIR}"

# Each per-architecture job writes one file whose entire contents are a digest.
# Reading the directory rather than taking a list as an argument means a job
# that was skipped or failed shows up here as a missing file, which the count
# check below turns into a hard failure. Passing the expected digests in would
# have let a silently-absent architecture through -- the S60 shape again, where
# a job that never ran cannot fail its own assertion.
refs=()
for f in "$DIGEST_DIR"/*; do
  [ -f "$f" ] || continue
  d="$(tr -d '[:space:]' < "$f")"
  case "$d" in
    sha256:*) ;;
    *) die "${f} does not contain a digest: '${d}'" ;;
  esac
  refs+=("${IMAGE}@${d}")
done

# Unquoted on purpose, both here and in the loop below: EXPECTED_PLATFORMS is a
# space-separated list and word splitting is the parsing.
# shellcheck disable=SC2086
expected_count="$(printf '%s\n' ${EXPECTED_PLATFORMS} | wc -l | tr -d ' ')"
[ "${#refs[@]}" -eq "$expected_count" ] ||
  die "expected ${expected_count} per-architecture digests in ${DIGEST_DIR}," \
      "found ${#refs[@]}. A build job was skipped or failed, and merging what" \
      "is present would publish a manifest list that is quietly short an" \
      "architecture."

echo "merging ${#refs[@]} images into ${IMAGE}:${TAG}"
docker buildx imagetools create -t "${IMAGE}:${TAG}" "${refs[@]}" ||
  die "could not create the manifest list for ${IMAGE}:${TAG}"

rc=0; inspect_digest "${IMAGE}:${TAG}" || rc=$?
case "$rc" in
  0) ;;
  1) echo "${INSPECT_ERROR}" >&2
     die "${IMAGE}:${TAG} does not resolve after the merge reported success" ;;
  *) echo "${INSPECT_ERROR}" >&2
     die "could not read ${IMAGE}:${TAG} back after the merge. This is not" \
         "evidence that the merge failed; it is the absence of evidence that" \
         "it succeeded." ;;
esac

# The assertion S69 said was missing. `imagetools inspect` prints one
# `Platform:` line per manifest in the list, so this reads the list as a
# consumer would rather than trusting what we asked for.
inspected="$(docker buildx imagetools inspect "${IMAGE}:${TAG}" 2>&1)" ||
  die "could not inspect ${IMAGE}:${TAG} for its platform set"

missing=""
for want in ${EXPECTED_PLATFORMS}; do
  grep -qE "^Platform:[[:space:]]+${want}\$" <<<"$inspected" || missing="${missing} ${want}"
done

if [ -n "$missing" ]; then
  echo "$inspected" | sed 's/^/    /' >&2
  die "${IMAGE}:${TAG} is missing platform(s):${missing}. The tag resolves and" \
      "the digest is real -- this is precisely the state S69 shipped in, and" \
      "a signature over it would have been equally valid and equally useless."
fi

echo "list digest: ${INSPECT_DIGEST}"
echo "platforms:   ${EXPECTED_PLATFORMS}"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "digest=${INSPECT_DIGEST}" >> "$GITHUB_OUTPUT"
fi
