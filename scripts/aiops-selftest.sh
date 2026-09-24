#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# shellcheck disable=SC2016
#   The single-quoted strings below are Markdown fragments to grep for, and the
#   backticks in them are Markdown code spans, not command substitution. Single
#   quotes are exactly right here.
#
# Self-test for the AIOps tooling.
#
# The risk commenter's whole claim is that a *deterministic* analyser, not a
# model, decides what is dangerous. That claim is only worth making if the
# analyser is tested, and tested in both directions: it must raise the findings
# a dangerous plan deserves, and it must stay quiet on a safe one. A checker
# that flags everything is as useless as one that flags nothing, and the two
# are indistinguishable if you only ever run the dangerous fixture.
#
# Everything here runs offline against committed fixtures. No AWS, no cluster,
# no model, no network.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIX="$ROOT/tools/aiops/fixtures"
PASSED=0
FAILED=0

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; PASSED=$((PASSED + 1)); }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILED=$((FAILED + 1)); }
head_() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }

# Asserts stdout of the risk commenter contains (or does not contain) a string.
# The commenter is invoked exactly as CI invokes it, rather than importing the
# module: an argument-parsing bug that makes CI post an empty comment would be
# invisible to a test that skips the entry point.
run_risk() {
  local plan="$1"; shift
  python3 "$ROOT/tools/aiops/risk_comment.py" --plan "$FIX/$plan" "$@"
}

assert_has() {
  local label="$1" needle="$2" haystack="$3"
  if printf '%s' "$haystack" | grep -qF -- "$needle"; then
    pass "$label"
  else
    fail "$label — expected to find: $needle"
  fi
}

assert_lacks() {
  local label="$1" needle="$2" haystack="$3"
  if printf '%s' "$haystack" | grep -qF -- "$needle"; then
    fail "$label — should not have found: $needle"
  else
    pass "$label"
  fi
}

# --------------------------------------------------------------------------
head_ "risk: a dangerous plan produces the findings it deserves"

DANGER="$(run_risk plan-destructive.json \
  --checkov "$FIX/checkov.json" --infracost "$FIX/infracost.json" --title 'Tier P')"

assert_has "a replaced DynamoDB table is critical data loss" \
  'data-loss' "$DANGER"
assert_has "the replacement names the attribute that forced it" \
  'forced by: hash_key' "$DANGER"
assert_has "deletion protection being switched off is critical" \
  'deletion_protection_enabled' "$DANGER"
assert_has "point-in-time recovery being switched off is caught" \
  'point_in_time_recovery' "$DANGER"
assert_has "force_destroy on a bucket is caught" \
  'force_destroy' "$DANGER"

# The ALB is replaced with create_before_destroy, so its actions arrive as
# ["create","delete"] rather than ["delete","create"]. Matching only the
# common order silently exempts every resource with a lifecycle block.
assert_has "a create_before_destroy replacement is still a replacement" \
  'module.alb.aws_lb.public' "$DANGER"
assert_has "replacing a load balancer is flagged as downtime" \
  'downtime' "$DANGER"
assert_has "destroying a NAT gateway is flagged as downtime" \
  'aws_nat_gateway' "$DANGER"
assert_has "ingress from 0.0.0.0/0 is flagged" \
  'open-ingress' "$DANGER"
assert_has "the open port is named" \
  'port `22`' "$DANGER"
assert_has "an IAM policy change is flagged as a permissions change" \
  'permissions' "$DANGER"
# The content rules below are what make it safe to suppress the "an IAM role is
# being created" finding. Without them, tightening the noise would mean a
# brand-new Action:"*" policy passed a greenfield plan unmentioned.
assert_has "a wildcard action is high, not medium" \
  '| 🟠 high | wildcard-action |' "$DANGER"
assert_has "the wildcard action itself is named" \
  'allows s3:' "$DANGER"
assert_has "a principal of * is flagged" \
  'public-principal' "$DANGER"
assert_has "the public principal finding says why it matters" \
  'Any AWS account can assume or use this' "$DANGER"
assert_has "an enumerated action on Resource * is medium, not high" \
  '| 🟡 medium | wildcard-resource |' "$DANGER"
# Some IAM actions take no resource at all, so "*" is their only legal form. A
# finding that is correct, unfixable and present on every plan is how a reviewer
# learns to scroll past this comment, which costs more than it catches.
assert_lacks "an action that cannot be scoped is not reported as unscoped" \
  'aws_iam_policy.stream_list' "$DANGER"
# The suppression is per action, not per statement. Folding an unscopable action
# into a statement alongside a scopable one must not launder the second.
assert_has "a scopable action is still flagged when it shares a statement" \
  'aws_iam_policy.stream_mixed' "$DANGER"
assert_has "only the scopable actions are counted in that statement" \
  'stream_mixed` | `aws_iam_policy` allows 1 action(s)' "$DANGER"
assert_has "checkov failures are folded in with their check id" \
  'CKV_AWS_260' "$DANGER"
assert_has "checkov severity is preserved rather than flattened" \
  '| ⚪ low | policy |' "$DANGER"
assert_has "a large cost delta is raised" \
  'monthly cost rises by 128.40' "$DANGER"
assert_has "the header leads with the worst severity" \
  '🔴 **critical**' "$DANGER"
assert_has "the plan counts are reported" \
  '**2** to replace' "$DANGER"

# Terraform is not Python. `True` in a comment about HCL is a tell that the
# tool is rendering its own internals rather than the plan.
assert_lacks "booleans are rendered as HCL, not as Python" \
  'to `True`' "$DANGER"
assert_lacks "booleans are rendered as HCL, not as Python (false)" \
  'from `False`' "$DANGER"

# --------------------------------------------------------------------------
head_ "risk: a safe plan stays quiet"

SAFE="$(run_risk plan-safe.json --title 'Tier P')"

assert_has "a plan with no risky changes reports nothing notable" \
  'nothing notable' "$SAFE"
assert_has "an unchanged plan reports no findings" \
  '_No findings._' "$SAFE"
assert_lacks "a no-op on a stateful resource is not a data-loss finding" \
  'data-loss' "$SAFE"
assert_lacks "an update that does not weaken a guardrail is not flagged" \
  'guardrail-removed' "$SAFE"
assert_lacks "a data source read is not counted as a change" \
  'aws_caller_identity' "$SAFE"
# A `data "aws_security_group"` block reads a group somebody else manages. Its
# type is in the SECURITY set and its attributes look exactly like a managed
# resource's, so a checker that forgets to filter on mode reports a permissions
# change and an open-ingress finding for infrastructure this plan does not
# touch. The fixture contains one that admits 0.0.0.0/0 specifically to make
# the mode filter load-bearing.
assert_lacks "reading a data source is not a permissions change" \
  'permissions' "$SAFE"
assert_lacks "a data source's own ingress rules are not this plan's problem" \
  'open-ingress' "$SAFE"
assert_has "creates are counted" \
  '**2** to add' "$SAFE"
# On a greenfield plan every IAM role in the design is a create. Emitting a
# finding for each produced 25 identical mediums against the real prod root and
# buried the two that mattered. A correctly scoped new role is counted, not
# listed; the content rules above still catch a bad one.
assert_lacks "creating a correctly scoped IAM role is not a finding" \
  '| 🟡 medium | permissions |' "$SAFE"
assert_lacks "a federated OIDC principal is not a public principal" \
  'public-principal' "$SAFE"
assert_has "suppressed security creates are still disclosed as a count" \
  '1 security resource(s) are created' "$SAFE"
assert_has "no-ops are not counted as changes" \
  '**0** to destroy' "$SAFE"

# --------------------------------------------------------------------------
head_ "risk: a missing cost estimate is a skip, not a zero"

assert_has "absent infracost is announced, not silently rendered as free" \
  'skip, not a zero' "$SAFE"

SMALL="$(run_risk plan-safe.json --infracost "$FIX/infracost-small.json")"
assert_has "a small cost delta is still reported" \
  '+3.20 USD' "$SMALL"
assert_lacks "a small cost delta does not raise a finding" \
  '| 🟡 medium | cost |' "$SMALL"

# --------------------------------------------------------------------------
head_ "risk: the comment is honest about how it was produced"

assert_has "the comment states that severities are not model-assigned" \
  'not by a model' "$DANGER"
assert_has "the comment states that it cannot fail the build" \
  'never fails the build' "$DANGER"
assert_has "an unconfigured model is disclosed in the output" \
  'no model configured' "$DANGER"

# A provider that is set but broken must degrade to the template and say so,
# not raise and not pretend a model wrote the text. Pointing at a port nothing
# listens on is the cheapest way to produce a real failure.
BROKEN="$(AIOPS_PROVIDER=openai AIOPS_API_KEY=not-a-key \
  AIOPS_BASE_URL=http://127.0.0.1:9 \
  run_risk plan-safe.json 2>/dev/null)"
assert_has "an unreachable model degrades to the template" \
  'model unavailable' "$BROKEN"
assert_has "the degraded comment still contains the findings" \
  'Findings' "$BROKEN"

UNKNOWN="$(AIOPS_PROVIDER=wat run_risk plan-safe.json 2>/dev/null)"
assert_has "an unknown provider is reported rather than ignored" \
  'unknown AIOPS_PROVIDER' "$UNKNOWN"

# --------------------------------------------------------------------------
head_ "risk: malformed input fails loudly"

if run_risk does-not-exist.json >/dev/null 2>&1; then
  fail "a missing plan file should exit non-zero"
else
  pass "a missing plan file exits non-zero rather than posting an empty comment"
fi

BAD="$(mktemp)"; printf 'not json' > "$BAD"
if python3 "$ROOT/tools/aiops/risk_comment.py" --plan "$BAD" >/dev/null 2>&1; then
  fail "an unparseable plan should exit non-zero"
else
  pass "an unparseable plan exits non-zero"
fi
rm -f "$BAD"

# --------------------------------------------------------------------------
head_ "triage: evidence is gathered without a cluster"

# Every endpoint is unreachable here on purpose. The tool must still produce a
# usable comment and must label the gaps as gaps -- a triage aid that reports
# "0 errors" because Prometheus was down is worse than one that reports nothing.
TRIAGE="$(python3 "$ROOT/tools/aiops/triage.py" --alert "$FIX/alert.json" \
  --prometheus http://127.0.0.1:9 --loki http://127.0.0.1:9 2>/dev/null)"

assert_has "the alert name and severity lead the summary" \
  'HighErrorRate (critical)' "$TRIAGE"
assert_has "the runbook link is surfaced" \
  'docs/runbooks/high-error-rate.md' "$TRIAGE"
assert_has "the service is resolved from the app label" \
  'timeline-service' "$TRIAGE"
assert_has "an unreachable Prometheus is reported as unreachable" \
  'unreachable' "$TRIAGE"
assert_lacks "an unreachable Prometheus is never reported as zero" \
  '| error ratio (5m) | 0 |' "$TRIAGE"
assert_has "the queries used are printed so a conclusion is traceable" \
  'histogram_quantile' "$TRIAGE"
assert_has "the error-ratio query guards its empty numerator" \
  'or vector(0)' "$TRIAGE"
assert_has "the comment states it made no mutating calls" \
  'read-only, no mutating calls' "$TRIAGE"

# The whole safety claim of the triage agent is that it cannot change anything.
# Asserted against the source, because a reviewer cannot be expected to re-read
# it on every change.
head_ "triage: the agent has no mutating code path"
for verb in 'urlopen.*method="POST"' 'kubectl' 'subprocess' 'os.system' 'boto3'; do
  if grep -qE "$verb" "$ROOT/tools/aiops/triage.py"; then
    fail "triage.py must not reference $verb"
  else
    pass "triage.py does not reference $verb"
  fi
done

# --------------------------------------------------------------------------
head_ "the safe-execution contract exists and is specific"
for clause in 'never applies' 'read-only' 'AIOPS_PROVIDER'; do
  if grep -qiF "$clause" "$ROOT/AGENTS.md" 2>/dev/null; then
    pass "AGENTS.md covers: $clause"
  else
    fail "AGENTS.md is missing: $clause"
  fi
done

# --------------------------------------------------------------------------
head_ "documents the output points at actually exist"
# Every generated comment footers with "see docs/07-aiops.md", and AGENTS.md
# cites it too. That reference was dead for the first half of this phase: the
# comment rendered perfectly and sent the reader to a 404. A citation is a
# claim, and an unchecked one rots the moment the file is renamed.
for ref in docs/07-aiops.md AGENTS.md; do
  if [ -f "$ROOT/$ref" ]; then
    pass "referenced document exists: $ref"
  else
    fail "referenced document is missing: $ref"
  fi
done
assert_has "the comment cites where severities come from" \
  'docs/07-aiops.md' "$SAFE"

printf '\n%d passed, %d failed\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
