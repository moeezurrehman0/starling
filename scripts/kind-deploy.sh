#!/usr/bin/env bash
#
# Build the images, load them into kind, and helm-install the charts.
#
# This is the non-GitOps path: the same charts and the same values files ArgoCD
# would use, applied directly. It exists because ArgoCD reads from a git server
# and Tier L may not have one, and because it is the fastest way to find a broken
# manifest -- a helm error appears immediately, an ArgoCD sync failure appears in
# a UI two minutes later.
set -euo pipefail

CLUSTER="${CLUSTER:-twitter-clone}"
NS="${NS:-twitter-clone}"
TAG="${TAG:-sha-local}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICES=(gateway user-service tweet-service timeline-service fanout-worker web)

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mxx\033[0m  %s\n' "$*" >&2; exit 1; }

kind get clusters 2>/dev/null | grep -qx "$CLUSTER" || die "cluster '$CLUSTER' not found — run: make kind-up"
kubectl config use-context "kind-$CLUSTER" >/dev/null

# --- build ------------------------------------------------------------------

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  log "building the jars"
  "$ROOT/gradlew" -p "$ROOT" bootJar -x test -x integrationTest --console=plain -q

  for svc in "${SERVICES[@]}"; do
    log "building image twitterclone/$svc:$TAG"
    if [ "$svc" = "web" ]; then
      docker build -q -f "$ROOT/docker/Dockerfile.web" -t "twitterclone/web:$TAG" "$ROOT/web" >/dev/null
    else
      # The glob matches both the boot jar and the -plain.jar Gradle also emits.
      # Picking the wrong one produces an image that builds, starts, and exits
      # immediately with "no main manifest attribute".
      jar=""
      for candidate in "$ROOT/services/$svc/build/libs/"*.jar; do
        case "$candidate" in *-plain.jar|*'*.jar') continue ;; esac
        jar="$candidate"
      done
      [ -n "$jar" ] || die "no bootJar for $svc — run ./gradlew bootJar"
      docker build -q -f "$ROOT/docker/Dockerfile" \
        --build-arg "SERVICE=$svc" \
        --build-arg "JAR_FILE=${jar#"$ROOT/"}" \
        --build-arg "GIT_SHA=$(git -C "$ROOT" rev-parse --short HEAD)" \
        -t "twitterclone/$svc:$TAG" "$ROOT" >/dev/null
    fi
  done
fi

# --- load -------------------------------------------------------------------
#
# kind nodes have their own containerd; an image in the host daemon is invisible
# to them. Without this the pods sit in ErrImagePull against a registry that has
# never heard of twitterclone/*.
log "loading images into the cluster"
for svc in "${SERVICES[@]}"; do
  kind load docker-image "twitterclone/$svc:$TAG" --name "$CLUSTER"
done

# --- deploy -----------------------------------------------------------------

kubectl get ns "$NS" >/dev/null 2>&1 || kubectl create ns "$NS"

# Pod Security Standards, enforced. Set here rather than assumed, so that a chart
# change that breaks the restricted profile is rejected at admission instead of
# discovered on a cluster that happens to have the label.
kubectl label ns "$NS" --overwrite \
  pod-security.kubernetes.io/enforce=restricted \
  pod-security.kubernetes.io/audit=restricted \
  pod-security.kubernetes.io/warn=restricted >/dev/null

# The observability namespace is referenced by every NetworkPolicy's scrape rule.
# A namespaceSelector matching a namespace that does not exist is not an error --
# it just never matches, so creating it now keeps the policies meaningful later.
kubectl get ns observability >/dev/null 2>&1 || kubectl create ns observability

log "installing dev-infra"
helm upgrade --install dev-infra "$ROOT/deploy/charts/dev-infra" -n "$NS" --wait --timeout 5m

# LocalStack starts empty: no tables, no bucket. The services do not create them,
# by design -- a service that creates its own tables at boot will happily create
# the wrong ones in production.
log "creating DynamoDB tables and the media bucket"
kubectl create configmap create-tables -n "$NS" \
  --from-file="$ROOT/tools/localstack/create-tables.py" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl delete job create-tables -n "$NS" --ignore-not-found >/dev/null
kubectl apply -n "$NS" -f - >/dev/null <<YAML
apiVersion: batch/v1
kind: Job
metadata:
  name: create-tables
spec:
  backoffLimit: 6
  template:
    spec:
      restartPolicy: OnFailure
      containers:
        - name: create
          image: python:3.12-alpine
          command: [sh, -c]
          args:
            - pip install --quiet boto3 && python /work/create-tables.py
          env:
            - {name: AWS_ENDPOINT_URL, value: "http://localstack:4566"}
            - {name: AWS_REGION, value: us-east-1}
            - {name: AWS_ACCESS_KEY_ID, value: test}
            - {name: AWS_SECRET_ACCESS_KEY, value: test}
          volumeMounts:
            - {name: work, mountPath: /work}
      volumes:
        - name: work
          configMap: {name: create-tables}
YAML
kubectl wait --for=condition=complete --timeout=300s -n "$NS" job/create-tables

for svc in "${SERVICES[@]}" tweet-indexer; do
  log "installing $svc"
  helm upgrade --install "$svc" "$ROOT/deploy/charts/service" -n "$NS" \
    -f "$ROOT/deploy/envs/dev/values.yaml" \
    -f "$ROOT/deploy/envs/dev/$svc.yaml" \
    --set "image.repository=twitterclone/${svc/tweet-indexer/tweet-service}" \
    --set "image.tag=$TAG" \
    --set image.pullPolicy=Never
done

# NodePort for the frontend, matching the extraPortMapping in the kind config.
# The chart does not express this because it is a kind-only concern; every other
# tier reaches the frontend through an Ingress.
kubectl patch svc web -n "$NS" --type merge -p \
  '{"spec":{"type":"NodePort","ports":[{"name":"http","port":3000,"targetPort":3000,"nodePort":30080}]}}' >/dev/null

log "waiting for rollouts"
fail=0
for svc in "${SERVICES[@]}" tweet-indexer; do
  kubectl rollout status deploy/"$svc" -n "$NS" --timeout=300s || fail=1
done

if [ "$fail" -ne 0 ]; then
  echo
  die "one or more rollouts failed — kubectl get pods -n $NS"
fi

echo
log "deployed. web: http://localhost:8088"
