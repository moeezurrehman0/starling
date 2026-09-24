#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Container vulnerability scan.
#
#   scripts/image-scan.sh <image[:tag]> [sarif-output-path]
#
# Fails on any fixable CRITICAL or HIGH. A scan that only reports is a scan
# nobody reads, so this one is a gate.
#
# WHY A PINNED CONTAINER RATHER THAN aquasecurity/trivy-action
#
# The action does not ship the scanner. It checks out contrib/install.sh from
# aquasecurity/trivy@main -- an unpinned script on a branch that moves -- and
# runs it. That script began exiting 1 with no diagnostic immediately after
# resolving the release, which failed all five image jobs here while the action
# itself was pinned to an exact tag. Pinning a wrapper around a moving
# dependency buys nothing, and the failure surfaces as somebody else's shell
# script inside a composite action, which is an unpleasant place to debug.
#
# The official image is one artefact with one digest, so the scanner, its
# defaults and its exit codes are all pinned together. It also means this scan
# runs locally, identically, with no GitHub Actions runner involved -- the
# property gap S49 is about.
#
# The vulnerability database is still fetched at run time and still moves; that
# is inherent to scanning and is the point. Only the scanner is pinned.
# ---------------------------------------------------------------------------
set -euo pipefail

TRIVY_IMAGE="${TRIVY_IMAGE:-aquasec/trivy:0.65.0}"

IMAGE="${1:-}"
if [ -z "$IMAGE" ]; then
  echo "usage: scripts/image-scan.sh <image[:tag]> [sarif-output-path]" >&2
  exit 2
fi
SARIF="${2:-}"

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "image not present in the local daemon: $IMAGE" >&2
  echo "build it first (make image SERVICE=<name>)" >&2
  exit 2
fi

# A named volume, not a bind mount: the database is ~700 MB unpacked and
# re-downloading it per service turns a five-service matrix into five
# downloads. Trivy is also rate-limited by the registry serving the DB.
docker volume create starling-trivy-db >/dev/null

args=(
  image
  --severity "CRITICAL,HIGH"
  # Unfixable findings cannot be acted on in this repository. Failing on them
  # means the only way to green is to add an ignore entry, and a suppression
  # file that grows on every base-image refresh stops being read.
  --ignore-unfixed
  --exit-code 1
  --no-progress
)

mounts=(
  -v /var/run/docker.sock:/var/run/docker.sock
  -v starling-trivy-db:/root/.cache/trivy
)

if [ -n "$SARIF" ]; then
  out_dir="$(cd "$(dirname "$SARIF")" && pwd)"
  out_file="$(basename "$SARIF")"
  mounts+=(-v "${out_dir}:/out")
  args+=(--format sarif --output "/out/${out_file}")
fi

# TRIVY_USERNAME/PASSWORD authenticate the *database* pull from ghcr.io, not the
# image being scanned. Unauthenticated pulls are rate-limited per IP, which on a
# shared runner is an intermittent failure that looks like a scanner bug.
creds=()
if [ -n "${GITHUB_TOKEN:-}" ]; then
  creds=(-e "TRIVY_USERNAME=${GITHUB_ACTOR:-github-actions}" -e "TRIVY_PASSWORD=${GITHUB_TOKEN}")
fi

echo "==> scanning ${IMAGE} with ${TRIVY_IMAGE}"
status=0
# ${creds[@]+...} because bash 3.2, which is what macOS ships, treats an empty
# array expansion as an unbound variable under `set -u`. CI runs bash 5 and
# would not have shown this.
docker run --rm "${mounts[@]}" ${creds[@]+"${creds[@]}"} "$TRIVY_IMAGE" "${args[@]}" "$IMAGE" || status=$?

if [ -n "$SARIF" ] && [ ! -f "$SARIF" ]; then
  # An absent SARIF and a failing scan look the same to the upload step, which
  # is set to continue-on-error. Say so here instead.
  echo "warning: no SARIF written to ${SARIF}; the scanner did not get far enough" >&2
fi

if [ "$status" -ne 0 ]; then
  echo "==> fixable CRITICAL/HIGH findings in ${IMAGE}" >&2
  exit "$status"
fi

echo "==> clean: no fixable CRITICAL or HIGH findings"
