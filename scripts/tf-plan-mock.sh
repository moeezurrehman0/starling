#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# Produce `terraform show -json` for a root that is never applied, on a machine
# with no AWS credentials.
#
# Why this exists
# ---------------
# The Terraform risk commenter (Phase 12) is only worth having if it runs on
# every pull request. A commenter that runs "when someone has a playground
# session open" would comment roughly never, and the useful signal -- this PR
# replaces a table, this PR opens a security group -- arrives after review
# rather than during it.
#
# `terraform validate` needs no credentials but produces no plan, and a plan is
# the only artifact that distinguishes a replacement from an update. So the plan
# has to be real. This script makes it real by pointing the three AWS APIs the
# configuration actually calls at a LocalStack container and overriding
# credential validation. Everything else -- module wiring, `for_each` expansion,
# rendered IAM policy documents, computed names -- is genuine, because Terraform
# evaluates all of it locally.
#
# What it is NOT
# --------------
# This is not a substitute for a plan against real state. It always reports a
# create-everything plan, because there is no state to diff against. It is a
# check on the *shape* of the configuration, which is exactly what a reviewer
# looking at a diff needs. `scripts/tf-test.sh` covers invariants;
# `scripts/tf-validate.sh` covers syntax and policy.

set -euo pipefail

ROOT="${1:-infra/terraform/envs/prod}"
OUT="${2:-/tmp/plan.json}"
ENDPOINT="${LOCALSTACK_ENDPOINT:-http://localhost:4566}"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${repo_root}"

if [ ! -d "${ROOT}" ]; then
  echo "no such Terraform root: ${ROOT}" >&2
  exit 2
fi

override="${ROOT}/zz_mock_override.tf"

# The override must never survive this script. A leftover copy would point a
# real `terraform apply` at a mock endpoint, and the failure mode -- resources
# reported as created that do not exist -- is worse than any error.
cleanup() { rm -f "${override}"; }
trap cleanup EXIT

sed "s#http://localhost:4566#${ENDPOINT}#g" \
  infra/terraform/ci/mock_override.tf >"${override}"

export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-mock}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-mock}"
export AWS_REGION="${AWS_REGION:-eu-central-1}"

echo "==> terraform init (${ROOT})"
terraform -chdir="${ROOT}" init -backend=false -input=false -no-color >/dev/null

echo "==> terraform plan against ${ENDPOINT}"
terraform -chdir="${ROOT}" plan \
  -refresh=false -input=false -lock=false -no-color \
  -out=tfplan.mock >/dev/null

terraform -chdir="${ROOT}" show -json tfplan.mock >"${OUT}"
rm -f "${ROOT}/tfplan.mock"

echo "==> wrote ${OUT} ($(wc -c <"${OUT}" | tr -d ' ') bytes)"
