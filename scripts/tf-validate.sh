#!/usr/bin/env bash
#
# Validate every Terraform root and module without touching AWS.
#
# Three layers, in increasing order of usefulness:
#
#   1. fmt + validate        -- syntax and type checking. Catches typos.
#   2. tflint + checkov      -- generic lint and policy. Catches the well-known.
#   3. the assertions below  -- catches what is specific to *this* design, which
#                               is the only part a generic tool cannot do.
#
# Layer 3 exists because `terraform validate` is happy with a production root
# that has deletion protection off and a bucket open to the world. It type-checks
# a configuration; it has no opinion about whether the configuration is correct.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_DIR="$ROOT/infra/terraform"

TERRAFORM="${TERRAFORM:-terraform}"
command -v "$TERRAFORM" >/dev/null 2>&1 || {
  echo "terraform not found. Set TERRAFORM=/path/to/terraform." >&2
  exit 1
}

PASS=0
FAIL=0

ok() {
  PASS=$((PASS + 1))
  printf '  \033[32mok\033[0m    %s\n' "$1"
}

bad() {
  FAIL=$((FAIL + 1))
  printf '  \033[31mFAIL\033[0m  %s\n' "$1"
}

log() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }

# assert_grep <description> <pattern> <file>
assert_grep() {
  if grep -qE -- "$2" "$3" 2>/dev/null; then ok "$1"; else bad "$1"; fi
}

# refute_grep <description> <pattern> <file>
refute_grep() {
  if grep -qE -- "$2" "$3" 2>/dev/null; then bad "$1"; else ok "$1"; fi
}

ROOTS=(envs/sandbox envs/prod)
MODULES=(network dynamodb storage registry database eks iam)

# ---------------------------------------------------------------------------
log "terraform fmt"
# ---------------------------------------------------------------------------
if "$TERRAFORM" fmt -check -recursive "$TF_DIR" >/tmp/tf-fmt.out 2>&1; then
  ok "every file is canonically formatted"
else
  bad "unformatted files (run: terraform fmt -recursive infra/terraform)"
  sed 's/^/        /' /tmp/tf-fmt.out
fi

# ---------------------------------------------------------------------------
log "terraform validate"
# ---------------------------------------------------------------------------
# -backend=false everywhere. The prod root declares an S3 backend that does not
# exist, and a real init would try to reach it and fail on a bucket rather than
# on the configuration -- which is the thing being checked.
for d in "${MODULES[@]/#/modules/}" "${ROOTS[@]}"; do
  path="$TF_DIR/$d"
  [ -d "$path" ] || continue
  if (cd "$path" && "$TERRAFORM" init -backend=false -input=false -no-color >/tmp/tf-init.out 2>&1 &&
    "$TERRAFORM" validate -no-color >/tmp/tf-val.out 2>&1); then
    ok "$d"
  else
    bad "$d"
    sed 's/^/        /' /tmp/tf-init.out /tmp/tf-val.out | tail -20
  fi
done

# ---------------------------------------------------------------------------
log "provider lock files"
# ---------------------------------------------------------------------------
for r in "${ROOTS[@]}"; do
  lock="$TF_DIR/$r/.terraform.lock.hcl"
  if [ ! -f "$lock" ]; then
    bad "$r has no .terraform.lock.hcl"
    continue
  fi
  # A lock generated on a Mac records only darwin_arm64 hashes. CI runs on
  # linux_amd64, finds no matching hash and fails an init that worked locally --
  # with a checksum error that reads like a supply-chain compromise.
  # A lock generated on a Mac records one h1 hash per provider -- darwin_arm64
  # only. CI runs on linux_amd64, finds no matching hash, and fails an init that
  # worked locally with a checksum error that reads like a supply-chain
  # compromise. The file does not name platforms, so the observable signal is the
  # hash count: one h1 per provider means one platform. Regenerate with:
  #   terraform providers lock -platform=linux_amd64 -platform=darwin_arm64
  thin="$(awk '
    /^provider "/ { name = $2; h1 = 0 }
    /h1:/         { h1++ }
    /^}/          { if (name != "" && h1 < 2) print name; name = "" }
  ' "$lock")"
  if ! grep -q 'zh:' "$lock"; then
    bad "$r lock file has no zh: hashes"
  elif [ -n "$thin" ]; then
    bad "$r lock file is single-platform for: $(echo "$thin" | tr '\n' ' ')"
  else
    ok "$r lock file is multi-platform and records upstream hashes"
  fi
done

# ---------------------------------------------------------------------------
log "tflint"
# ---------------------------------------------------------------------------
if command -v tflint >/dev/null 2>&1; then
  for d in "${MODULES[@]/#/modules/}" "${ROOTS[@]}"; do
    path="$TF_DIR/$d"
    [ -d "$path" ] || continue
    if (cd "$path" && tflint --no-color --force >/tmp/tflint.out 2>&1); then
      ok "tflint $d"
    else
      bad "tflint $d"
      sed 's/^/        /' /tmp/tflint.out
    fi
  done
else
  printf '  \033[33mskip\033[0m  tflint not installed\n'
fi

# ---------------------------------------------------------------------------
log "checkov"
# ---------------------------------------------------------------------------
if command -v checkov >/dev/null 2>&1; then
  # Soft-fail on the sandbox root: it is *deliberately* non-compliant, and a
  # policy scanner that reports the intended gaps as failures teaches people to
  # pass --skip-check until it is silent.
  if checkov -d "$TF_DIR/envs/prod" --quiet --compact --framework terraform >/tmp/checkov.out 2>&1; then
    ok "checkov envs/prod"
  else
    bad "checkov envs/prod"
    # Print every failing resource, never a tail. An earlier version of this
    # showed `tail -30`, which rendered 7 of 25 findings while looking like the
    # whole report -- the reader fixes the 7, sees green, and ships the 18.
    # A truncated report that does not say it is truncated is gap S40 exactly.
    n=$(grep -c 'FAILED for resource' /tmp/checkov.out || true)
    printf '        %s failing resource(s):\n' "$n"
    grep -E '^Check: CKV|FAILED for resource' /tmp/checkov.out |
      paste - - 2>/dev/null | sed 's/^/        /'
  fi
  checkov -d "$TF_DIR/envs/sandbox" --quiet --compact --framework terraform >/tmp/checkov-sbx.out 2>&1 || true
  printf '  \033[33mnote\033[0m  sandbox findings are expected; see docs/16-gap-register.md\n'
else
  printf '  \033[33mskip\033[0m  checkov not installed\n'
fi

# ---------------------------------------------------------------------------
log "design invariants (what the generic tools cannot know)"
# ---------------------------------------------------------------------------

SBX="$TF_DIR/envs/sandbox/main.tf"
PRD="$TF_DIR/envs/prod/main.tf"

# The whole point of the dynamodb module: one schema, two consumers. If somebody
# restates the tables in HCL, LocalStack and AWS drift and the difference shows
# up as a ValidationException on a GSI that exists locally.
assert_grep "dynamodb module reads tools/dynamodb-tables.json" \
  'jsondecode' "$TF_DIR/modules/dynamodb/main.tf"
assert_grep "sandbox points at the shared schema file" \
  'tools/dynamodb-tables\.json' "$SBX"
assert_grep "prod points at the same schema file" \
  'tools/dynamodb-tables\.json' "$PRD"

# Production safety rails. Each of these is a one-word edit away from being wrong
# and none of them is caught by `validate`.
assert_grep "prod enables PITR" 'point_in_time_recovery = true' "$PRD"
assert_grep "prod enables table deletion protection" 'deletion_protection    = true' "$PRD"
assert_grep "prod enables bucket versioning" 'versioning    = true' "$PRD"
refute_grep "prod never force-destroys the media bucket" 'force_destroy = true' "$PRD"
refute_grep "prod never force-deletes ECR" 'force_delete = true' "$PRD"
assert_grep "prod encrypts with a customer-managed key" 'aws_kms_key\.data\.arn' "$PRD"
assert_grep "prod envelope-encrypts etcd secrets" 'secrets_kms_key_arn' "$PRD"
assert_grep "prod keeps the API server off the internet" 'endpoint_public_access = false' "$PRD"
assert_grep "prod builds its own VPC" 'use_default_vpc = false' "$PRD"
assert_grep "prod creates the OIDC provider" 'create_oidc_provider = true' "$PRD"

# The sandbox is allowed to be wrong -- but only in ways it admits to. A sandbox
# that quietly enabled deletion protection would strand resources in an account
# nobody can get back into.
assert_grep "sandbox force-destroys the bucket so destroy can succeed" \
  'force_destroy = true' "$SBX"
assert_grep "sandbox disables table deletion protection" \
  'deletion_protection    = false' "$SBX"
assert_grep "sandbox adopts the default VPC" 'use_default_vpc = true' "$SBX"

# No real account id may be committed. The prod values files use 000000000000 on
# purpose; a 12-digit number that is not that is almost certainly somebody's.
if grep -rnE 'arn:aws:iam::[0-9]{12}:' "$TF_DIR" --include='*.tf' |
  grep -vE '000000000000|123456789012' >/tmp/tf-acct.out 2>/dev/null; then
  bad "a real-looking AWS account id is committed"
  sed 's/^/        /' /tmp/tf-acct.out
else
  ok "no real AWS account ids in .tf files"
fi

# tfvars hold session-specific ARNs and, for the search database, nothing at all
# -- but the habit is what matters.
if git -C "$ROOT" ls-files --error-unmatch '*.tfvars' >/dev/null 2>&1; then
  bad "a .tfvars file is tracked by git"
else
  ok "no .tfvars files are tracked"
fi

# State must never be committed. This is the one mistake that is unrecoverable:
# state contains the generated database password in plaintext.
if git -C "$ROOT" ls-files --error-unmatch '*.tfstate' >/dev/null 2>&1; then
  bad "terraform state is tracked by git"
else
  ok "no state files are tracked"
fi

# IRSA trust policies need both conditions. With only :sub the role is still
# assumable by any service account the issuer will vouch for; the cluster keeps
# working, so nothing surfaces the loss.
assert_grep "IRSA trust policy constrains :sub" ':sub"' "$TF_DIR/modules/iam/main.tf"
assert_grep "IRSA trust policy constrains :aud" ':aud"' "$TF_DIR/modules/iam/main.tf"

# The IRSA service names have to match the Helm workload names, because the
# trust policy's sub is system:serviceaccount:<ns>:<name>. A rename on one side
# produces pods that silently fall back to the node role.
for svc in gateway user-service tweet-service timeline-service fanout-worker tweet-indexer; do
  if [ -f "$ROOT/deploy/envs/prod/$svc.yaml" ] &&
    grep -qE "^    ${svc} = \{|^    ${svc} = \{\}" "$TF_DIR/modules/iam/variables.tf"; then
    ok "IRSA role exists for workload $svc"
  elif [ -f "$ROOT/deploy/envs/prod/$svc.yaml" ]; then
    bad "workload $svc has no IRSA grant in modules/iam/variables.tf"
  fi
done

# ECR must refuse mutable tags, for the same reason the chart refuses `latest`:
# a rollback target has to identify bytes, not a name someone can repoint.
assert_grep "ECR tags are immutable" 'image_tag_mutability = "IMMUTABLE"' \
  "$TF_DIR/modules/registry/main.tf"

# A database initiates no connections. Every egress rule on its security group is
# either unused or a path out for something that should not be running there.
#
# This lives here rather than in modules/database/tests because `terraform test`
# can only assert over resources that are declared -- the absence of a resource
# type is invisible to it, and referencing one that does not exist fails to parse
# rather than evaluating false. Absence is a grep problem.
refute_grep "the search database has no egress rule" \
  'aws_vpc_security_group_egress_rule' "$TF_DIR/modules/database/main.tf"

# Same shape, same reason: ingress by security-group reference only. A CIDR rule
# keeps matching after the workload it was written for is replaced, and admits
# whatever occupies that range next.
refute_grep "database ingress is never by CIDR" \
  'cidr_ipv4' "$TF_DIR/modules/database/main.tf"

# ---------------------------------------------------------------------------
# Witnesses for the controls checkov cannot see.
#
# Each of these corresponds to a `checkov:skip` in the module. A skip with no
# replacement assertion is just a deleted control with a comment on it: the
# resource can be removed later and nothing anywhere goes red. These are the
# reason those skips are defensible.
NET="$TF_DIR/modules/network/main.tf"

# CKV2_AWS_11 -- checkov cannot follow vpc_id to a count-indexed aws_vpc.
assert_grep "the VPC has flow logs (CKV2_AWS_11 witness)" \
  'resource "aws_flow_log"' "$NET"
assert_grep "flow logs capture rejects as well as accepts" \
  'traffic_type *= *"ALL"' "$NET"

# CKV2_AWS_12 -- same resolver limitation. The default security group ships
# wide open and is only safe because it is adopted and emptied.
assert_grep "the default security group is adopted (CKV2_AWS_12 witness)" \
  'resource "aws_default_security_group"' "$NET"

# CKV_AWS_300 -- the rule exists; checkov judges the configuration as a whole.
assert_grep "media uploads abort incomplete multipart (CKV_AWS_300 witness)" \
  'abort_incomplete_multipart_upload' "$TF_DIR/modules/storage/main.tf"

# CKV2_AWS_69 is satisfied by a parameter, which no scanner reads back.
assert_grep "the database refuses plaintext connections" \
  'rds\.force_ssl' "$TF_DIR/modules/database/main.tf"

# ---------------------------------------------------------------------------
printf '\n\033[1m%d passed, %d failed\033[0m\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
