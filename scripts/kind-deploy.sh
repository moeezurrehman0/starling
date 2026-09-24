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
need() { command -v "$1" >/dev/null 2>&1 || die "$1 not found on PATH"; }

# Check the tools before the cluster. Without this, a missing `kind` makes
# `kind get clusters` fail, the || fires, and the script reports "cluster not
# found -- run make kind-up" -- sending you to re-create a cluster that already
# exists and is healthy. A diagnostic that confidently names the wrong cause is
# worse than no diagnostic.
need kind
need kubectl
need helm
need docker

kind get clusters 2>/dev/null | grep -qx "$CLUSTER" || die "cluster '$CLUSTER' not found — run: make kind-up"
kubectl config use-context "kind-$CLUSTER" >/dev/null

# --- build ------------------------------------------------------------------

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  log "building the jars"
  "$ROOT/gradlew" -p "$ROOT" bootJar -x test -x integrationTest --console=plain -q

  for svc in "${SERVICES[@]}"; do
    log "building image twitterclone/$svc:$TAG"
    if [ "$svc" = "web" ]; then
      # Context is the repo root, not web/. Dockerfile.web does `COPY web/ ./`
      # -- the same context compose.yaml uses. Narrowing it to web/ looks tidier
      # and fails with "/web: not found", which reads like a missing directory
      # rather than a context that is one level too deep.
      docker build -q -f "$ROOT/docker/Dockerfile.web" -t "twitterclone/web:$TAG" "$ROOT" >/dev/null
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
kubectl label ns observability --overwrite \
  pod-security.kubernetes.io/enforce=restricted \
  pod-security.kubernetes.io/audit=restricted \
  pod-security.kubernetes.io/warn=restricted >/dev/null

# Log collection, one privilege level up and one namespace across. Promtail has
# to mount /var/log/pods from the node, which `baseline` already forbids; the
# alternative to this namespace is relaxing the profile for Grafana too, and
# Grafana is the most exposed process in the stack. See promtail.yaml.
kubectl get ns observability-agents >/dev/null 2>&1 || kubectl create ns observability-agents
kubectl label ns observability-agents --overwrite \
  pod-security.kubernetes.io/enforce=privileged \
  pod-security.kubernetes.io/audit=privileged \
  pod-security.kubernetes.io/warn=privileged >/dev/null

# The Secret the charts reference by name. Nothing else creates it, and a missing
# Secret named in `envFrom` does not fail the Deployment -- the pod is scheduled,
# the image is pulled, and the kubelet then reports CreateContainerConfigError
# with the Secret name buried in a describe. The Deployment itself stays
# Available=False with no message about a Secret at all.
#
# These are LocalStack's fixed dummy credentials, not secrets in any real sense.
# They live in a Secret rather than the ConfigMap so that the workload reads them
# from exactly the same place it will in Tier P, where External Secrets Operator
# produces this name from Secrets Manager.
log "applying the dev secret"
kubectl create secret generic twitter-clone-secrets -n "$NS" \
  --from-literal=AWS_ACCESS_KEY_ID=test \
  --from-literal=AWS_SECRET_ACCESS_KEY=test \
  --from-literal=SEARCH_DB_PASSWORD=twitter \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

log "installing dev-infra"
helm upgrade --install dev-infra "$ROOT/deploy/charts/dev-infra" -n "$NS" --wait --timeout 5m

# Observability before the applications, not after. The applications export
# traces from their first request; a collector that does not exist yet means the
# exporter logs a connection refused at warn and carries on, so the first minutes
# of a fresh cluster are exactly the ones with no trace data -- which is when
# anyone debugging a fresh cluster is looking.
#
# Not --wait: Prometheus and Grafana take longer to become ready than the whole
# application stack, and nothing in the application path depends on them being
# up. Blocking here would add a minute to every deploy in exchange for nothing.
log "installing observability"
helm upgrade --install observability "$ROOT/deploy/charts/observability" -n observability

# LocalStack starts empty: no tables, no bucket. The services do not create them,
# by design -- a service that creates its own tables at boot will happily create
# the wrong ones in production.
log "creating DynamoDB tables and the media bucket"
# Both files. create-tables.py reads dynamodb-tables.json -- mounting only the
# script gives a Job that pip-installs boto3, starts cleanly and then exits on a
# "could not find dynamodb-tables.json" that looks like a packaging problem
# rather than a missing mount. The script's second candidate path is its own
# directory, which is why /work works without an env override.
kubectl create configmap create-tables -n "$NS" \
  --from-file="$ROOT/tools/localstack/create-tables.py" \
  --from-file="$ROOT/tools/dynamodb-tables.json" \
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
      # The namespace enforces the restricted profile, and a Job is subject to it
      # like anything else. Without this the Job object is created, reports
      # Running, and never produces a Pod -- admission rejects each attempt and
      # the only trace is a FailedCreate event on the Job. A wait on
      # condition=complete then blocks for the full timeout and reports
      # "timed out waiting for the condition", which says nothing about admission.
      securityContext:
        runAsNonRoot: true
        runAsUser: 65532
        runAsGroup: 65532
        fsGroup: 65532
        seccompProfile: {type: RuntimeDefault}
      containers:
        - name: create
          image: python:3.12-alpine
          securityContext:
            allowPrivilegeEscalation: false
            capabilities: {drop: ["ALL"]}
          command: [sh, -c]
          args:
            # --user with a writable HOME: a non-root pod cannot write to the
            # image's site-packages, and pip's error names the directory rather
            # than the user.
            - pip install --quiet --user boto3 && python /work/create-tables.py
          env:
            - {name: AWS_ENDPOINT_URL, value: "http://localstack:4566"}
            - {name: AWS_REGION, value: us-east-1}
            - {name: AWS_ACCESS_KEY_ID, value: test}
            - {name: AWS_SECRET_ACCESS_KEY, value: test}
            - {name: HOME, value: /home/nonroot}
            - {name: PYTHONPATH, value: /home/nonroot/.local/lib/python3.12/site-packages}
          volumeMounts:
            - {name: work, mountPath: /work}
            - {name: home, mountPath: /home/nonroot}
      volumes:
        - name: work
          configMap: {name: create-tables}
        - name: home
          emptyDir: {}
YAML
kubectl wait --for=condition=complete --timeout=300s -n "$NS" job/create-tables

for svc in "${SERVICES[@]}" tweet-indexer; do
  log "installing $svc"
  img="twitterclone/${svc/tweet-indexer/tweet-service}"
  # The image id, as a pod annotation.
  #
  # Without it a rebuild deploys nothing. TAG is fixed (`sha-local`), so a code
  # change produces a new image under the same tag, the rendered Deployment is
  # byte-identical to the running one, Helm makes no change, no pod is replaced --
  # and `rollout status` immediately reports success for the pods already there.
  # The script says "deployed", the cluster runs the previous build, and the only
  # way to notice is that the fix you just made is still missing.
  #
  # An annotation rather than an unconditional `rollout restart`: this restarts
  # exactly the workloads whose image actually changed, so re-running the script
  # after editing one service does not bounce the other six.
  digest="$(docker image inspect "$img:$TAG" --format '{{.Id}}' 2>/dev/null || echo unknown)"
  helm upgrade --install "$svc" "$ROOT/deploy/charts/service" -n "$NS" \
    -f "$ROOT/deploy/envs/dev/values.yaml" \
    -f "$ROOT/deploy/envs/dev/$svc.yaml" \
    --set "image.repository=$img" \
    --set "image.tag=$TAG" \
    --set image.pullPolicy=Never \
    --set "podAnnotations.twitterclone\.dev/image-id=$digest"
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
