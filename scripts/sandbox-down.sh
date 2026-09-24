#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Phase 14 — destroy the sandbox, then prove independently that it is gone.
#
#   scripts/sandbox-down.sh [--dry-run] [--force]
#
# WHY THE VERIFICATION SWEEP IS THE POINT
#
# `terraform destroy` reporting success means one thing only: every resource
# *in the state file* was deleted. It is silent about everything Terraform did
# not create, and this project creates a great deal outside Terraform:
#
#   - the AWS Load Balancer Controller creates a real ALB, real target groups
#     and real security groups in response to an Ingress object. None of them
#     are in Terraform state. Destroying the VPC with an orphaned ALB in it
#     does not fail cleanly -- it hangs on the dependency and then times out.
#   - ArgoCD-managed PersistentVolumeClaims become real EBS volumes.
#   - ECR repositories that received images are non-empty, and a destroy of a
#     non-empty repository fails unless force_delete is set.
#   - CloudWatch log groups created implicitly by EKS outlive the cluster.
#
# So the project's done criterion -- "`make sandbox-down` leaves nothing" -- is
# a claim that Terraform is structurally unable to substantiate. This script
# therefore asks AWS directly, by tag and by name prefix, and exits non-zero if
# anything is still standing. Anything else is an assertion, and this repo is
# specifically about not accepting assertions.
#
# ORDER MATTERS. Kubernetes-created AWS resources are deleted first, by
# deleting the Kubernetes objects that own them and waiting for the controller
# to finish. Going straight to `terraform destroy` is the single most common
# way to strand a VPC.
# ---------------------------------------------------------------------------
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
# shellcheck source=scripts/sandbox-lib.sh
. scripts/sandbox-lib.sh

DRY_RUN=0
FORCE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --force)   FORCE=1 ;;
    -h|--help) sed -n '3,33p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
  shift
done

preflight

REGION="$(state_get region)"; REGION="${REGION:-${AWS_REGION:-us-east-1}}"
export AWS_REGION="${REGION}"

say ""
say "sandbox-down — region ${REGION}, session elapsed $(fmt_duration "$(session_elapsed_s)")"
[ "${DRY_RUN}" = "1" ] && dim "DRY RUN — nothing will be deleted"

# --- 1. Kubernetes-owned AWS resources, before the VPC goes ------------------
step "releasing Kubernetes-created AWS resources"
if [ "${DRY_RUN}" = "1" ]; then
  dim "  [dry-run] kubectl delete ingress/svc type=LoadBalancer/pvc --all-namespaces"
elif kubectl cluster-info >/dev/null 2>&1; then
  # Deleting the Ingress is what makes the controller delete the ALB. Deleting
  # the namespace would also do it, but namespace deletion does not wait for
  # finalizers in any useful order and routinely wedges on a stuck one.
  kubectl delete ingress --all --all-namespaces --ignore-not-found --timeout=120s 2>/dev/null
  kubectl delete svc --all-namespaces --field-selector spec.type=LoadBalancer \
    --ignore-not-found --timeout=120s 2>/dev/null
  kubectl delete pvc --all --all-namespaces --ignore-not-found --timeout=120s 2>/dev/null
  # The controller is asynchronous. Without this pause the ALB deletion is
  # still in flight when terraform starts removing subnets, and the destroy
  # fails on a DependencyViolation that reads like a Terraform bug.
  dim "  waiting 45s for the load balancer controller to finish"
  sleep 45
else
  dim "  cluster already unreachable — nothing to release"
fi

# --- 2. terraform destroy ---------------------------------------------------
step "terraform destroy"
if [ "${DRY_RUN}" = "1" ]; then
  dim "  [dry-run] terraform -chdir=${SANDBOX_ROOT} destroy -auto-approve"
else
  terraform -chdir="${SANDBOX_ROOT}" destroy -input=false -auto-approve -parallelism=20
  destroy_rc=$?
  if [ "${destroy_rc}" -ne 0 ]; then
    warn "terraform destroy exited ${destroy_rc} — continuing to the sweep anyway,"
    warn "because the sweep is what tells you what is actually left."
  fi
fi

# --- 3. the independent sweep -----------------------------------------------
step "verification sweep — asking AWS directly, not Terraform"

LEFT=0
SWEPT=0

# survivors <label> <command...>
# Prints and counts anything the command returns. The command must print one
# identifier per line and nothing on a clean result. A *failed* query is
# reported as a survivor too: "I could not check" and "there is nothing there"
# are different answers, and collapsing them is how a teardown reports success
# over a running cluster.
survivors() {
  local label="$1"; shift
  SWEPT=$(( SWEPT + 1 ))
  local out rc
  out="$("$@" 2>&1)"; rc=$?
  if [ "${rc}" -ne 0 ]; then
    printf '  %s?%s  %-22s could not check: %s\n' "${c_yel}" "${c_off}" "${label}" \
      "$(printf '%s' "${out}" | head -1)"
    LEFT=$(( LEFT + 1 ))
    return
  fi
  out="$(printf '%s' "${out}" | tr '\t' '\n' | grep -v '^[[:space:]]*$' | grep -v '^None$')"
  if [ -n "${out}" ]; then
    printf '  %s✗%s  %-22s %s\n' "${c_red}" "${c_off}" "${label}" "$(printf '%s' "${out}" | tr '\n' ' ')"
    LEFT=$(( LEFT + $(printf '%s\n' "${out}" | wc -l | tr -d ' ') ))
  else
    printf '  %s✓%s  %-22s clear\n' "${c_grn}" "${c_off}" "${label}"
  fi
}

if [ "${DRY_RUN}" = "1" ]; then
  dim "  [dry-run] would sweep eks, ec2, elbv2, dynamodb, rds, s3, ecr, ebs, sg, logs"
else
  survivors "eks clusters"   aws eks list-clusters --query 'clusters' --output text
  survivors "ec2 instances"  aws ec2 describe-instances \
    --filters "Name=tag:${SANDBOX_TAG_KEY},Values=${SANDBOX_TAG_VALUE}" \
              "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query 'Reservations[].Instances[].InstanceId' --output text
  survivors "load balancers" aws elbv2 describe-load-balancers \
    --query 'LoadBalancers[].LoadBalancerName' --output text
  survivors "dynamodb tables" aws dynamodb list-tables \
    --query "TableNames[?starts_with(@, '${SANDBOX_TAG_VALUE}')]" --output text
  survivors "rds instances"  aws rds describe-db-instances \
    --query "DBInstances[?starts_with(DBInstanceIdentifier, '${SANDBOX_TAG_VALUE}')].DBInstanceIdentifier" \
    --output text
  survivors "s3 buckets"     aws s3api list-buckets \
    --query "Buckets[?starts_with(Name, '${SANDBOX_TAG_VALUE}')].Name" --output text
  survivors "ecr repos"      aws ecr describe-repositories \
    --query "repositories[?starts_with(repositoryName, '${SANDBOX_TAG_VALUE}')].repositoryName" \
    --output text
  # Orphaned EBS volumes are the classic one: the PVC is gone from a cluster
  # that no longer exists, so nothing will ever delete them, and nothing will
  # ever tell you.
  survivors "ebs volumes"    aws ec2 describe-volumes \
    --filters "Name=status,Values=available,in-use" \
    --query "Volumes[?Tags[?Key=='kubernetes.io/created-for/pvc/name']].VolumeId" --output text
  survivors "security groups" aws ec2 describe-security-groups \
    --query "SecurityGroups[?starts_with(GroupName, 'k8s-') || starts_with(GroupName, '${SANDBOX_TAG_VALUE}')].GroupId" \
    --output text
  survivors "eks log groups" aws logs describe-log-groups \
    --log-group-name-prefix "/aws/eks/${SANDBOX_TAG_VALUE}" \
    --query 'logGroups[].logGroupName' --output text
fi

# ---------------------------------------------------------------------------
say ""
if [ "${DRY_RUN}" = "1" ]; then
  say "dry run complete — no sweep performed"
  exit 0
fi

if [ "${LEFT}" -eq 0 ]; then
  printf '%ssandbox-down: %d checks, nothing left standing%s\n' "${c_grn}" "${SWEPT}" "${c_off}"
  rm -f "${SANDBOX_STATE}"
  exit 0
fi

printf '%ssandbox-down: %d resource(s) survived across %d checks%s\n' \
  "${c_red}" "${LEFT}" "${SWEPT}" "${c_off}"
say ""
say "The session state at ${SANDBOX_STATE} has deliberately been KEPT, so you still"
say "have the region and prefix needed to finish by hand. Re-run this script once"
say "the controller has caught up — the sweep is idempotent."
[ "${FORCE}" = "1" ] && { warn "--force: removing session state despite survivors"; rm -f "${SANDBOX_STATE}"; }
exit 1
