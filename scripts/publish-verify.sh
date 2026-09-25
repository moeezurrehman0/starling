#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Assert that the Publish matrix actually published what it selected.
#
#   SERVICES='["gateway"]' MATRIX_RESULT=success \
#   IMAGE_BASE=ghcr.io/owner/repo SHA=<sha> scripts/publish-verify.sh
#
# WHY THIS EXISTS
#
# S60: Publish reported success twice on a repository where it had never once
# pushed an image. Every matrix job had been filtered out, and a skipped job and
# a completed one render as the same green tick.
#
# The per-job digest read-back added in response to that closed less of the hole
# than it looked. It lives *inside* the matrix job, so it verifies nothing at all
# in the exact case that caused S60 -- a job that never ran cannot fail its own
# assertion. A service dropped by the path filter still publishes nothing and
# still reports success.
#
# This runs in a separate job that the path filter cannot skip, and asks two
# questions the matrix cannot answer about itself:
#
#   1. Did the matrix reach a successful conclusion for a non-empty selection?
#      `skipped` and `cancelled` are failures here, which is the S60 case.
#   2. Independently of what the build said, does every selected service's tag
#      resolve in the registry at this commit? This is asked from outside, so it
#      holds even if the push step lied or the job was never created.
#
# An empty selection is a legitimate state -- a docs-only commit publishes
# nothing -- but it is stated out loud rather than passed silently, because
# "nothing to do" and "did nothing" were indistinguishable in S60.
# ---------------------------------------------------------------------------
set -euo pipefail

SERVICES="${SERVICES:?SERVICES (a JSON array) is required}"
MATRIX_RESULT="${MATRIX_RESULT:?MATRIX_RESULT is required}"
IMAGE_BASE="${IMAGE_BASE:?IMAGE_BASE is required}"
SHA="${SHA:?SHA is required}"

die() { echo "::error::$*" >&2; exit 1; }

echo "$SERVICES" | jq -e 'type == "array"' >/dev/null 2>&1 ||
  die "SERVICES is not a JSON array: ${SERVICES}"

count=$(jq 'length' <<<"$SERVICES")

if [ "$count" -eq 0 ]; then
  # The matrix job is `if: services != '[]'`, so GitHub reports it as skipped.
  # Anything else means the guard and this check disagree about what ran.
  [ "$MATRIX_RESULT" = "skipped" ] ||
    die "no services were selected, but the publish matrix reported" \
        "'${MATRIX_RESULT}' rather than 'skipped'"
  echo "No service paths changed at ${SHA}; nothing to publish. Matrix correctly skipped."
  exit 0
fi

[ "$MATRIX_RESULT" = "success" ] ||
  die "${count} service(s) were selected for publication but the matrix" \
      "reported '${MATRIX_RESULT}'. Selected: $(jq -c . <<<"$SERVICES")"

# shellcheck source=scripts/registry-lib.sh
. "$(dirname "$0")/registry-lib.sh"

failed=0
undetermined=0
attempts="${INSPECT_ATTEMPTS:-5}"

while read -r service; do
  image="${IMAGE_BASE}/${service}:sha-${SHA}"
  rc=0; inspect_digest "$image" || rc=$?

  case "$rc" in
    0) echo "  ok            ${service} -> ${INSPECT_DIGEST}" ;;
    1)
      echo "  MISSING       ${service} (${image} does not resolve)"
      echo "${INSPECT_ERROR}"
      failed=1
      ;;
    *)
      # Distinct from MISSING on purpose. "The image is not there" and "I could
      # not find out whether the image is there" are different findings, and
      # naming the second one as the first sends someone to debug a publish that
      # worked.
      echo "  UNDETERMINED  ${service} (could not query ${image} after ${attempts} attempts)"
      echo "${INSPECT_ERROR}"
      undetermined=1
      ;;
  esac
done < <(jq -r '.[]' <<<"$SERVICES")

if [ "$failed" -ne 0 ]; then
  die "the matrix reported success but at least one selected service has no" \
      "image in the registry at ${SHA}. This is the S60 shape: a green" \
      "Publish that published nothing."
fi

if [ "$undetermined" -ne 0 ]; then
  die "could not establish whether every selected service reached the" \
      "registry at ${SHA}. This is not evidence that publishing failed -- it" \
      "is the absence of evidence that it succeeded, which this gate is not" \
      "allowed to treat as success."
fi

echo "All ${count} selected service(s) resolve in the registry at ${SHA}."
