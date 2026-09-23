#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Phase 6 — KodeKloud playground capability probe.
#
#   scripts/probe.sh [--keep]
#
# Four rows of the gap register (9, 10, 11, 20) currently rest on a presumption
# rather than on a fact. The KodeKloud documentation lists what is explicitly
# permitted and is silent on everything else, and silence is not prohibition.
# This script replaces each presumption with a measured answer.
#
# WHY A SCRIPT AND NOT A CHECKLIST
#
# The session is 180 minutes and the probe is not the demo — it is the thing
# that has to finish before the demo is worth designing. A human working
# through a checklist in the console will spend forty of those minutes and
# produce an answer nobody can re-derive next week. This produces a committed
# artefact: docs/06-probe-report.md, machine-generated, timestamped, and
# diffable against the next session.
#
# DESIGN RULES, each of which cost something to learn:
#
#   1. EVERY PROBE IS NON-FATAL. A denial is the result, not an error. The
#      script must run to completion on an account that denies all of it, so
#      `set -e` is deliberately absent and every call is wrapped.
#
#   2. EVERY PROBE CLEANS UP AFTER ITSELF, in reverse order, via a trap. In a
#      180-minute account a leaked NAT gateway or a leaked load balancer is
#      not a cost problem, it is a quota problem that breaks the real run.
#      `--keep` exists for the case where a probe fails and the wreckage is
#      the evidence.
#
#   3. DENIALS ARE CLASSIFIED, NOT JUST RECORDED. `AccessDenied` on an SCP
#      boundary and `UnauthorizedOperation` on a missing IAM grant mean very
#      different things: the first is immovable, the second is a policy edit.
#      A report that flattens both to "failed" is worth nothing.
#
#   4. TIMINGS ARE PART OF THE RESULT. "EKS can be created" is not actionable;
#      "the control plane took 11m40s" is, because it decides whether the
#      session budget in docs/02-workflow.md §9 survives contact.
#
# The probe reads nothing and writes nothing outside a `probe-` name prefix.
# ---------------------------------------------------------------------------
set -uo pipefail

KEEP=false
[[ "${1:-}" == "--keep" ]] && KEEP=true

REPORT="${REPORT:-docs/06-probe-report.md}"
PREFIX="probe-$(date +%s)"
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"

# Cleanup actions, executed last-in-first-out.
declare -a TEARDOWN=()
defer() { TEARDOWN+=("$1"); }

cleanup() {
  if [[ "${KEEP}" == true ]]; then
    printf '\n--keep: leaving %d resources behind. Names start with %s.\n' \
      "${#TEARDOWN[@]}" "${PREFIX}"
    return
  fi
  printf '\ncleaning up %d resources...\n' "${#TEARDOWN[@]}"
  for (( i=${#TEARDOWN[@]}-1; i>=0; i-- )); do
    eval "${TEARDOWN[$i]}" >/dev/null 2>&1 \
      || printf '  WARN  teardown failed: %s\n' "${TEARDOWN[$i]}"
  done
}
trap cleanup EXIT INT TERM

# --- reporting -------------------------------------------------------------

declare -a ROWS=()

# record <row> <capability> <verdict> <detail>
#   verdict is YES | NO | PARTIAL
record() {
  ROWS+=("$1|$2|$3|$4")
  local mark
  case "$3" in
    YES) mark='  YES ' ;;
    NO) mark='  NO  ' ;;
    *) mark='  ~   ' ;;
  esac
  printf '%s %-46s %s\n' "${mark}" "$2" "$4"
}

# Classify an AWS CLI failure. The distinction is the point of the probe: an
# explicit deny from a service control policy is a wall, while a missing IAM
# action is a one-line policy change, and both print "An error occurred".
classify() {
  local err="$1"
  case "${err}" in
    *AccessDeniedException*|*AccessDenied*|*"explicit deny"*)
      echo "denied by policy" ;;
    *UnauthorizedOperation*|*"not authorized to perform"*)
      echo "IAM grant missing" ;;
    *OptInRequired*|*SubscriptionRequired*)
      echo "service not enabled in account" ;;
    *LimitExceeded*|*QuotaExceeded*|*"Maximum number"*)
      echo "quota exhausted" ;;
    *InvalidClientTokenId*|*ExpiredToken*)
      echo "CREDENTIALS EXPIRED — rerun" ;;
    *UnrecognizedClientException*|*EndpointConnectionError*)
      echo "service unavailable in ${REGION}" ;;
    *) printf '%s' "${err}" | tr '\n' ' ' | cut -c1-90 ;;
  esac
}

# run <cmd...> — executes, captures combined output in TRY_OUT, and sets TRY_OK
# and a classified TRY_ERR.
#
# NOTE: this must NOT be called inside a command substitution. An earlier version
# was used as `out=$(try aws ...)`, which runs the function in a subshell, so
# TRY_OK was assigned in a process the caller could not see and every single
# probe reported NO with an empty reason. The stub harness caught it; a real
# session would have produced a confident, entirely false report.
TRY_OK=false
TRY_ERR=""
TRY_OUT=""
run() {
  if TRY_OUT=$("$@" 2>&1); then
    TRY_OK=true
    TRY_ERR=""
  else
    TRY_OK=false
    TRY_ERR="$(classify "${TRY_OUT}")"
  fi
}

elapsed() { printf '%dm%02ds' $(( $1 / 60 )) $(( $1 % 60 )); }

# --- preflight -------------------------------------------------------------

command -v aws >/dev/null || { echo "aws CLI not found"; exit 1; }

IDENTITY=$(aws sts get-caller-identity --output json 2>&1) || {
  echo "no usable AWS credentials:"; echo "${IDENTITY}"; exit 1
}
ACCOUNT=$(printf '%s' "${IDENTITY}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["Account"])')
CALLER=$(printf '%s' "${IDENTITY}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["Arn"])')

printf 'probing account %s in %s\n' "${ACCOUNT}" "${REGION}"
printf 'as %s\n\n' "${CALLER}"

STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
T0=$(date +%s)

# --- row 6: DynamoDB PITR and a customer-managed key -----------------------
# The register claims near-zero gap on the data layer. That claim is only true
# if the *production* settings are also reachable here; if PITR is denied, row
# 6 has a gap it does not currently admit.

TABLE="${PREFIX}-table"
run aws dynamodb create-table \
  --table-name "${TABLE}" \
  --attribute-definitions AttributeName=pk,AttributeType=S \
  --key-schema AttributeName=pk,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --region "${REGION}"
if [[ "${TRY_OK}" == true ]]; then
  defer "aws dynamodb delete-table --table-name ${TABLE} --region ${REGION}"
  aws dynamodb wait table-exists --table-name "${TABLE}" --region "${REGION}" 2>/dev/null

  run aws dynamodb update-continuous-backups \
    --table-name "${TABLE}" \
    --point-in-time-recovery-specification PointInTimeRecoveryEnabled=true \
    --region "${REGION}"
  if [[ "${TRY_OK}" == true ]]; then
    record 6 "DynamoDB point-in-time recovery" YES "prod setting reachable in Tier S"
  else
    record 6 "DynamoDB point-in-time recovery" NO "${TRY_ERR}"
  fi

  # Streams matter more than either: row 8 claims no gap, and that claim is
  # load-bearing for the entire event transport.
  run aws dynamodb update-table --table-name "${TABLE}" \
    --stream-specification StreamEnabled=true,StreamViewType=NEW_AND_OLD_IMAGES \
    --region "${REGION}"
  if [[ "${TRY_OK}" == true ]]; then
    record 8 "DynamoDB Streams (NEW_AND_OLD_IMAGES)" YES "event transport confirmed"
  else
    record 8 "DynamoDB Streams (NEW_AND_OLD_IMAGES)" NO "${TRY_ERR} — ADR-0012 is invalid here"
  fi
else
  record 6 "DynamoDB table creation" NO "${TRY_ERR}"
  record 8 "DynamoDB Streams" NO "untested — no table"
fi

run aws kms create-key --description "${PREFIX}" --region "${REGION}"
if [[ "${TRY_OK}" == true ]]; then
  KEY_ID=$(printf '%s' "${TRY_OUT}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["KeyMetadata"]["KeyId"])')
  # KMS keys cannot be deleted, only scheduled, and 7 days is the floor.
  defer "aws kms schedule-key-deletion --key-id ${KEY_ID} --pending-window-in-days 7 --region ${REGION}"
  record 6 "KMS customer-managed key" YES "created ${KEY_ID:0:8}…"
else
  record 6 "KMS customer-managed key" NO "${TRY_ERR}"
fi

# --- row 10: Secrets Manager ------------------------------------------------
# The presumption is that the playground restricts secret names to the RDS
# rotation prefix. If a free-form name is accepted, External Secrets Operator
# becomes demonstrable in Tier S and row 10 collapses to near-zero gap.

SECRET="${PREFIX}-secret"
run aws secretsmanager create-secret \
  --name "${SECRET}" --secret-string '{"probe":"true"}' --region "${REGION}"
if [[ "${TRY_OK}" == true ]]; then
  defer "aws secretsmanager delete-secret --secret-id ${SECRET} --force-delete-without-recovery --region ${REGION}"
  record 10 "Secrets Manager, free-form secret name" YES "ESO path is demonstrable"
else
  record 10 "Secrets Manager, free-form secret name" NO "${TRY_ERR}"
fi

# --- row 9: IRSA ------------------------------------------------------------
# The single highest-value question in the probe. IRSA is the difference
# between "every pod shares the node role" and per-service least privilege.
# Creating an EKS cluster costs 11-15 minutes, so this checks the two things
# that can be checked without one: whether an OIDC provider can be registered
# at all, and whether a role with a web-identity trust policy is accepted.

run aws iam list-open-id-connect-providers --region "${REGION}"
if [[ "${TRY_OK}" == true ]]; then
  ROLE="${PREFIX}-irsa"
  TRUST=$(cat <<JSON
{"Version":"2012-10-17","Statement":[{
  "Effect":"Allow",
  "Principal":{"Federated":"arn:aws:iam::${ACCOUNT}:oidc-provider/oidc.eks.${REGION}.amazonaws.com/id/PROBE"},
  "Action":"sts:AssumeRoleWithWebIdentity"}]}
JSON
)
  run aws iam create-role --role-name "${ROLE}" \
    --assume-role-policy-document "${TRUST}"
  if [[ "${TRY_OK}" == true ]]; then
    defer "aws iam delete-role --role-name ${ROLE}"
    record 9 "IAM role with web-identity trust policy" YES "IRSA shape accepted"
  else
    # A rejection here is expected and informative: IAM validates that the
    # federated principal exists, so this specific failure means only that the
    # cluster is absent, not that IRSA is forbidden.
    case "${TRY_ERR}" in
      *"denied by policy"*|*"IAM grant missing"*)
        record 9 "IAM role with web-identity trust policy" NO "${TRY_ERR}" ;;
      *)
        record 9 "IAM role with web-identity trust policy" PARTIAL \
          "role API reachable; needs a live cluster OIDC issuer to confirm" ;;
    esac
  fi
else
  record 9 "IAM OIDC provider API" NO "${TRY_ERR}"
fi

# --- row 11: ACM and ALB ----------------------------------------------------
# ACM is probed by requesting a certificate for a domain we do not own. The
# request itself is what is being tested, not validation -- if the API accepts
# it, ACM is usable and row 11's blocker is domain ownership rather than the
# platform, which is a materially different problem.

run aws acm request-certificate \
  --domain-name "probe.invalid" --validation-method DNS --region "${REGION}"
if [[ "${TRY_OK}" == true ]]; then
  CERT_ARN=$(printf '%s' "${TRY_OUT}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["CertificateArn"])')
  defer "aws acm delete-certificate --certificate-arn ${CERT_ARN} --region ${REGION}"
  record 11 "ACM certificate request" YES "blocker is domain ownership, not ACM"
else
  record 11 "ACM certificate request" NO "${TRY_ERR}"
fi

run aws route53 list-hosted-zones
if [[ "${TRY_OK}" == true ]]; then
  COUNT=$(printf '%s' "${TRY_OUT}" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["HostedZones"]))')
  record 11 "Route53 API" YES "${COUNT} hosted zone(s) visible"
else
  record 11 "Route53 API" NO "${TRY_ERR}"
fi

# Elastic Load Balancing v2 is what the AWS Load Balancer Controller drives.
# Describing is enough: if the caller cannot describe, the controller cannot
# reconcile, and Ingress in Tier S falls back to a NodePort.
run aws elbv2 describe-load-balancers --region "${REGION}"
if [[ "${TRY_OK}" == true ]]; then
  record 11 "ELBv2 API (AWS Load Balancer Controller)" YES "Ingress path viable"
else
  record 11 "ELBv2 API (AWS Load Balancer Controller)" NO "${TRY_ERR}"
fi

# --- row 20: Bedrock --------------------------------------------------------

run aws bedrock list-foundation-models --region "${REGION}"
if [[ "${TRY_OK}" == true ]]; then
  COUNT=$(printf '%s' "${TRY_OUT}" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["modelSummaries"]))')
  record 20 "Bedrock foundation models" PARTIAL "${COUNT} listed — listing is not invocation"
else
  record 20 "Bedrock foundation models" NO "${TRY_ERR}"
fi

# --- supporting capabilities ------------------------------------------------

# probe_api <row> <label> <detail-on-success> <cmd...>
probe_api() {
  local row="$1" label="$2" detail="$3"; shift 3
  run "$@"
  if [[ "${TRY_OK}" == true ]]; then
    record "${row}" "${label}" YES "${detail}"
  else
    record "${row}" "${label}" NO "${TRY_ERR}"
  fi
}

probe_api 19 "ECR API" "publish target available" \
  aws ecr describe-repositories --region "${REGION}"
probe_api 4 "EKS API" "cluster creation reachable" \
  aws eks list-clusters --region "${REGION}"
probe_api 3 "EC2 API" "VPC and node groups reachable" \
  aws ec2 describe-account-attributes --region "${REGION}"

# --- write the report -------------------------------------------------------

TOTAL=$(( $(date +%s) - T0 ))
printf '\nprobe finished in %s\n' "$(elapsed "${TOTAL}")"

mkdir -p "$(dirname "${REPORT}")"
{
  cat <<MD
# 06 — Capability probe report

**Generated by \`scripts/probe.sh\`. Do not edit by hand; rerun the probe.**

| | |
|---|---|
| Account | \`${ACCOUNT}\` |
| Region | \`${REGION}\` |
| Caller | \`${CALLER}\` |
| Started | ${STARTED} |
| Duration | $(elapsed "${TOTAL}") |

This report replaces the presumptions in
[16-gap-register.md](16-gap-register.md) with measured facts. A \`NO\` here is a
result, not a failure: the register is more valuable when it records a wall
accurately than when it records an aspiration.

The distinction between *denied by policy* and *IAM grant missing* is the one to
read first. The former is a service control policy and cannot be worked around
from inside the account. The latter is a permissions edit, which means the
capability exists and is merely ungranted.

| Register row | Capability | Verdict | Detail |
|---|---|---|---|
MD
  for row in "${ROWS[@]}"; do
    IFS='|' read -r n cap verdict detail <<< "${row}"
    printf '| %s | %s | **%s** | %s |\n' "${n}" "${cap}" "${verdict}" "${detail}"
  done
  cat <<'MD'

## What this does not answer

Three things are deliberately out of scope, because each costs more than the
whole probe:

- **Whether IRSA actually works end to end.** Confirming it needs a live EKS
  cluster, which is 11–15 minutes of the session before anything is learned.
  The probe establishes only that the IAM and OIDC APIs are reachable and that
  a web-identity trust policy is accepted in principle.
- **Whether an ALB is actually provisioned.** Requires a cluster, the AWS Load
  Balancer Controller, and an Ingress. The probe confirms only that the ELBv2
  API answers.
- **Whether a Bedrock model is invocable.** Listing models is an IAM action on
  the control plane; invocation is a separate action on a separate endpoint and
  usually needs per-model access to be granted first.

Each becomes a follow-up inside the first session that gets a cluster up, and
the answers belong in the same table.

## Next

Amend the affected rows of [16-gap-register.md](16-gap-register.md) in the same
pull request as this report. A probe result that does not change the register is
a probe that was not worth running.
MD
} > "${REPORT}"

printf 'report written to %s\n' "${REPORT}"
