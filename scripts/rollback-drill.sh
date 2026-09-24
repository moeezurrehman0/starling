#!/usr/bin/env bash
#
# SPDX-License-Identifier: MIT
#
# The rollback drill.
#
# Deploys a deliberately broken build as a canary and asserts that Argo Rollouts
# rejects it *without being told to*. A canary that has only ever been driven by
# a healthy image proves that the happy path works, which was never in doubt.
# The claim worth making is "a bad build cannot reach 100% of traffic", and the
# only way to substantiate it is to try to ship one.
#
# What "broken" means here is a configuration fault, not a corrupt binary: the
# canary is pointed at an upstream that does not exist, so it starts cleanly,
# passes its health probes, and returns 5xx for real requests. That is a far
# more representative bad deploy than a crash loop -- a crash loop is caught by
# the probes and never gets traffic, so it never exercises the analysis at all.
#
# Usage:
#   scripts/rollback-drill.sh            # abort drill (expects rejection)
#   scripts/rollback-drill.sh --healthy  # control run (expects promotion)
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NS="${NS:-starling}"
SVC="${SVC:-gateway}"
MODE="${1:-broken}"
OUT="${OUT:-$ROOT/build/rollout}"
DEADLINE_SECONDS="${DEADLINE_SECONDS:-900}"
# At least the analysis lookback window, so the first measurement describes the
# canary rather than the deploy that preceded it.
WARMUP_SECONDS="${WARMUP_SECONDS:-90}"

log()  { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
pass() { printf '  \033[32mPASS\033[0m  %s\n' "$*"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; FAILED=$((FAILED + 1)); }
die()  { printf '\033[31mERROR\033[0m %s\n' "$*" >&2; exit 1; }
FAILED=0

command -v kubectl >/dev/null || die "kubectl not found"
kubectl get crd rollouts.argoproj.io >/dev/null 2>&1 \
  || die "Argo Rollouts is not installed — run 'make kind-up'"
kubectl -n "$NS" get rollout "$SVC" >/dev/null 2>&1 \
  || die "no Rollout/$SVC in $NS — is rollout.enabled set for this environment?"

mkdir -p "$OUT"

# Capture what we are rolling back *to*, before changing anything. Reading it
# afterwards reads the broken value.
#
# TIMELINE_SERVICE_URL, not USER_SERVICE_URL. The fault has to sit on the path
# the load generator actually exercises during the rollout, and steady.js
# enrols each VU once and caches the token -- so after the warm-up nothing ever
# calls user-service again. Breaking it produced a canary with a measured error
# rate of exactly 0.0, which the gate correctly passed and which therefore
# tested nothing. Timeline reads are ~80% of steady traffic.
#
# The upstream URLs reach the container through `envFrom` on the service's
# ConfigMap, not as inline env entries, so the baseline has to be read from the
# ConfigMap. The fault is injected as an *inline* env entry -- inline env wins
# over envFrom, so the override takes effect without editing (and risking
# permanently corrupting) shared config.
UPSTREAM_KEY="${UPSTREAM_KEY:-TIMELINE_SERVICE_URL}"
BASELINE="$(kubectl -n "$NS" get configmap "$SVC" \
  -o jsonpath="{.data.$UPSTREAM_KEY}" 2>/dev/null)"
[ -n "$BASELINE" ] || die "could not read the current $UPSTREAM_KEY to restore later"

# The exact env array to converge back to. `kubectl set env` is not usable here:
# it resolves the target through kubectl's compiled-in scheme, which has no
# entry for argoproj.io/v1alpha1, so it fails with "no kind Rollout is
# registered" against any CRD workload. A JSON patch goes straight to the API
# server and does not need a registered type.
ENV_BASELINE="$(kubectl -n "$NS" get rollout "$SVC" \
  -o jsonpath='{.spec.template.spec.containers[0].env}' 2>/dev/null)"
[ -n "$ENV_BASELINE" ] || ENV_BASELINE='[]'
printf '%s\n' "$ENV_BASELINE" > "$OUT/baseline-env.json"
printf '%s\n' "$BASELINE" > "$OUT/baseline-upstream.txt"
log "baseline $UPSTREAM_KEY = $BASELINE"

restore() {
  log "restoring the baseline"
  # Replace the whole env array with the captured baseline rather than deleting
  # the one key. That restores the exact spec Helm rendered, so the cluster
  # converges back to the GitOps state instead of to a hand-written copy of it
  # that would then drift the next time the ConfigMap changes.
  kubectl -n "$NS" patch rollout "$SVC" --type=json \
    -p "[{\"op\":\"replace\",\"path\":\"/spec/template/spec/containers/0/env\",\"value\":$ENV_BASELINE}]" \
    >/dev/null 2>&1 || true

  # Restoring the spec is NOT enough on its own, and assuming it was left the
  # cluster serving 100% 5xx for fifteen minutes during development.
  #
  # After an abort, Argo Rollouts latches `status.abort: true`. While that latch
  # is set the controller will not roll forward, so the corrected spec sits in
  # the API server while the *broken* ReplicaSet keeps serving. Worse, the
  # analysis measures the whole Service rather than just the canary pods, so the
  # aggregate error rate is dominated by the broken stable pods -- the healthy
  # replacement fails a gate that is measuring the very thing it would fix, and
  # every automatic retry aborts again. See docs/16-gap-register.md #35.
  #
  # `promoteFull` skips the remaining steps and the analysis for this one
  # transition, which is the documented break-glass for exactly this state. It
  # is correct here because the spec being promoted is the known-good baseline
  # that was captured before the drill touched anything.
  kubectl -n "$NS" patch rollout "$SVC" --type=merge --subresource=status \
    -p '{"status":{"abort":false,"promoteFull":true}}' >/dev/null 2>&1 || true

  # Do not exit claiming success while the cluster is still broken.
  local phase
  for _ in $(seq 1 40); do
    phase="$(kubectl -n "$NS" get rollout "$SVC" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    [ "$phase" = "Healthy" ] && { log "baseline restored (rollout Healthy)"; return 0; }
    sleep 10
  done
  printf '  \033[31mWARN\033[0m  rollout is %s after restore — the cluster may still be degraded\n' "${phase:-unknown}" >&2
}
trap restore EXIT

# A drill that starts from a degraded cluster measures the previous run, not
# this one. The abort latch in particular survives across runs.
PRE_PHASE="$(kubectl -n "$NS" get rollout "$SVC" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
[ "$PRE_PHASE" = "Healthy" ] \
  || die "Rollout/$SVC is $PRE_PHASE, not Healthy — the drill needs a known-good starting point"

# Traffic is required for the analysis to have anything to measure. Without it
# the Prometheus query returns no data, the metric is inconclusive, and the
# rollout stalls -- which is correct behaviour and a useless drill.
#
# Started BEFORE the rollout is touched, and given a full lookback window to
# settle. Starting it afterwards means the first measurement's rate() window is
# mostly idle, so a handful of restart-related 5xx become a large *fraction* of
# a tiny denominator and abort a healthy build. Warm traffic gives the ratio a
# denominator worth dividing by.
kubectl -n "$NS" delete job k6-steady --ignore-not-found >/dev/null 2>&1 || true
OUT="$OUT/traffic" "$ROOT/scripts/load-test.sh" steady 5 "$((DEADLINE_SECONDS / 60))m" \
  > "$OUT/drill-traffic.log" 2>&1 &
TRAFFIC=$!

log "warming traffic for ${WARMUP_SECONDS}s so the first measurement has a full window"
sleep "$WARMUP_SECONDS"

# Marks the boundary between AnalysisRuns left over from earlier drills and the
# ones this run is allowed to draw conclusions from.
DRILL_STARTED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

if [ "$MODE" = "--healthy" ]; then
  log "control run: re-deploying the SAME configuration"
  # A no-op annotation change forces a new revision without changing behaviour,
  # so the analysis runs against a build that should pass. Without this control
  # the drill cannot distinguish "the gate rejects bad builds" from "the gate
  # rejects everything", and a gate that rejects everything blocks delivery
  # while looking rigorous.
  kubectl -n "$NS" patch rollout "$SVC" --type=merge \
    -p "{\"spec\":{\"template\":{\"metadata\":{\"annotations\":{\"drill/control\":\"$(date +%s)\"}}}}}" >/dev/null
  EXPECT="Healthy"
else
  log "abort drill: pointing $SVC at an upstream that does not resolve"
  # The hostname carries a timestamp so every run is a genuinely new pod spec.
  # With a fixed hostname the second run re-creates a ReplicaSet Argo Rollouts
  # has already seen and promoted, which triggers its fast-track rollback path:
  # the rollout jumps straight to Healthy without running a single analysis step
  # and the drill reports a false failure. Uniqueness makes each run a new build.
  BROKEN_URL="http://upstream-does-not-exist-$(date +%s):8082"
  BROKEN_ENV="$(printf '%s' "$ENV_BASELINE" | BROKEN_URL="$BROKEN_URL" UPSTREAM_KEY="$UPSTREAM_KEY" python3 -c 'import json,os,sys; k=os.environ["UPSTREAM_KEY"]; e=json.load(sys.stdin); e=[v for v in e if v.get("name")!=k]; e.append({"name":k,"value":os.environ["BROKEN_URL"]}); print(json.dumps(e))')"
  kubectl -n "$NS" patch rollout "$SVC" --type=json \
    -p "[{\"op\":\"replace\",\"path\":\"/spec/template/spec/containers/0/env\",\"value\":$BROKEN_ENV}]" \
    >/dev/null
  EXPECT="Degraded"
fi

log "watching the rollout (deadline ${DEADLINE_SECONDS}s, expecting $EXPECT)"

STATUS=""
ELAPSED=0
: > "$OUT/timeline.txt"
while [ "$ELAPSED" -lt "$DEADLINE_SECONDS" ]; do
  STATUS="$(kubectl -n "$NS" get rollout "$SVC" -o jsonpath='{.status.phase}' 2>/dev/null || echo '')"
  STEP="$(kubectl -n "$NS" get rollout "$SVC" -o jsonpath='{.status.currentStepIndex}' 2>/dev/null || echo '')"
  # The message is the only place the *reason* appears. Printing the phase alone
  # turns every failure into a bisect through controller logs.
  MSG="$(kubectl -n "$NS" get rollout "$SVC" -o jsonpath='{.status.message}' 2>/dev/null || true)"
  [ -z "$MSG" ] || printf '      %s\n' "$MSG"
  printf '%4ds  phase=%-12s step=%-3s replicas=%s\n' "$ELAPSED" "${STATUS:-?}" "${STEP:-?}" \
    "$(kubectl -n "$NS" get rollout "$SVC" -o jsonpath='{.status.replicas}' 2>/dev/null || echo '?')" \
    | tee -a "$OUT/timeline.txt"
  case "$STATUS" in
    Degraded) break ;;
    Healthy)  [ "$ELAPSED" -gt 30 ] && break ;;
  esac
  sleep 15
  ELAPSED=$((ELAPSED + 15))
done

kill "$TRAFFIC" 2>/dev/null || true
kubectl -n "$NS" get rollout "$SVC" -o yaml > "$OUT/rollout.yaml" 2>/dev/null || true
kubectl -n "$NS" get analysisrun -o wide > "$OUT/analysisruns.txt" 2>/dev/null || true

log "result"
if [ "$STATUS" = "$EXPECT" ]; then
  pass "rollout reached $STATUS, as expected"
else
  fail "rollout is $STATUS, expected $EXPECT (see $OUT/timeline.txt)"
fi

if [ "$MODE" != "--healthy" ]; then
  # An abort must be attributable to the analysis. A rollout that went Degraded
  # because its pods failed to schedule looks identical in `status.phase` and
  # proves nothing about the gate.
  #
  # Scoped to runs created after this drill started. The unscoped version
  # matched any Failed AnalysisRun left in the namespace by an earlier drill,
  # so it reported PASS during a run in which every AnalysisRun was Successful
  # and the broken build was promoted -- the assertion failed open in exactly
  # the way the drill exists to catch.
  if kubectl -n "$NS" get analysisrun \
      -o jsonpath="{range .items[?(@.metadata.creationTimestamp>'$DRILL_STARTED')]}{.status.phase}{'\n'}{end}" \
      2>/dev/null | grep -q '^Failed$'; then
    pass "an AnalysisRun failed — the abort came from the metrics, not from scheduling"
  else
    fail "no AnalysisRun from this run reached Failed; the abort was not caused by the analysis"
  fi

  # The stable ReplicaSet must still be serving. "Rolled back" is not the same
  # as "stopped", and a canary framework that halts a bad deploy by taking the
  # service down has not helped.
  READY="$(kubectl -n "$NS" get rollout "$SVC" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo 0)"
  if [ "${READY:-0}" -ge 1 ]; then
    pass "$READY replica(s) still Ready — the stable version kept serving throughout"
  else
    fail "no Ready replicas — the abort took the service down"
  fi
fi

printf '\nartifacts: %s\n' "$OUT"
[ "$FAILED" -eq 0 ] || die "$FAILED assertion(s) failed"
log "drill passed"
