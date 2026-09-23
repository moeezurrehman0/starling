#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Image size report and gate — ADR-0010.
#
#   scripts/image-report.sh <image[:tag]> [baseline-image[:tag]]
#
# The gate exists to protect pull time, so it is expressed in the terms that
# govern pull time rather than in the number `docker images` happens to print.
# Three numbers are reported; two are gated.
#
#   COLD PULL       the sum of the gzipped layer blobs: what a node with an
#                   empty image cache downloads. In Tier S every node is cold
#                   on every run, because the cluster is destroyed after 180
#                   minutes, so this is not an edge case there — it is the
#                   normal case. GATED.
#
#   WARM PULL       the gzipped size of only those layers whose digest differs
#                   from the baseline image: what a node that already ran the
#                   previous revision downloads on redeploy. This is the true
#                   cost of a canary step and of an HPA scale-out on a warm
#                   node. GATED when a baseline is supplied.
#
#   ON-DISK         the uncompressed total, i.e. what `docker images` prints.
#                   Reported for continuity with ADR-0010's original figure,
#                   but deliberately not gated: it governs node disk, and node
#                   disk is not the scarce resource in any of the three tiers.
#
# Override the limits with IMAGE_MAX_COLD_MB / IMAGE_MAX_WARM_MB.
#
# The per-layer arithmetic lives in scripts/image-layers.py; its header
# explains why `docker history` cannot be used for it.
# ---------------------------------------------------------------------------
set -euo pipefail

IMAGE="${1:?usage: image-report.sh <image[:tag]> [baseline]}"
BASELINE="${2:-}"


MAX_WARM_MB="${IMAGE_MAX_WARM_MB:-20}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUDGETS="${HERE}/../docker/image-budgets.txt"

# The cold-pull limit comes from docker/image-budgets.txt, keyed on the service
# name in the image reference, unless IMAGE_MAX_COLD_MB overrides it. A single
# shared limit would have to be set by the largest image and would then fail to
# constrain any of the others; that file explains the reasoning in full.
SERVICE_NAME="${IMAGE##*/}"; SERVICE_NAME="${SERVICE_NAME%%:*}"
if [[ -n "${IMAGE_MAX_COLD_MB:-}" ]]; then
  MAX_COLD_MB="${IMAGE_MAX_COLD_MB}"
  BUDGET_SRC="IMAGE_MAX_COLD_MB"
elif [[ -r "${BUDGETS}" ]]; then
  MAX_COLD_MB="$(awk -v s="${SERVICE_NAME}" '
    /^[[:space:]]*#/ || NF < 2 { next }
    $1 == s { print $2; found = 1; exit }
    $1 == "*" { star = $2 }
    END { if (!found && star != "") print star }' "${BUDGETS}")"
  BUDGET_SRC="docker/image-budgets.txt"
fi
if [[ -z "${MAX_COLD_MB:-}" ]]; then
  MAX_COLD_MB=167
  BUDGET_SRC="built-in default"
fi

mb() { awk -v b="$1" 'BEGIN { printf "%.1f", b / 1048576 }'; }
over() { awk -v b="$1" -v m="$2" 'BEGIN { exit !(b / 1048576 > m) }'; }

docker image inspect "${IMAGE}" >/dev/null 2>&1 || { echo "no such image: ${IMAGE}"; exit 1; }

TMP="$(mktemp -d)"; trap 'rm -rf "${TMP}"' EXIT

docker save "${IMAGE}" -o "${TMP}/img.tar"
ARGS=("${TMP}/img.tar")

if [[ -n "${BASELINE}" ]]; then
  if docker image inspect "${BASELINE}" >/dev/null 2>&1; then
    docker save "${BASELINE}" -o "${TMP}/base.tar"
    ARGS+=("${TMP}/base.tar")
  else
    echo "  note: baseline ${BASELINE} not present locally — warm-pull gate skipped"
    BASELINE=""
  fi
fi

# The helper emits KEY=VALUE plus heredoc-delimited tables, so its output can
# be sourced directly rather than parsed once per figure.
python3 "${HERE}/image-layers.py" "${ARGS[@]}" > "${TMP}/report.env"
# shellcheck disable=SC1090
source "${TMP}/report.env"

printf '\n  image            %s\n' "${IMAGE}"
printf '  layers           %s\n\n' "${LAYER_COUNT}"
printf '  on-disk          %s MB   (reported, not gated)\n' "$(mb "${TOTAL_RAW}")"
printf '  cold pull        %s MB   (limit %s MB, from %s)\n' "$(mb "${TOTAL_GZ}")" "${MAX_COLD_MB}" "${BUDGET_SRC}"

FAIL=0
over "${TOTAL_GZ}" "${MAX_COLD_MB}" && { printf '  FAIL  cold pull exceeds %s MB\n' "${MAX_COLD_MB}"; FAIL=1; }

printf '\n  largest layers        on-disk      gzipped\n'
printf '%s\n' "${TABLE}"

if [[ -n "${BASELINE}" ]]; then
  printf '\n  vs %s\n' "${BASELINE}"
  printf '  changed layers   %s of %s\n' "${CHANGED_COUNT}" "${LAYER_COUNT}"
  printf '  warm pull        %s MB   (limit %s MB; %s MB on disk)\n' \
    "$(mb "${CHANGED_GZ}")" "${MAX_WARM_MB}" "$(mb "${CHANGED_RAW}")"
  if [[ "${CHANGED_COUNT}" -gt 0 ]]; then
    printf '\n  changed layers        on-disk      gzipped\n'
    printf '%s\n' "${CHANGED_TABLE}"
  fi
  over "${CHANGED_GZ}" "${MAX_WARM_MB}" \
    && { printf '\n  FAIL  warm pull exceeds %s MB\n' "${MAX_WARM_MB}"; FAIL=1; }
else
  printf '\n  warm pull        (no baseline — gate skipped)\n'
fi

echo
if [[ "${FAIL}" -eq 0 ]]; then echo "  size gates passed."; else echo "  size gates FAILED."; fi
exit "${FAIL}"
