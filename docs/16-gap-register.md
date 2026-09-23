# 16 — Sandbox vs. production: the gap register

This is the project's headline deliverable.

The AWS target is a **KodeKloud playground**: 180-minute sessions, `t3.medium` ceiling,
at most three nodes per node group and five EC2 instances in total, IAM wiped between
sessions, and no documented support for ElastiCache, CloudFront, Route53, ACM, WAF,
Aurora, MSK or Bedrock. A production-grade platform cannot run there.

Rather than pretend otherwise, every compromise is recorded here alongside the production
answer and — the part that matters — **the artefact in this repository that keeps the
production path honest.** The rationale for the three-tier model is in
[ADR-0009](adr/0009-three-tier-environment-model.md).

Tiers: **L** local (compose + kind), **S** sandbox (KodeKloud EKS), **P** production
(written, validated in CI, never applied).

---

## The register

| # | Area | Sandbox (Tier S) | Production (Tier P) | How the repo proves the prod path |
|---|---|---|---|---|
| 1 | **CI → AWS auth** | laptop-driven with session credentials; IAM is wiped each session | GitHub OIDC provider + scoped plan/apply roles, `sub` pinned to repo and environment, no static keys | `iam` module written and Checkov-scanned; `terraform test` asserts the trust-policy conditions |
| 2 | **Terraform state** | local state, discarded with the session | S3 + SSE-KMS + versioning + native S3 locking, one key per environment | `bootstrap/` module written and validated in CI |
| 3 | **Networking** | default VPC, public subnets, no NAT gateway | custom VPC `10.0.0.0/16`, 3 AZs, private node subnets, NAT, VPC endpoints for S3/ECR/Secrets Manager | `network` module; `terraform test` asserts nodes land in private subnets |
| 4 | **Node capacity** | 3 × `t3.medium` on-demand; hard caps of 3 nodes per group and 5 EC2 total | Karpenter, spot-first, mixed instance types, right-sized from measured load | Karpenter `NodePool` committed; sizing rationale derived from the Phase 11 k6 run |
| 5 | **Autoscaling** | **HPA pod scale-out is demonstrated on real EKS**; node scale-out is impossible at the 3-node cap | HPA for pods + Karpenter for nodes | HPA proven under k6 on EKS; Karpenter configuration lives in the prod profile |
| 6 | **Database** | RDS `db.t3.micro`, single-AZ, gp2, free tier | Aurora PostgreSQL Serverless v2 (0.5–2 ACU), multi-AZ, encrypted, PITR, automated backups | `database` module flags; `terraform test` asserts multi-AZ, encryption and backup retention |
| 7 | **Cache** | Redis in-cluster on `emptyDir`; data lost on pod restart | ElastiCache `t4g.micro`, encrypted in transit and at rest | `cache` module written; a chart flag switches the endpoint, application code is unchanged |
| 8 | **Messaging** | SNS/SQS availability unproven → PostgreSQL outbox relay | SNS → SQS with DLQs, redrive policy, IRSA-scoped access | one `EventPublisher` interface, two adapters, **both writing to the outbox first**; identical integration tests |
| 9 | **Pod → AWS identity** | shared node instance role | IRSA: one role and one service account per service, least privilege | `iam` module; `terraform test` asserts distinct roles and rejects wildcard actions |
| 10 | **Secrets** | Kubernetes `Secret` seeded from a gitignored `.env` | Secrets Manager + External Secrets Operator with rotation | the chart renders an `ExternalSecret` *or* a plain `Secret` behind one values flag |
| 11 | **DNS & TLS** | raw ALB hostname or `nip.io`, self-signed certificate | Route53 zone + ACM certificate + ExternalDNS + cert-manager, HSTS | `dns` module written; **requires a domain we own — currently unresolved** |
| 12 | **CDN** | S3 accessed directly | CloudFront + OAC, cache policy, signed URLs for private media | `storage` module flag |
| 13 | **Edge protection** | none | AWS WAF managed rule groups plus a rate-based rule in front of the ALB | written in the `network` module |
| 14 | **GitOps** | **ArgoCD app-of-apps runs on real EKS** — dev auto-sync, prod promotion PR, Argo Rollouts canary | the same, plus GitHub Environment approval gates and SNS/Slack notifications | demonstrated on both kind and EKS |
| 15 | **Observability** | Prometheus + Grafana on `emptyDir` with ~2 h retention, OTel → Tempo; **no Loki** | 15-day metrics on gp3, 7-day logs via Fluent Bit → CloudWatch, Tempo backed by S3, SLO burn-rate alerts | traces and dashboards proven on EKS; Loki and the log pipeline proven on kind |
| 16 | **Environments** | `dev` and `prod` namespaces on a single cluster | separate clusters, ideally separate accounts under Control Tower | the promotion flow is demonstrated on EKS; the prod roots are written and tested |
| 17 | **HA / DR** | none — single AZ, entirely ephemeral | multi-AZ, enforced PDBs, RDS PITR, documented RTO/RPO, cross-region backup | ADR records the accepted risk; `terraform test` asserts multi-AZ |
| 18 | **Cost governance** | free and time-boxed | tagging convention, budgets, anomaly alerts | **Infracost posts the true prod cost on every pull request** |
| 19 | **Image supply chain** | identical to production | cosign keyless signing, SBOM, Trivy gate, ECR scan-on-push, immutable tags | **no gap — identical in all three tiers** |
| 20 | **AIOps** | Bedrock unavailable | Bedrock agent with a read-only IRSA role | provider-pluggable; the CI risk commenter needs no AWS at all |
| 21 | **Session lifetime** | **180 minutes**, everything destroyed afterwards | permanent | forces every operation to be a scripted, idempotent, time-budgeted target — kept as a virtue, not a workaround |

---

## What the probe will change

Rows 8, 9, 10, 11 and 20 rest on **presumed** unavailability: the KodeKloud
documentation lists what is explicitly permitted and is silent on the rest. Silence is
not the same as prohibition.

The **Phase 6 capability probe** tests each presumption directly and rewrites the
affected rows with measured facts:

| Row | Question the probe answers |
|-----|---------------------------|
| 8 | Can we create an SNS topic and an SQS queue and publish between them? |
| 9 | Can we associate an OIDC provider with the EKS cluster and assume a role from a pod? |
| 10 | Does Secrets Manager accept a non-`SecretsManagerRDSMySQLRot-*` secret? |
| 11 | Can an AWS Load Balancer Controller provision an ALB? Is ACM reachable? |
| 20 | Is any Bedrock model invocable? |

If IRSA works, rows 9 and 10 move substantially towards production fidelity and the ESO
path becomes demonstrable in the sandbox. That single finding is worth the probe.

---

## Rows with no gap

Worth stating explicitly, because a register that only lists failures is misleading.

The following run **identically in all three tiers**, and the sandbox demonstration is
therefore a genuine production demonstration:

- the full CI pipeline — lint, test, coverage, CodeQL, gitleaks, Trivy, cosign, SBOM;
- the container image itself, built once and promoted unchanged;
- Flyway expand–contract migrations as a Helm `pre-upgrade` hook;
- the ArgoCD app-of-apps structure and the promotion-PR flow;
- the Argo Rollouts canary definition and its automatic abort on an SLO breach;
- every Kubernetes manifest — probes, resource requests and limits, PDBs,
  `securityContext`, NetworkPolicies;
- the application code. No tier check appears anywhere in the domain layer.

---

## Maintenance

This register is only worth having if it stays true.

- Any change that makes a tier diverge **must** add or amend a row in the same pull
  request.
- Any row whose "how the repo proves it" column names an artefact that does not exist is
  a bug, not a plan.
- Phase 6 rewrites the presumed rows; Phase 14 reviews the whole table before the final
  demo.
