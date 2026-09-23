# ADR-0009 — Three-tier environment model

- **Status:** Accepted
- **Date:** 2026-09-23

## Context

The AWS target is a KodeKloud playground: 180-minute sessions, everything destroyed
afterwards, `t3.medium` as the largest instance, at most three nodes per node group and
five EC2 instances in total, IAM wiped between sessions, and no documented support for
ElastiCache, CloudFront, Route53, WAF, Aurora or Bedrock.

A production-grade design cannot run there. But a design that is merely *claimed* and
never exercised is worthless — "this is what I would do in production" is unfalsifiable
and reviewers know it.

The question is how to be simultaneously honest about the constraint and rigorous about
the production design.

## Decision

Three tiers, with one codebase serving all of them.

| Tier | Where | Lifetime | Role |
|------|-------|----------|------|
| **L — Local** | docker compose + kind | permanent | dev loop, all CI, full observability including Loki, anything needing persistence |
| **S — Sandbox** | KodeKloud playground | 180 min | real EKS, RDS, ECR, ArgoCD, canary, k6 — then destroyed |
| **P — Production** | code only, never applied | n/a | the design that would actually ship |

Tier P is held to a standard that makes it falsifiable:

- `terraform validate`, `tflint` and **Checkov** run on it in CI;
- **`terraform test` with mocked providers** asserts its invariants — nodes in private
  subnets, RDS multi-AZ and encrypted, S3 public access blocked, no `0.0.0.0/0` ingress
  except the ALB, a distinct least-privilege IRSA role per service account, no wildcard
  IAM actions;
- **Infracost** posts its true monthly cost on every pull request.

Tier differences are expressed as **variables on shared modules and values files on
shared charts**, never as forked code. `envs/sandbox` and `envs/prod` call the same
modules; `values-sandbox.yaml` and `values-prod.yaml` parameterise the same chart.

Every divergence is recorded in [`16-gap-register.md`](../16-gap-register.md) together
with the artefact that proves the production path.

## Alternatives considered

**Target only the sandbox.** Honest but unambitious: the result would show a single-AZ,
public-subnet, no-IRSA, no-TLS deployment with nothing to say about production.

**Target only production, never run anything.** Rejected outright. Unexecuted Terraform
is a wish list. The single most valuable thing the sandbox provides is proof that
`terraform apply` actually converges on real AWS.

**Pay for a real AWS account.** Removes every constraint, at roughly $245/month. Rejected
on cost — and, in retrospect, the constraint produced better engineering than its absence
would have.

**Maintain separate sandbox and production Terraform codebases.** Rejected as the worst
option available: two codebases drift, the production one rots unexercised, and the gap
register becomes impossible to generate honestly.

## Consequences

**Positive**

- The production design is continuously validated rather than merely asserted.
- The sandbox proves the provisioning path converges on real AWS.
- The gap register becomes a genuine artefact — a reviewer can read exactly what was
  compromised and what the remedy is, which is more informative than a repository that
  pretends there were no compromises.
- Ephemerality forces every operation to be a scripted idempotent target rather than a
  click-path. This is a discipline most projects never acquire.

**Negative**

- Three sets of values files and two Terraform roots to keep consistent. Drift between
  them is the primary maintenance risk; CI validating both roots is the main defence.
- Tier P will contain bugs that only a real `apply` would reveal — mocked provider tests
  catch structure and policy, not AWS API behaviour. This is stated plainly rather than
  glossed over.
- Contributors must understand which tier they are targeting before making a change.
- Some features are only ever demonstrable in one tier: Loki and 15-day retention on L,
  IRSA and ALB on S if the probe permits, Karpenter and multi-AZ nowhere.

**Neutral**

- The Phase 6 capability probe exists precisely because the sandbox's true limits are
  documented incompletely; its findings move rows between tiers.
