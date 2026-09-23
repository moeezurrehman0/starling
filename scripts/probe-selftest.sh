#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Self-test for scripts/probe.sh.
#
#   scripts/probe-selftest.sh
#
# WHY THIS EXISTS
#
# The probe runs exactly once per KodeKloud session, inside a 180-minute clock,
# against an account nobody can reproduce. Debugging it there costs the session.
# So it is tested here instead, against a stub `aws` placed ahead of the real
# one on PATH, which returns a deliberately mixed set of successes, denials and
# service errors.
#
# This is not theoretical. The first version of the probe called its wrapper as
# `out=$(try aws ...)`, which runs the function in a subshell -- so the success
# flag was assigned in a process the caller could not see, and every single
# capability reported NO with a blank reason. Against a real account that would
# have produced a confident, authoritative, entirely false report, and the gap
# register would have been rewritten from it.
#
# The stub asserts three things the probe must get right:
#   1. a success is reported as YES,
#   2. a denial is classified by *kind* -- policy deny, missing IAM grant and
#      service-unavailable are three different findings, not one,
#   3. teardown runs for exactly the resources that were created.
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."

STUB=$(mktemp -d)
REPORT=$(mktemp)
trap 'rm -rf "${STUB}" "${REPORT}"' EXIT

cat > "${STUB}/aws" <<'STUB'
#!/usr/bin/env bash
# A mixed-verdict account: some things work, some are denied by policy, some
# lack an IAM grant, and one service is absent from the region. Every branch of
# classify() is exercised.
case "$*" in
  "sts get-caller-identity"*)
    echo '{"Account":"123456789012","Arn":"arn:aws:iam::123456789012:user/probe"}'; exit 0;;
  "dynamodb create-table"*)        echo '{"TableDescription":{}}'; exit 0;;
  "dynamodb wait"*)                exit 0;;
  "dynamodb delete-table"*)        echo '{}'; exit 0;;
  "dynamodb update-continuous-backups"*)
    echo 'An error occurred (AccessDeniedException) when calling the operation'; exit 255;;
  "dynamodb update-table"*)        echo '{}'; exit 0;;
  "kms create-key"*)               echo '{"KeyMetadata":{"KeyId":"abcd1234-aaaa"}}'; exit 0;;
  "kms schedule-key-deletion"*)    echo '{}'; exit 0;;
  "secretsmanager create-secret"*)
    echo 'User: x is not authorized to perform: secretsmanager:CreateSecret'; exit 255;;
  "iam list-open-id-connect-providers"*) echo '{"OpenIDConnectProviderList":[]}'; exit 0;;
  "iam create-role"*)
    echo 'An error occurred (InvalidInput) when calling the CreateRole operation'; exit 255;;
  "acm request-certificate"*)
    echo '{"CertificateArn":"arn:aws:acm:us-east-1:1:certificate/x"}'; exit 0;;
  "acm delete-certificate"*)       echo '{}'; exit 0;;
  "route53 list-hosted-zones"*)    echo '{"HostedZones":[]}'; exit 0;;
  "elbv2 describe-load-balancers"*) echo '{"LoadBalancers":[]}'; exit 0;;
  "bedrock list-foundation-models"*)
    echo 'Could not connect to the endpoint URL: EndpointConnectionError'; exit 255;;
  "ecr describe-repositories"*)    echo '{"repositories":[]}'; exit 0;;
  "eks list-clusters"*)            echo '{"clusters":[]}'; exit 0;;
  "ec2 describe-account-attributes"*) echo '{"AccountAttributes":[]}'; exit 0;;
  *) echo '{}'; exit 0;;
esac
STUB
chmod +x "${STUB}/aws"

OUT=$(PATH="${STUB}:${PATH}" REPORT="${REPORT}" AWS_REGION=us-east-1 bash scripts/probe.sh 2>&1)

fail() { printf '  FAIL  %s\n' "$1"; printf '\n--- probe output ---\n%s\n' "${OUT}"; exit 1; }
pass() { printf '  PASS  %s\n' "$1"; }

printf 'self-testing scripts/probe.sh\n'

# 1. A working capability is YES, not NO. This is the assertion that would have
#    caught the subshell bug: before the fix, every line here read NO.
grep -q 'YES.*DynamoDB Streams'  <<< "${OUT}" || fail "a successful call was not reported as YES"
grep -q 'YES.*ECR API'           <<< "${OUT}" || fail "ECR success was not reported as YES"
pass "successes are reported as YES"

# 2. Denials are classified by kind, not flattened into one bucket.
grep -q 'denied by policy'                 <<< "${OUT}" || fail "AccessDeniedException was not classified as a policy deny"
grep -q 'IAM grant missing'                <<< "${OUT}" || fail "'not authorized to perform' was not classified as a missing grant"
grep -q 'service unavailable in us-east-1' <<< "${OUT}" || fail "an endpoint error was not classified as service-unavailable"
pass "denials are classified by kind"

# 3. A capability that cannot be settled without a live cluster is PARTIAL, so
#    the register is not rewritten from an inconclusive result.
grep -q '~ .*web-identity'  <<< "${OUT}" || fail "the inconclusive IRSA probe was not reported as PARTIAL"
pass "inconclusive results are PARTIAL, not YES or NO"

# 4. Everything created is torn down. A leaked resource in a capped account is a
#    quota failure in the run that matters, not a cost problem.
grep -qE 'cleaning up [1-9][0-9]* resources' <<< "${OUT}" || fail "teardown did not run"
grep -q 'WARN  teardown failed'              <<< "${OUT}" && fail "a teardown step failed"
pass "every created resource is torn down"

# 5. The report is a real artefact, not an empty file.
[[ -s "${REPORT}" ]]                      || fail "no report was written"
grep -q '^| Register row' "${REPORT}"     || fail "the report has no results table"
grep -q '123456789012' "${REPORT}"        || fail "the report does not record the account"
grep -qE '^\| [0-9]+ \|' "${REPORT}"      || fail "the report table has no rows"
pass "a populated report is written"

printf '\nprobe self-test passed.\n'
