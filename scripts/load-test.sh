#!/usr/bin/env bash
#
# SPDX-License-Identifier: MIT
#
# Run a k6 scenario against the gateway and record what the cluster did.
#
# k6 runs *inside* the cluster, as a Job, not on the laptop through a
# port-forward. A port-forward is a single TCP connection multiplexed by
# kubectl through the API server: it caps throughput at something unrelated to
# the application, adds its own latency, and — worst of all — bypasses the
# Service, so the load never exercises kube-proxy or the endpoint set the HPA is
# about to change. Every number measured that way describes kubectl.
#
# Usage:
#   scripts/load-test.sh smoke
#   scripts/load-test.sh ramp                   # drives the HPA
#   scripts/load-test.sh steady 5 10m           # background traffic for a canary
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NS="${NS:-starling}"
K6_IMAGE="${K6_IMAGE:-grafana/k6:0.55.0}"
SCENARIO="${1:-smoke}"
PEAK_RPS="${2:-${PEAK_RPS:-20}}"
DURATION="${3:-${DURATION:-2m}}"
OUT="${OUT:-$ROOT/build/load}"

die() { printf '\033[31mERROR\033[0m %s\n' "$*" >&2; exit 1; }
log() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

[ -f "$ROOT/load/$SCENARIO.js" ] || die "no such scenario: load/$SCENARIO.js"
command -v kubectl >/dev/null || die "kubectl not found"
kubectl get ns "$NS" >/dev/null 2>&1 || die "namespace $NS not found — run 'make kind-deploy' first"

mkdir -p "$OUT"
JOB="k6-$SCENARIO"

# The scripts go in via a ConfigMap rather than being baked into an image. A
# load test whose source lives in a registry is a load test nobody edits.
log "publishing load scripts"
kubectl -n "$NS" delete configmap k6-scripts --ignore-not-found >/dev/null
kubectl -n "$NS" create configmap k6-scripts \
  --from-file="$ROOT/load/smoke.js" \
  --from-file="$ROOT/load/ramp.js" \
  --from-file="$ROOT/load/steady.js" >/dev/null
kubectl -n "$NS" delete configmap k6-lib --ignore-not-found >/dev/null
kubectl -n "$NS" create configmap k6-lib --from-file="$ROOT/load/lib" >/dev/null

log "recording pre-test state"
kubectl -n "$NS" get hpa -o wide > "$OUT/hpa-before.txt" 2>/dev/null || true
kubectl -n "$NS" get pods -l app.kubernetes.io/part-of=starling > "$OUT/pods-before.txt" 2>/dev/null || true

kubectl -n "$NS" delete job "$JOB" --ignore-not-found --wait=true >/dev/null

log "running $SCENARIO (peak=${PEAK_RPS}rps stage=${DURATION})"
# `restartPolicy: Never` with `backoffLimit: 0`: a k6 run that fails its
# thresholds must stay failed. Retrying it would eventually produce a green run
# on a system that is not meeting its SLO, which is worse than no test.
kubectl -n "$NS" apply -f - <<YAML >/dev/null
apiVersion: batch/v1
kind: Job
metadata:
  name: $JOB
  labels:
    app.kubernetes.io/name: k6
    app.kubernetes.io/part-of: starling
spec:
  backoffLimit: 0
  template:
    metadata:
      labels:
        app.kubernetes.io/name: k6
        app.kubernetes.io/part-of: starling
    spec:
      restartPolicy: Never
      securityContext:
        runAsNonRoot: true
        runAsUser: 12345
        seccompProfile: { type: RuntimeDefault }
      containers:
        - name: k6
          image: $K6_IMAGE
          args: ["run", "--no-usage-report", "--summary-export=/tmp/summary.json", "/scripts/$SCENARIO.js"]
          env:
            - name: BASE_URL
              value: "http://gateway.$NS.svc.cluster.local:8080"
            - name: PEAK_RPS
              value: "$PEAK_RPS"
            - name: STAGE_DURATION
              value: "$DURATION"
            - name: RATE
              value: "$PEAK_RPS"
            - name: DURATION
              value: "$DURATION"
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: false
            capabilities: { drop: ["ALL"] }
          resources:
            requests: { cpu: 100m, memory: 128Mi }
            limits: { memory: 512Mi }
          volumeMounts:
            - { name: scripts, mountPath: /scripts }
            - { name: lib, mountPath: /scripts/lib }
      volumes:
        - { name: scripts, configMap: { name: k6-scripts } }
        - { name: lib, configMap: { name: k6-lib } }
YAML

# Poll for a terminal Job condition while sampling the HPA in the same loop.
#
# `kubectl wait` can only wait for one condition, and a k6 job can end either
# Complete or Failed -- waiting on the wrong one blocks for the full timeout on
# every run of the other kind. The obvious fix is to race two `wait` calls with
# `wait -n`, but bash 3.2 (which is what macOS ships, and it is not going to
# change) has no `wait -n`. Polling is less elegant and always works.
#
# Sampling the HPA in the same loop is deliberate: reading it only at the end
# cannot distinguish "never scaled" from "scaled up and scaled back down", and
# the second is the outcome this drill exists to demonstrate.
: > "$OUT/hpa-samples.txt"
DEADLINE=$(( $(date +%s) + 1800 ))
STATUS=""
while :; do
  # Select by condition type rather than reading conditions[0]: Kubernetes 1.31
  # added a `SuccessCriteriaMet` condition that lands *before* `Complete` in the
  # array, so an index-based read polls forever on a job that already finished.
  STATUS="$(kubectl -n "$NS" get job "$JOB" -o jsonpath='{range .status.conditions[?(@.status=="True")]}{.type}{"\n"}{end}' 2>/dev/null | grep -E '^(Complete|Failed)$' | head -1 || true)"
  case "$STATUS" in
    Complete | Failed) break ;;
  esac
  [ "$(date +%s)" -lt "$DEADLINE" ] || { STATUS="Timeout"; break; }
  printf '%s %s\n' "$(date +%H:%M:%S)" \
    "$(kubectl -n "$NS" get hpa -o custom-columns=N:.metadata.name,T:.spec.metrics[0].resource.target.averageUtilization,C:.status.currentMetrics[0].resource.current.averageUtilization,R:.status.currentReplicas,D:.status.desiredReplicas --no-headers 2>/dev/null | tr '\n' '|')" \
    >> "$OUT/hpa-samples.txt"
  sleep 10
done

kubectl -n "$NS" logs "job/$JOB" > "$OUT/$SCENARIO.log" 2>&1 || true
kubectl -n "$NS" get hpa -o wide > "$OUT/hpa-after.txt" 2>/dev/null || true



log "summary"
sed -n '/scenarios:/,$p' "$OUT/$SCENARIO.log" | tail -40 || true
printf '\nartifacts: %s\n' "$OUT"

[ "$STATUS" = "Complete" ] || die "k6 job $STATUS — thresholds breached or the run errored (see $OUT/$SCENARIO.log)"
log "k6 $SCENARIO passed"
