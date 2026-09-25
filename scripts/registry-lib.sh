#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Shared registry lookup, hardened against the failure mode that a naive read
# cannot even report: not knowing.
#
# Sourced, not executed. Provides inspect_digest().
#
# WHY THIS IS A LIBRARY
#
# S63 was a freshly pushed image reported as MISSING by the outer publish gate.
# That gate was fixed. The fix was not applied to the per-service read-back
# inside the matrix job, which had the same defect, and one commit later the
# same thing happened there: fanout-worker pushed sha256:32657efa at 08:49:17,
# the inline check asked for it at 08:49:19 and died with exit 255, and the
# image was in the registry the whole time with exactly the digest that had
# just been pushed.
#
# Two copies of one check is two chances to fix the bug in only one of them.
# So there is now one copy, and both callers use it.
# ---------------------------------------------------------------------------

# Ask the registry for a tag's digest, distinguishing three outcomes that a
# naive check collapses into one:
#
#   0  resolved        a digest came back, in INSPECT_DIGEST
#   1  absent          the registry answered, and the answer was "no such tag"
#   2  undetermined    we could not get an answer at all
#
# On 1 and 2, INSPECT_ERROR holds the indented output that explains which.
#
# The distinction is the whole point. Collapsing 1 and 2 -- by sending stderr to
# /dev/null and treating any non-zero exit as absent -- is what produced S63:
# the evidence that would have shown the image was present had been discarded
# before anyone could read it. That is the same defect as a teardown sweep
# reporting "clean" because its query was denied, mirrored: reporting "gone"
# because the question failed.
#
# Every failure is retried, including a flat not-found, because a registry is
# eventually consistent and a tag written seconds ago can legitimately fail to
# resolve once before it succeeds. The two-second gap above is the proof.
# inspect_digest sets INSPECT_DIGEST and INSPECT_ERROR for its caller to read.
# The linter cannot see across the source boundary and reports them as unused.
# shellcheck disable=SC2034
inspect_digest() {
  local image="$1"
  local attempts="${INSPECT_ATTEMPTS:-5}"
  local delay="${INSPECT_DELAY:-2}"
  local attempt=1 out rc digest

  INSPECT_DIGEST=""
  INSPECT_ERROR=""

  while :; do
    out=$(docker buildx imagetools inspect "$image" 2>&1) && rc=0 || rc=$?

    if [ "$rc" -eq 0 ]; then
      # Parsed out of the human output on purpose: `--format
      # '{{.Manifest.Digest}}'` is accepted and then silently ignored by some
      # buildx versions, which print the default block instead. Tested -- it
      # would have made this pass against a string that was never a digest.
      digest=$(awk '/^Digest:/{print $2; exit}' <<<"$out")
      case "$digest" in
        sha256:*) INSPECT_DIGEST="$digest"; return 0 ;;
      esac
      # A success that carries no digest is a definitive answer of the wrong
      # shape, not a transport problem. Retrying cannot improve it.
      INSPECT_ERROR="inspect exited 0 but printed no digest:
$(sed 's/^/    /' <<<"$out")"
      return 1
    fi

    if [ "$attempt" -ge "$attempts" ]; then
      INSPECT_ERROR="$(sed 's/^/    /' <<<"$out")"
      # Only now, with the retries spent, is it worth deciding which kind of
      # failure this was.
      if grep -qiE 'not found|manifest unknown|name unknown|404' <<<"$out"; then
        return 1
      fi
      return 2
    fi

    sleep $((delay * attempt))
    attempt=$((attempt + 1))
  done
}
