#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Phase 14 — where the 180 minutes have gone, and whether the demo still fits.
#
#   scripts/sandbox-status.sh
#
# The plan's session budget is a table in a document. A table in a document is
# not a control: by the time you think to re-read it you have already spent the
# time it was warning you about. This prints the same table with the current
# position marked, plus the cluster health that decides whether the remaining
# minutes are usable at all.
# ---------------------------------------------------------------------------
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
# shellcheck source=scripts/sandbox-lib.sh
. scripts/sandbox-lib.sh

DRY_RUN="${DRY_RUN:-0}"
require_session

ELAPSED_S="$(session_elapsed_s)"
ELAPSED_M=$(( ELAPSED_S / 60 ))
REMAIN_M=$(( SANDBOX_BUDGET_MIN - ELAPSED_M ))

say ""
printf 'session  %s elapsed, %sm remaining of %sm\n' \
  "$(fmt_duration "${ELAPSED_S}")" "${REMAIN_M}" "${SANDBOX_BUDGET_MIN}"
printf 'region   %s\n' "$(state_get region)"
printf 'ingress  %s\n' "$(state_get ingress)"

# A 40-cell bar is legible in any terminal and needs no dependencies.
FILLED=$(( ELAPSED_M * 40 / SANDBOX_BUDGET_MIN ))
[ "${FILLED}" -gt 40 ] && FILLED=40
printf '\n['
i=0; while [ "${i}" -lt 40 ]; do
  if [ "${i}" -lt "${FILLED}" ]; then printf '#'; else printf '.'; fi
  i=$(( i + 1 ))
done
printf '] %dm\n' "${ELAPSED_M}"
printf '%s0        provision      45   demo window   150  teardown  180%s\n' "${c_dim}" "${c_off}"

if [ "${ELAPSED_M}" -ge 150 ]; then
  printf '\n%sTEARDOWN WINDOW — run make sandbox-down now.%s\n' "${c_red}" "${c_off}"
elif [ "${ELAPSED_M}" -ge 135 ]; then
  printf '\n%s15 minutes to the teardown window. Start wrapping up.%s\n' "${c_yel}" "${c_off}"
fi

# --- cluster health ---------------------------------------------------------
say ""
if ! kubectl cluster-info >/dev/null 2>&1; then
  warn "cluster unreachable — kubeconfig may have expired with the session token"
  exit 0
fi

step "cluster"
kubectl get nodes -o wide 2>/dev/null | head -5

step "workloads not ready"
# Printing only what is wrong. A wall of Running pods is noise at minute 90;
# the one CrashLoopBackOff is the entire message.
notready="$(kubectl get pods --all-namespaces --no-headers 2>/dev/null |
  awk 'NF >= 4 && $4 != "Running" && $4 != "Completed" { print "  " $1 "/" $2 "  " $4 }')"
if [ -n "${notready}" ]; then
  printf '%s\n' "${notready}"
else
  printf '  %s✓%s everything Running or Completed\n' "${c_grn}" "${c_off}"
fi

step "argocd"
kubectl get applications -n argocd \
  -o custom-columns=NAME:.metadata.name,SYNC:.status.sync.status,HEALTH:.status.health.status \
  2>/dev/null || dim "  no Applications (ArgoCD not installed yet)"
