#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Phase 14 — provision the 180-minute sandbox, end to end, in one command.
#
#   scripts/sandbox-up.sh [--dry-run] [--from <stage>] [--skip-observability]
#
# WHY THIS IS A SCRIPT AND NOT A RUNBOOK
#
# The project's stated done criterion is that inside one 180-minute session
# `make sandbox-up` provisions real AWS, ArgoCD syncs the app, a promotion PR
# canaries to prod, k6 drives an HPA scale-out, a trace crosses four services
# and an alert fires. None of that is reachable if the first forty minutes go
# on copying commands out of a document. The clock is the binding constraint
# in this project -- not money, not quota -- so the provisioning path has to be
# one idempotent target with a measured time budget, and it has to print how it
# is tracking against that budget as it goes.
#
# THE TWO PROPERTIES THAT MATTER
#
#   1. IDEMPOTENT AND RESUMABLE. Sessions fail halfway. A control plane times
#      out, a quota bites, a laptop sleeps. Every stage is re-runnable, and
#      `--from <stage>` restarts at a named point rather than from zero --
#      because re-creating an EKS cluster you already have costs twelve of the
#      hundred and eighty minutes.
#
#   2. BUDGET-AWARE. Each stage records its own elapsed time against the
#      budget in the plan. A stage that overruns prints how far behind the
#      session now is, while there is still time to drop the observability
#      stack and keep the demo. Discovering at minute 150 that you cannot
#      finish is the failure this is designed to prevent.
#
# WHAT IT DELIBERATELY DOES NOT DO
#
# It does not create the GitHub OIDC provider or any CI role. Tier S is driven
# from a laptop with session credentials precisely because the playground wipes
# IAM between sessions; that is gap register row 1 and it is a finding, not an
# omission.
# ---------------------------------------------------------------------------
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
# shellcheck source=scripts/sandbox-lib.sh
. scripts/sandbox-lib.sh

DRY_RUN=0
FROM=""
SKIP_OBS=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run)            DRY_RUN=1 ;;
    --from)               FROM="${2:-}"; shift ;;
    --skip-observability) SKIP_OBS=1 ;;
    -h|--help) sed -n '3,40p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
  shift
done

# The budget from the plan, in minutes-from-session-start that each stage
# should have *finished* by. Overrunning is not fatal -- it is reported, which
# is the whole point.
stage_deadline() {
  case "$1" in
    terraform)     echo 20 ;;
    kubeconfig)    echo 22 ;;
    platform)      echo 28 ;;
    argocd)        echo 35 ;;
    observability) echo 45 ;;
    *)             echo 0  ;;
  esac
}

STAGES="terraform kubeconfig platform argocd observability"

should_run() {
  # With --from, skip every stage before the named one.
  [ -z "${FROM}" ] && return 0
  local s seen=0
  for s in ${STAGES}; do
    [ "${s}" = "${FROM}" ] && seen=1
    [ "${s}" = "$1" ] && { [ "${seen}" = "1" ] && return 0 || return 1; }
  done
  return 1
}

report_budget() {
  local stage="$1" deadline elapsed_min
  deadline="$(stage_deadline "${stage}")"
  elapsed_min=$(( $(session_elapsed_s) / 60 ))
  if [ "${elapsed_min}" -gt "${deadline}" ]; then
    warn "stage '${stage}' finished at minute ${elapsed_min}, budget was ${deadline} — $(( elapsed_min - deadline )) min behind"
  else
    dim "  on budget: minute ${elapsed_min} of ${deadline}"
  fi
}

# ---------------------------------------------------------------------------
preflight

if [ -f "${SANDBOX_STATE}" ] && [ -z "${FROM}" ]; then
  warn "a session is already recorded (started $(fmt_duration "$(session_elapsed_s)") ago)."
  warn "continuing — every stage is idempotent. Use --from <stage> to skip ahead."
else
  [ -f "${SANDBOX_STATE}" ] || state_put started_at "$(date +%s)"
fi
state_put region "${AWS_REGION:-us-east-1}"
state_put prefix "${SANDBOX_TAG_VALUE}"

say ""
say "sandbox-up — budget ${SANDBOX_BUDGET_MIN} min, elapsed $(fmt_duration "$(session_elapsed_s)")"
[ "${DRY_RUN}" = "1" ] && dim "DRY RUN — nothing will be created"

# --- 1. terraform -----------------------------------------------------------
if should_run terraform; then
  step "terraform apply — VPC prep, ECR, DynamoDB, IAM; EKS and RDS in parallel"
  run "init"  terraform -chdir="${SANDBOX_ROOT}" init -input=false ||
    die "terraform init failed"
  # -parallelism is raised because the two long poles (EKS control plane ~12
  # min, RDS ~6 min) are independent, and the default of 10 is not the
  # constraint -- the dependency graph is. Raising it costs nothing and lets
  # the eight DynamoDB tables land while EKS is still coming up.
  run "apply" terraform -chdir="${SANDBOX_ROOT}" apply -input=false -auto-approve -parallelism=20 ||
    die "terraform apply failed — run 'make sandbox-down' before retrying, or --from terraform to resume"
  report_budget terraform
fi

# --- 2. kubeconfig ----------------------------------------------------------
if should_run kubeconfig; then
  step "kubeconfig"
  if [ "${DRY_RUN}" = "1" ]; then
    dim "  [dry-run] terraform output -raw kubeconfig_command | sh"
  else
    kubecmd="$(terraform -chdir="${SANDBOX_ROOT}" output -raw kubeconfig_command 2>/dev/null)"
    case "${kubecmd}" in
      "aws eks update-kubeconfig"*) eval "${kubecmd}" || die "update-kubeconfig failed" ;;
      *) die "EKS is not enabled in this root: ${kubecmd}" ;;
    esac
    kubectl get nodes || die "cluster is unreachable"
  fi
  report_budget kubeconfig
fi

# --- 3. platform ------------------------------------------------------------
if should_run platform; then
  step "platform add-ons — metrics-server, AWS Load Balancer Controller"
  run "metrics-server" kubectl apply -f \
    https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml

  # The LB Controller needs IRSA. Whether IRSA works here is gap register row 9
  # and is answered by the Phase 6 probe, not assumed. If it is unavailable the
  # session continues on NodePort -- degraded, and *reported as degraded*,
  # because a demo that silently swaps its ingress path and says nothing is the
  # exact failure this repo keeps cataloguing.
  if [ "${DRY_RUN}" = "1" ]; then
    dim "  [dry-run] helm upgrade --install aws-load-balancer-controller"
  elif kubectl get sa -n kube-system aws-load-balancer-controller >/dev/null 2>&1; then
    dim "  LB controller service account present — installing"
    helm repo add eks https://aws.github.io/eks-charts >/dev/null 2>&1
    helm upgrade --install aws-load-balancer-controller eks/aws-load-balancer-controller \
      -n kube-system --set clusterName="$(state_get cluster_name)" \
      --set serviceAccount.create=false \
      --set serviceAccount.name=aws-load-balancer-controller ||
      warn "LB controller install failed — falling back to NodePort ingress (gap register row 9)"
    state_put ingress alb
  else
    warn "no IRSA service account for the LB controller — using NodePort (gap register row 9)"
    state_put ingress nodeport
  fi
  report_budget platform
fi

# --- 4. argocd --------------------------------------------------------------
if should_run argocd; then
  step "ArgoCD + app-of-apps"
  run "namespace" kubectl create namespace argocd --dry-run=client -o yaml

  # Resolved outside the DRY_RUN guard so that `--dry-run` fails here, on a
  # laptop, rather than at minute 35 of a 180-minute session. ArgoCD reads
  # manifests from a git server; it cannot read this working copy. The origin
  # remote is HTTPS and the repository is public, so no clone credential is
  # needed -- a private repo would need a repo secret here and does not have one.
  REPO_URL="${REPO_URL:-$(git -C "$(dirname "$0")/.." remote get-url origin 2>/dev/null || true)}"
  [ -n "${REPO_URL}" ] || die "no git remote and no REPO_URL -- ArgoCD would be bootstrapped against the literal string REPO_URL_PLACEHOLDER and sync nothing. Set REPO_URL."

  # Only root.yaml, and only after substitution. The previous form was
  # `kubectl apply -f deploy/argocd/`, which pointed kubectl at a Helm chart
  # directory: Chart.yaml and values.yaml are not Kubernetes manifests, kubectl
  # rejects them, and the command exits 1. Verified -- it does exactly that,
  # and `|| die` then ended the session at the ArgoCD stage. It also left
  # REPO_URL_PLACEHOLDER unsubstituted, which kind-up.sh has always handled and
  # this script never did.
  run "app-of-apps" kubectl apply --dry-run=client -f - \
    <<<"$(sed "s|REPO_URL_PLACEHOLDER|${REPO_URL}|g" "$(dirname "$0")/../deploy/argocd/root.yaml")"

  if [ "${DRY_RUN}" != "1" ]; then
    kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
    helm repo add argo https://argoproj.github.io/argo-helm >/dev/null 2>&1
    # Slim profile: no dex, no notifications, no ApplicationSet controller. On
    # 6 vCPU those three cost about as much as the entire application.
    helm upgrade --install argocd argo/argo-cd -n argocd \
      --set dex.enabled=false \
      --set notifications.enabled=false \
      --set applicationSet.enabled=false \
      --wait --timeout 8m || die "ArgoCD install failed"
    sed "s|REPO_URL_PLACEHOLDER|${REPO_URL}|g" \
      "$(dirname "$0")/../deploy/argocd/root.yaml" |
      kubectl apply -f - || die "app-of-apps bootstrap failed"
  fi
  report_budget argocd
fi

# --- 5. observability -------------------------------------------------------
if should_run observability && [ "${SKIP_OBS}" = "0" ]; then
  step "observability — Prometheus, Grafana, Tempo (no Loki; gap register row 15)"
  if [ "${DRY_RUN}" != "1" ]; then
    helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1
    helm repo add grafana https://grafana.github.io/helm-charts >/dev/null 2>&1
    # 2h retention on emptyDir. The session is 180 minutes; a PVC would outlive
    # nothing and would block the teardown sweep on a lingering EBS volume.
    helm upgrade --install kube-prometheus-stack \
      prometheus-community/kube-prometheus-stack -n platform --create-namespace \
      -f deploy/observability/values-sandbox.yaml --wait --timeout 8m ||
      warn "observability install failed — the demo still works without it"
  fi
  report_budget observability
elif [ "${SKIP_OBS}" = "1" ]; then
  dim "skipping observability (--skip-observability)"
fi

# ---------------------------------------------------------------------------
say ""
step "up in $(fmt_duration "$(session_elapsed_s)") of ${SANDBOX_BUDGET_MIN}m"
say "  demo window closes at minute 150. Run 'make sandbox-status' to track it."
say "  TEAR DOWN WITH 'make sandbox-down' — the account is wiped anyway, but the"
say "  teardown sweep is what proves the destroy path is complete."
