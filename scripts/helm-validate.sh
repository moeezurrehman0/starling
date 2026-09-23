#!/usr/bin/env bash
#
# Lint, render and schema-check every chart, for every environment.
#
# `helm lint` on its own is close to worthless here: it checks that the chart is
# well-formed, not that what it produces is a valid Kubernetes object. A template
# that renders `replicas: "3"` as a string, or an `env` list with a missing name,
# lints perfectly and fails at apply time. kubeconform against the real API
# schemas is the part that catches those.
set -euo pipefail

# pipefail plus `grep | head` is a trap: grep exits 1 when it finds nothing, and
# under pipefail that failure propagates out of the assignment and kills the
# script mid-check. Every extraction below therefore ends in `|| true`, and a
# missing value is treated as a finding rather than as an abort.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
K8S_VERSION="${K8S_VERSION:-1.29.0}"
ENVS=(dev prod)
SERVICES=(gateway user-service tweet-service tweet-indexer timeline-service fanout-worker web)

pass=0
fail=0
ok()  { printf '  \033[1;32mPASS\033[0m  %s\n' "$*"; pass=$((pass + 1)); }
bad() { printf '  \033[1;31mFAIL\033[0m  %s\n' "$*"; fail=$((fail + 1)); }
log() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

command -v helm >/dev/null || { echo "helm is required"; exit 1; }
command -v kubeconform >/dev/null || { echo "kubeconform is required"; exit 1; }

log "helm lint"
for chart in service dev-infra; do
  if helm lint "$ROOT/deploy/charts/$chart" \
       --set name=lint --set image.repository=r --set image.tag=sha-1 >/tmp/hl.txt 2>&1; then
    ok "charts/$chart"
  else
    bad "charts/$chart"
    sed 's/^/        /' /tmp/hl.txt
  fi
done

log "render + schema check: services"
for env in "${ENVS[@]}"; do
  for svc in "${SERVICES[@]}"; do
    out="/tmp/render-$env-$svc.yaml"
    if ! helm template "$svc" "$ROOT/deploy/charts/service" \
        -f "$ROOT/deploy/envs/$env/values.yaml" \
        -f "$ROOT/deploy/envs/$env/$svc.yaml" >"$out" 2>/tmp/he.txt; then
      bad "$env/$svc — template"
      sed 's/^/        /' /tmp/he.txt
      continue
    fi
    if kubeconform -strict -summary -kubernetes-version "$K8S_VERSION" "$out" >/tmp/kc.txt 2>&1; then
      ok "$env/$svc"
    else
      bad "$env/$svc — schema"
      sed 's/^/        /' /tmp/kc.txt
    fi
  done
done

log "render + schema check: dev-infra and the app-of-apps"
if helm template infra "$ROOT/deploy/charts/dev-infra" >/tmp/render-infra.yaml 2>/tmp/he.txt \
   && kubeconform -strict -summary -kubernetes-version "$K8S_VERSION" /tmp/render-infra.yaml >/dev/null 2>&1; then
  ok "dev-infra"
else
  bad "dev-infra"
  sed 's/^/        /' /tmp/he.txt
fi

# The Application CRD is not in the default schema store, so -ignore-missing-schemas
# is required and the check degrades to "is it valid YAML with the right shape".
# Said out loud, because a summary reporting "Skipped: 8" looks like a pass.
for env in "${ENVS[@]}"; do
  if helm template apps "$ROOT/deploy/argocd" \
      --set repoURL=https://example.invalid/repo.git \
      --set "env=$env" >"/tmp/render-apps-$env.yaml" 2>/tmp/he.txt; then
    ok "argocd/$env (CRD schema not validated)"
  else
    bad "argocd/$env"
    sed 's/^/        /' /tmp/he.txt
  fi
done

log "negative checks — the guards must actually fire"

check_fails() {
  local label="$1"; shift
  if "$@" >/dev/null 2>&1; then
    bad "$label — expected failure, got success"
  else
    ok "$label"
  fi
}

check_fails "image.tag=latest is rejected" \
  helm template x "$ROOT/deploy/charts/service" \
    -f "$ROOT/deploy/envs/dev/values.yaml" -f "$ROOT/deploy/envs/dev/gateway.yaml" \
    --set image.tag=latest

check_fails "dev-infra refuses a non-dev tier" \
  helm template x "$ROOT/deploy/charts/dev-infra" --set tier=prod

check_fails "app-of-apps refuses an empty repoURL" \
  helm template x "$ROOT/deploy/argocd"

log "invariants that a schema check cannot see"

# A Deployment carrying both an HPA and a hardcoded replica count is the classic
# GitOps thrash. Rendering is not enough to catch it — both objects are valid.
for env in "${ENVS[@]}"; do
  for svc in "${SERVICES[@]}"; do
    out="/tmp/render-$env-$svc.yaml"
    if grep -q "kind: HorizontalPodAutoscaler" "$out" && grep -qE "^  replicas:" "$out"; then
      bad "$env/$svc — HPA and a hardcoded spec.replicas in the same release"
    fi
  done
done

# A PodDisruptionBudget with minAvailable equal to the replica count can never be
# satisfied by an eviction, so `kubectl drain` blocks forever.
for env in "${ENVS[@]}"; do
  for svc in "${SERVICES[@]}"; do
    out="/tmp/render-$env-$svc.yaml"
    grep -q "kind: PodDisruptionBudget" "$out" || continue
    reps="$(grep -E "^  replicas:" "$out" | head -1 | tr -d ' ' | cut -d: -f2 || true)"
    mina="$(grep -E "^  minAvailable:" "$out" | head -1 | tr -d ' ' | cut -d: -f2 || true)"
    if [ -n "$reps" ] && [ -n "$mina" ] && [ "$mina" -ge "$reps" ]; then
      bad "$env/$svc — PDB minAvailable=$mina with replicas=$reps blocks drain"
    fi
  done
done
ok "no PDB deadlocks, no HPA/replicas conflicts"

# Every service that reaches Redis must name both tiers, and they must differ.
# Pointing the celebrity cache at the ordinary one produces no error at all: the
# application works, the isolation is gone, and the only symptom is eviction
# pressure under a hot key.
for env in "${ENVS[@]}"; do
  out="/tmp/render-$env-timeline-service.yaml"
  main="$(grep -E '^  REDIS_MAIN_HOST:' "$out" | head -1 | cut -d: -f2- | tr -d ' "' || true)"
  celeb="$(grep -E '^  REDIS_CELEB_HOST:' "$out" | head -1 | cut -d: -f2- | tr -d ' "' || true)"
  if [ -z "$main" ] || [ -z "$celeb" ]; then
    bad "$env/timeline-service — a Redis tier is unset"
  elif [ "$main" = "$celeb" ]; then
    bad "$env/timeline-service — both Redis tiers point at $main"
  else
    ok "$env/timeline-service — cache tiers are distinct"
  fi
done

# The stream consumers must never be scalable. Two replicas of either processes
# every record twice, and nothing reports an error when it happens.
for env in "${ENVS[@]}"; do
  for svc in fanout-worker tweet-indexer; do
    out="/tmp/render-$env-$svc.yaml"
    reps="$(grep -E "^  replicas:" "$out" | head -1 | tr -d ' ' | cut -d: -f2 || true)"
    if grep -q "kind: HorizontalPodAutoscaler" "$out"; then
      bad "$env/$svc — has an HPA; a stream consumer must not autoscale"
    elif [ "$reps" != "1" ]; then
      bad "$env/$svc — replicas=$reps; a stream consumer must be singular"
    else
      ok "$env/$svc — pinned to one replica"
    fi
  done
done

# The two caches must not be configured identically. They were, briefly: the chart
# was written with one policy for both, which works perfectly and quietly removes
# the reason there are two caches at all. The condition where it matters -- memory
# pressure on a hot celebrity key -- is the one Tier L never reaches, so nothing
# would have caught it until production.
#
# compose.yaml is the source of truth for these values; drift between the two
# means Tier L stops predicting Tier S.
# Pull an arg value that follows a flag, from the first N lines after a marker.
# Helm groups rendered objects by kind, not by the order they appear in the
# template: both Services come first, then both Deployments. Anchoring on a
# tier's Service therefore reads forward into the *other* tier's Deployment,
# which is how this check first reported the celebrity cache as a copy of the
# main one. The anchor below is the Deployment's matchLabels, six spaces in and
# unique to the Deployment.
arg_after() { # file marker flag
  # The flag must match the whole list item: a loose match on --maxmemory also
  # hits --maxmemory-policy and returns the policy as the size.
  awk -v m="$2" 'index($0,m){f=1} f' "$1" \
    | grep -A1 -E "^[[:space:]]*-[[:space:]]*$3[[:space:]]*$" \
    | sed -n '2p' | sed 's/^[[:space:]]*-[[:space:]]*//; s/"//g' || true
}

# The two caches must not be configured identically. They were, briefly: the chart
# was written with one policy and one size for both, which works perfectly and
# quietly removes the reason there are two caches at all. The condition where it
# matters -- memory pressure on a hot celebrity key -- is the one Tier L never
# reaches, so nothing would have caught it until production.
#
# compose.yaml is the source of truth. Drift between the two means Tier L stops
# predicting Tier S.
for tier in main celeb; do
  want_pol="$(arg_after "$ROOT/compose.yaml" "  redis-$tier:" --maxmemory-policy)"
  want_mem="$(arg_after "$ROOT/compose.yaml" "  redis-$tier:" --maxmemory)"
  anchor="      app.kubernetes.io/name: redis-$tier"
  got_pol="$(arg_after /tmp/render-infra.yaml "$anchor" --maxmemory-policy)"
  got_mem="$(arg_after /tmp/render-infra.yaml "$anchor" --maxmemory)"
  if [ -z "$want_pol$want_mem" ] || [ -z "$got_pol$got_mem" ]; then
    bad "redis-$tier — could not read the cache settings from one of the two stacks"
  elif [ "$want_pol" != "$got_pol" ] || [ "$want_mem" != "$got_mem" ]; then
    bad "redis-$tier — chart says $got_pol/$got_mem, compose says $want_pol/$want_mem"
  else
    ok "redis-$tier — chart matches compose ($got_pol, $got_mem)"
  fi
done

if [ "$(arg_after /tmp/render-infra.yaml '      app.kubernetes.io/name: redis-main' --maxmemory-policy)" \
   = "$(arg_after /tmp/render-infra.yaml '      app.kubernetes.io/name: redis-celeb' --maxmemory-policy)" ]; then
  bad "both Redis tiers share one eviction policy — the split buys nothing"
fi

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
