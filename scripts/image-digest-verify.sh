#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Read back one image inside the matrix job and assert it resolves to the digest
# the push step just reported.
#
#   IMAGE=ghcr.io/owner/repo/svc:sha-abc DIGEST=sha256:... \
#     scripts/image-digest-verify.sh
#
# WHY THIS EXISTS
#
# This assertion used to be eight lines inline in publish.yml, under
# `set -euo pipefail`, with the registry read in a command substitution:
#
#     remote=$(docker buildx imagetools inspect "$IMAGE" | awk ...)
#
# which means that when the read failed, the step died right there with the
# registry client's exit code and none of the careful error handling below it
# ever ran. The messages it would have printed were unreachable in the only
# case that produced them. On 2026-09-25 that killed a fanout-worker publish
# two seconds after a successful push, for an image that was in the registry
# with exactly the expected digest.
#
# A failed read is now retried and, if it still fails, named as what it is.
# A mismatch is still a hard failure with no retry -- a registry that answers
# with the wrong digest has answered.
# ---------------------------------------------------------------------------
set -euo pipefail

IMAGE="${IMAGE:?IMAGE is required}"
DIGEST="${DIGEST:?DIGEST is required}"

# shellcheck source=scripts/registry-lib.sh
. "$(dirname "$0")/registry-lib.sh"

die() { echo "::error::$*" >&2; exit 1; }

rc=0; inspect_digest "$IMAGE" || rc=$?

case "$rc" in
  0) ;;
  1)
    echo "${INSPECT_ERROR}" >&2
    die "${IMAGE} does not resolve in the registry, but the push step" \
        "reported digest ${DIGEST}"
    ;;
  *)
    echo "${INSPECT_ERROR}" >&2
    # Deliberately not reported as a push failure. "The image is not there" and
    # "I could not find out whether the image is there" are different findings,
    # and calling the second one the first sends someone to debug a publish
    # that worked. It still fails the build -- absence of evidence is not
    # evidence of success -- but it fails under its own name.
    die "could not read ${IMAGE} back from the registry after" \
        "${INSPECT_ATTEMPTS:-5} attempts. This is not evidence that the push" \
        "failed; it is the absence of evidence that it succeeded."
    ;;
esac

echo "pushed:   ${DIGEST}"
echo "registry: ${INSPECT_DIGEST}"

[ "$INSPECT_DIGEST" = "$DIGEST" ] ||
  die "${IMAGE} resolves to ${INSPECT_DIGEST}, not the digest just pushed" \
      "(${DIGEST})"

echo "Registry agrees with the push step."
