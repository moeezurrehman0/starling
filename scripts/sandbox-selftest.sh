#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Offline self-test for the three sandbox lifecycle scripts.
#
#   scripts/sandbox-selftest.sh
#
# WHY
#
# These three scripts run exactly once per KodeKloud session, against an
# account that cannot be reproduced, inside a 180-minute clock. A bug in them
# does not cost a debugging session -- it costs *the* session. Phase 6 learned
# this the expensive way: the probe's first version captured its success flag
# in a subshell and would have reported every capability as denied, producing a
# confident and entirely false report from a real account.
#
# So the lifecycle is tested here, with stub `aws`, `terraform`, `kubectl` and
# `helm` binaries ahead of the real ones on PATH. No credentials, no cluster,
# no network.
#
# The assertion that carries the most weight is the teardown one. The project's
# done criterion is "make sandbox-down leaves nothing", and the failure mode
# that criterion invites is a sweep that reports clean because it did not
# actually look -- a denied API call, a changed output format, a query that
# returns the literal string "None". Each of those is asserted below as a
# *survivor*, not as a pass.
# ---------------------------------------------------------------------------
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

PASSED=0
FAILED=0
pass() { printf '  \033[32mok\033[0m    %s\n' "$1"; PASSED=$((PASSED + 1)); }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILED=$((FAILED + 1)); }
head_() { printf '\n\033[1m%s\033[0m\n' "$1"; }

check()   { if printf '%s' "$2" | grep -q -- "$3"; then pass "$1"; else fail "$1"; fi; }
refute()  { if printf '%s' "$2" | grep -q -- "$3"; then fail "$1"; else pass "$1"; fi; }

STUB="$(mktemp -d)"
STATE="$(mktemp -u)"
trap 'rm -rf "${STUB}" "${STATE}"' EXIT

# --- stubs ------------------------------------------------------------------
# SWEEP_MODE drives what the teardown sweep sees:
#   clean    -- everything gone
#   dirty    -- an ALB and an EBS volume survive
#   denied   -- the EKS query fails outright
#   none     -- AWS returns the literal string "None", which is its way of
#               saying "empty" for --output text and which a naive check reads
#               as one surviving resource named None.
cat > "${STUB}/aws" <<'STUB'
#!/usr/bin/env bash
case "$*" in
  "sts get-caller-identity"*)
    echo '{"Account":"123456789012","Arn":"arn:aws:sts::123456789012:assumed-role/playground/session"}'
    exit 0;;
esac
case "${SWEEP_MODE:-clean}" in
  dirty)
    case "$*" in
      *"elbv2 describe-load-balancers"*) echo "k8s-gateway-abc123"; exit 0;;
      *"describe-volumes"*)              echo "vol-0deadbeef"; exit 0;;
    esac;;
  denied)
    case "$*" in
      *"eks list-clusters"*)
        echo "An error occurred (AccessDeniedException) when calling ListClusters"; exit 255;;
    esac;;
  none)
    echo "None"; exit 0;;
esac
exit 0
STUB

cat > "${STUB}/terraform" <<'STUB'
#!/usr/bin/env bash
case "$*" in
  *"output -raw kubeconfig_command"*)
    echo "aws eks update-kubeconfig --region us-east-1 --name starling-sandbox";;
esac
exit "${TF_RC:-0}"
STUB

cat > "${STUB}/kubectl" <<'STUB'
#!/usr/bin/env bash
case "$*" in
  "cluster-info"*) exit "${K8S_DOWN:-0}";;
  "get nodes"*)    echo "ip-10-0-1-1   Ready   <none>   3m   v1.31.0";;
  "get pods"*)     echo "${FAKE_PODS:-}";;
esac
exit 0
STUB

cat > "${STUB}/helm" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
chmod +x "${STUB}"/aws "${STUB}"/terraform "${STUB}"/kubectl "${STUB}"/helm

sb() { # sb <script> <args...> -- run with stubs, isolated state, no sleeping
  env PATH="${STUB}:${PATH}" SANDBOX_STATE="${STATE}" AWS_REGION=us-east-1 \
      "$@" 2>&1
}

printf 'self-testing the sandbox lifecycle\n'

# ---------------------------------------------------------------------------
head_ "sandbox-up"

rm -f "${STATE}"
OUT="$(sb bash scripts/sandbox-up.sh --dry-run)"
check "dry run completes"                       "${OUT}" "up in"
check "dry run announces itself"                "${OUT}" "DRY RUN"
refute "dry run creates nothing"                "${OUT}" "apply -input=false -auto-approve$"
check "terraform stage runs"                    "${OUT}" "terraform apply"
check "argocd stage runs"                       "${OUT}" "ArgoCD"
check "observability stage runs"                "${OUT}" "Prometheus"
check "it names the teardown command"           "${OUT}" "sandbox-down"
check "it states the demo-window deadline"      "${OUT}" "minute 150"

# Resumability is the property that makes a failed session recoverable rather
# than a restart. If --from does not actually skip, a retry re-creates an EKS
# cluster you already have and spends twelve minutes you do not have.
rm -f "${STATE}"
OUT="$(sb bash scripts/sandbox-up.sh --dry-run --from argocd)"
refute "--from argocd skips terraform"          "${OUT}" "terraform apply"
refute "--from argocd skips the kubeconfig"     "${OUT}" "kubeconfig"
check  "--from argocd still runs argocd"        "${OUT}" "ArgoCD"
check  "--from argocd still runs later stages"  "${OUT}" "Prometheus"

rm -f "${STATE}"
OUT="$(sb bash scripts/sandbox-up.sh --dry-run --skip-observability)"
refute "--skip-observability skips it"          "${OUT}" "Tempo"
check  "and says so"                            "${OUT}" "skipping observability"

rm -f "${STATE}"
OUT="$(sb bash scripts/sandbox-up.sh --dry-run --wat)"
check "an unknown flag is refused"              "${OUT}" "unknown argument"

# A second run must not reset the clock. If it did, sandbox-status would report
# minute 4 at minute 90 and the teardown warning would never fire.
rm -f "${STATE}"
sb bash scripts/sandbox-up.sh --dry-run >/dev/null
FIRST="$(sed -n 's/^started_at=//p' "${STATE}")"
sleep 1
sb bash scripts/sandbox-up.sh --dry-run >/dev/null
SECOND="$(sed -n 's/^started_at=//p' "${STATE}")"
if [ "${FIRST}" = "${SECOND}" ]; then
  pass "re-running does not reset the session clock"
else
  fail "re-running reset the session clock (${FIRST} -> ${SECOND})"
fi

OUT="$(sb bash scripts/sandbox-up.sh --dry-run)"
check "a second run warns a session exists"     "${OUT}" "already recorded"

# ---------------------------------------------------------------------------
head_ "credential guard"

cat > "${STUB}/aws" <<'STUB'
#!/usr/bin/env bash
case "$*" in
  "sts get-caller-identity"*)
    echo '{"Account":"999","Arn":"arn:aws:iam::999:user/me"}'; exit 0;;
esac
exit 0
STUB
chmod +x "${STUB}/aws"

rm -f "${STATE}"
OUT="$(sb bash scripts/sandbox-down.sh)"
check "long-lived IAM user creds are refused"   "${OUT}" "not a playground session"
check "and the refusal says why"                "${OUT}" "not recoverable"
OUT="$(env SANDBOX_ALLOW_STATIC_CREDS=1 PATH="${STUB}:${PATH}" SANDBOX_STATE="${STATE}" \
        bash scripts/sandbox-down.sh --dry-run 2>&1)"
check "the override works and warns"            "${OUT}" "SANDBOX_ALLOW_STATIC_CREDS=1"

cat > "${STUB}/aws" <<'STUB'
#!/usr/bin/env bash
case "$*" in
  "sts get-caller-identity"*)
    echo '{"Account":"123456789012","Arn":"arn:aws:sts::123456789012:assumed-role/playground/session"}'
    exit 0;;
esac
case "${SWEEP_MODE:-clean}" in
  dirty)
    case "$*" in
      *"elbv2 describe-load-balancers"*) echo "k8s-gateway-abc123"; exit 0;;
      *"describe-volumes"*)              echo "vol-0deadbeef"; exit 0;;
    esac;;
  denied)
    case "$*" in
      *"eks list-clusters"*)
        echo "An error occurred (AccessDeniedException) when calling ListClusters"; exit 255;;
    esac;;
  none) echo "None"; exit 0;;
esac
exit 0
STUB
chmod +x "${STUB}/aws"

# ---------------------------------------------------------------------------
head_ "sandbox-down — the sweep is the deliverable"

rm -f "${STATE}"; printf 'started_at=%s\nregion=us-east-1\n' "$(date +%s)" > "${STATE}"
OUT="$(env SWEEP_MODE=clean K8S_DOWN=1 PATH="${STUB}:${PATH}" SANDBOX_STATE="${STATE}" \
        bash scripts/sandbox-down.sh 2>&1)"; RC=$?
check "a clean account reports nothing left"    "${OUT}" "nothing left standing"
if [ "${RC}" -eq 0 ]; then pass "clean teardown exits 0"; else fail "clean teardown exited ${RC}"; fi
if [ -f "${STATE}" ]; then fail "clean teardown left session state behind"
            else pass "clean teardown removes the session state"; fi

# The failure this whole script exists to prevent: destroy succeeds, an ALB and
# an EBS volume survive, and the operator is told everything is fine.
rm -f "${STATE}"; printf 'started_at=%s\nregion=us-east-1\n' "$(date +%s)" > "${STATE}"
OUT="$(env SWEEP_MODE=dirty K8S_DOWN=1 PATH="${STUB}:${PATH}" SANDBOX_STATE="${STATE}" \
        bash scripts/sandbox-down.sh 2>&1)"; RC=$?
check "a surviving ALB is detected"             "${OUT}" "k8s-gateway-abc123"
check "a surviving EBS volume is detected"      "${OUT}" "vol-0deadbeef"
check "survivors are counted"                   "${OUT}" "survived"
refute "it does NOT claim to be clean"          "${OUT}" "nothing left standing"
if [ "${RC}" -ne 0 ]; then pass "a dirty teardown exits non-zero"
            else fail "a dirty teardown exited 0 — the done criterion is unenforced"; fi
if [ -f "${STATE}" ]; then pass "a dirty teardown KEEPS state for the manual cleanup"
            else fail "a dirty teardown discarded the region and prefix"; fi

# "I could not check" is not "there is nothing there". A sweep that treats a
# denied call as a pass is the correct-looking, fail-open variant of this bug —
# the same class as gap S40.
rm -f "${STATE}"; printf 'started_at=%s\nregion=us-east-1\n' "$(date +%s)" > "${STATE}"
OUT="$(env SWEEP_MODE=denied K8S_DOWN=1 PATH="${STUB}:${PATH}" SANDBOX_STATE="${STATE}" \
        bash scripts/sandbox-down.sh 2>&1)"; RC=$?
check "a denied query is reported, not swallowed" "${OUT}" "could not check"
refute "a denied query is not reported as clean"  "${OUT}" "nothing left standing"
if [ "${RC}" -ne 0 ]; then pass "an unverifiable teardown exits non-zero"
            else fail "an unverifiable teardown exited 0"; fi

# `--output text` prints the literal string None for an empty result. Reading
# that as a resource named "None" makes every clean teardown fail, which is the
# fail-closed twin and would be just as corrosive: an operator who sees a
# spurious failure every time stops reading the output.
rm -f "${STATE}"; printf 'started_at=%s\nregion=us-east-1\n' "$(date +%s)" > "${STATE}"
OUT="$(env SWEEP_MODE=none K8S_DOWN=1 PATH="${STUB}:${PATH}" SANDBOX_STATE="${STATE}" \
        bash scripts/sandbox-down.sh 2>&1)"
check "the literal string None counts as empty"  "${OUT}" "nothing left standing"

rm -f "${STATE}"; printf 'started_at=%s\nregion=us-east-1\n' "$(date +%s)" > "${STATE}"
OUT="$(env K8S_DOWN=0 PATH="${STUB}:${PATH}" SANDBOX_STATE="${STATE}" \
        bash scripts/sandbox-down.sh --dry-run 2>&1)"
check "a reachable cluster releases k8s-owned AWS resources first" \
                                                 "${OUT}" "releasing Kubernetes-created"

# ---------------------------------------------------------------------------
head_ "sandbox-status"

rm -f "${STATE}"
OUT="$(sb bash scripts/sandbox-status.sh)"
check "with no session it refuses clearly"      "${OUT}" "no session state"

printf 'started_at=%s\nregion=us-east-1\ningress=alb\n' "$(( $(date +%s) - 3600 ))" > "${STATE}"
OUT="$(env K8S_DOWN=1 PATH="${STUB}:${PATH}" SANDBOX_STATE="${STATE}" \
        bash scripts/sandbox-status.sh 2>&1)"
check "it reports elapsed time"                 "${OUT}" "60m00s elapsed"
check "it reports remaining time"               "${OUT}" "120m remaining"
check "it draws the budget bar"                 "${OUT}" "demo window"

printf 'started_at=%s\nregion=us-east-1\n' "$(( $(date +%s) - 9300 ))" > "${STATE}"
OUT="$(env K8S_DOWN=1 PATH="${STUB}:${PATH}" SANDBOX_STATE="${STATE}" \
        bash scripts/sandbox-status.sh 2>&1)"
check "past minute 150 it demands teardown"     "${OUT}" "TEARDOWN WINDOW"

printf 'started_at=%s\nregion=us-east-1\n' "$(( $(date +%s) - 8300 ))" > "${STATE}"
OUT="$(env K8S_DOWN=1 PATH="${STUB}:${PATH}" SANDBOX_STATE="${STATE}" \
        bash scripts/sandbox-status.sh 2>&1)"
check "at minute 138 it gives a warning first"  "${OUT}" "15 minutes to the teardown"

printf 'started_at=%s\nregion=us-east-1\n' "$(( $(date +%s) - 600 ))" > "${STATE}"
OUT="$(env K8S_DOWN=1 PATH="${STUB}:${PATH}" SANDBOX_STATE="${STATE}" \
        bash scripts/sandbox-status.sh 2>&1)"
refute "early in the session it does not nag"   "${OUT}" "TEARDOWN WINDOW"

OUT="$(env K8S_DOWN=0 FAKE_PODS="dev gateway-1 1/1 CrashLoopBackOff" \
        PATH="${STUB}:${PATH}" SANDBOX_STATE="${STATE}" \
        bash scripts/sandbox-status.sh 2>&1)"
check "it surfaces a broken pod"                "${OUT}" "CrashLoopBackOff"

OUT="$(env K8S_DOWN=0 FAKE_PODS="" PATH="${STUB}:${PATH}" SANDBOX_STATE="${STATE}" \
        bash scripts/sandbox-status.sh 2>&1)"
check "and stays quiet when nothing is wrong"   "${OUT}" "everything Running"

# ---------------------------------------------------------------------------
head_ "the Makefile actually calls these"

MK="$(cat Makefile)"
check "sandbox-up is wired"     "${MK}" "scripts/sandbox-up.sh"
check "sandbox-down is wired"   "${MK}" "scripts/sandbox-down.sh"
check "sandbox-status is wired" "${MK}" "scripts/sandbox-status.sh"
refute "no 'not yet implemented' stubs remain" "${MK}" "Not yet implemented"

printf '\n%d passed, %d failed\n' "${PASSED}" "${FAILED}"
[ "${FAILED}" -eq 0 ]
