# 16 — Sandbox vs. production: the gap register

This is the project's headline deliverable.

The AWS target is a **KodeKloud playground**: 180-minute sessions, `t3.medium` ceiling,
at most three nodes per node group and five EC2 instances in total, IAM wiped between
sessions, and no documented support for ElastiCache, CloudFront, Route53, ACM, WAF,
Aurora, MSK or Bedrock. A production-grade platform cannot run there.

**DynamoDB, S3, ECR and Lambda are the exceptions** — explicitly permitted, and therefore
the parts of the design that can be demonstrated rather than merely asserted. That
availability is what drove the operational data layer onto DynamoDB
([ADR-0011](adr/0011-dynamodb-operational-datastore.md)): in a project whose deliverable
is this register, a row that reads "no gap" is worth more than a row that reads "the
Terraform is written and validated".

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
| 6 | **Operational datastore** | **DynamoDB**, on-demand billing, AWS-owned encryption key, no PITR, no deletion protection | DynamoDB, provisioned capacity with application auto-scaling, customer-managed KMS key, PITR, deletion protection, contributor insights | **near-zero gap — the same service, the same API, the same data model in both tiers.** The `data` module differs only by variable; `terraform test` asserts PITR, CMK and deletion protection on the prod profile |
| 6b | **Search index** | RDS `db.t3.micro` PostgreSQL, single-AZ, gp2, free tier | Aurora PostgreSQL Serverless v2 (0.5–2 ACU), multi-AZ, encrypted, PITR | `database` module flags; `terraform test` asserts multi-AZ, encryption and backup retention. **The gap matters far less than it used to**: since [ADR-0011](adr/0011-dynamodb-operational-datastore.md) this database holds only derived data and can be rebuilt by scanning DynamoDB |
| 7 | **Cache** | **two** Redis deployments in-cluster on `emptyDir` (`redis-celeb` 256 Mi `noeviction`, `redis-main` 512 Mi `allkeys-lru`); data lost on pod restart | two ElastiCache clusters with the same split, encrypted in transit and at rest | `cache` module written and parameterised per instance; a chart flag switches each endpoint, application code is unchanged. **Losing the sandbox caches now costs only latency and DynamoDB spend**, because `timelines` is durable — see [ADR-0013](adr/0013-split-celebrity-normal-caches.md) |
| 8 | **Event transport** | **DynamoDB Streams** on the `tweets` table, consumed by a polling worker with checkpoints in DynamoDB | identical | **no gap.** The stream is a property of the table, so it exists wherever the table does — including LocalStack in Tier L. What *is* a gap: production would idiomatically use a Lambda trigger or EventBridge Pipes, and we deliberately do not, to preserve "one image, promoted unchanged". Recorded in [ADR-0012](adr/0012-dynamodb-streams-event-transport.md) |
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
| 19 | **Image supply chain** | identical to production | cosign keyless signing, SBOM, Trivy gate, ECR scan-on-push, immutable tags | **no gap — identical in all three tiers, and now verified rather than asserted**: `make image-verify` starts the real container and checks context refresh, a live TLS handshake to AWS, CDS mapping, absence of any shell or package manager (every filesystem entry scanned), and the `nonroot` UID |
| 20 | **AIOps** | Bedrock unavailable | Bedrock agent with a read-only IRSA role | provider-pluggable; the CI risk commenter needs no AWS at all |
| 21 | **Session lifetime** | **180 minutes**, everything destroyed afterwards | permanent | forces every operation to be a scripted, idempotent, time-budgeted target — kept as a virtue, not a workaround |

---

## What the probe will change

Rows 9, 10, 11 and 20 rest on **presumed** unavailability: the KodeKloud
documentation lists what is explicitly permitted and is silent on the rest. Silence is
not the same as prohibition.

The **Phase 6 capability probe** (`scripts/probe.sh`, `make probe`) tests each presumption
directly and rewrites the affected rows with measured facts. It is a script rather than a
checklist because the session is 180 minutes and the probe is not the demo — it is what has
to finish before the demo is worth designing. It writes `docs/06-probe-report.md`:
machine-generated, timestamped and diffable against the next session.

Two details do most of the work. Every probe is **non-fatal and self-cleaning** — a denial
is the result, not an error, and in an account capped at five EC2 instances a leaked
resource is a quota failure in the run that matters. And every denial is **classified by
kind**: `denied by policy` is a service control policy and cannot be worked around from
inside the account, while `IAM grant missing` is a one-line permissions edit. A report that
flattens both to "failed" is worth nothing.

The probe itself is tested by `make probe-selftest`, against a stubbed AWS CLI, because a
bug found during the session costs the session. That is not hypothetical: the first version
reported a confident, blank-reasoned `NO` for every capability, and the stub is what caught
it.

| Row | Question the probe answers |
|-----|---------------------------|
| 6 | Does the playground permit PITR and a customer-managed KMS key on a DynamoDB table? |
| 9 | Can we associate an OIDC provider with the EKS cluster and assume a role from a pod? |
| 10 | Does Secrets Manager accept a non-`SecretsManagerRDSMySQLRot-*` secret? |
| 11 | Can an AWS Load Balancer Controller provision an ALB? Is ACM reachable? |
| 20 | Is any Bedrock model invocable? |

If IRSA works, rows 9 and 10 move substantially towards production fidelity and the ESO
path becomes demonstrable in the sandbox. That single finding is worth the probe.

**Row 8 no longer needs probing at all.** It previously asked whether SNS and SQS could
be created. Adopting DynamoDB Streams
([ADR-0012](adr/0012-dynamodb-streams-event-transport.md)) removed the question: the
event transport is a property of a table the design already creates. One unknown fewer
inside a 180-minute session.

---

## Rows with no gap

Worth stating explicitly, because a register that only lists failures is misleading.

The following run **identically in all three tiers**, and the sandbox demonstration is
therefore a genuine production demonstration:

- **the operational data layer** — the same DynamoDB tables, keys, indexes, transactions,
  atomic counters and TTLs, differing only in capacity mode and encryption key;
- **the event transport** — DynamoDB Streams, the same consumer code, the same
  checkpointing, the same dead-letter handling;
- the full CI pipeline — lint, test, coverage, CodeQL, gitleaks, Trivy, cosign, SBOM;
- the container image itself, built once and promoted unchanged;
- Flyway expand–contract migrations as a Helm `pre-upgrade` hook — now over the `search`
  schema only, which is a smaller demonstration than it was
  ([ADR-0008](adr/0008-expand-contract-migrations.md));
- the ArgoCD app-of-apps structure and the promotion-PR flow;
- the Argo Rollouts canary definition and its automatic abort on an SLO breach;
- every Kubernetes manifest — probes, resource requests and limits, PDBs,
  `securityContext`, NetworkPolicies;
- the application code. No tier check appears anywhere in the domain layer.

The first two entries are new, and they are the largest single fidelity improvement the
project has made. They were bought at a price, stated in
[ADR-0011](adr/0011-dynamodb-operational-datastore.md): roughly 4× the data-layer cost of
the design they replace, and access patterns that must now be right the first time.

---

## Gaps inside the product

The register above is about the *platform*. These are gaps in the *application* — places
where the running system is knowingly less than the design describes. They are listed here
rather than in an issue tracker because the same rule applies: a gap that is not written
down is a gap that gets demonstrated by accident.

| # | Gap | What actually happens | Why it was accepted | What closing it costs |
|---|---|---|---|---|
| P1 | **No backfill when you follow someone** | Fan-out is write-time: `FanoutService` resolves the follower set at post time. Following a non-celebrity author therefore delivers none of their existing posts to your home timeline — it starts from their *next* one. | The read-time merge path already exists, but only for celebrities. Extending it to "authors followed in the last N hours" is a second merge with its own cache key, and the e2e suite can only observe the defect if the specs are written in the right order (see the comment above the follow spec in `web/e2e/product.spec.ts`). | A bounded backfill job on the follow event, or widening the read-time merge. The first is a new consumer; the second changes the hot read path. |
| P2 | **A deleted tweet stays in the search index** | `SearchIndex.remove` exists, is correct and is covered by two integration tests. Nothing calls it. `TweetService.delete` removes the DynamoDB item and returns. | Search is a derived store fed by a stream consumer; deletion is the one mutation the current consumer does not carry. The index is rebuildable by scanning DynamoDB, so this is inconsistency, not data loss. | Emit a delete event and handle it in the indexer. Cheap — the method is already written and tested. |
| P3 | **Like rows outlive the tweet they liked** | Deleting a tweet leaves its `LIKE#` items behind. They are unreachable through any read path, so nothing renders wrong, but they accumulate. | A transactional multi-item delete across an unbounded set is not a single DynamoDB transaction, and the alternative — a scan on the delete path — is worse than the leak. | A TTL on like rows, or a reconciliation sweep. TTL is the DynamoDB-idiomatic answer and costs one attribute. |

Two candidates were investigated and are **not** gaps. Stream-reading logic is already
shared: `StreamReader`, `StreamArns`, `StreamRecords` and `StreamCheckpoints` live in
`services/platform-aws` and both consumers use them. And the like counter is not
eventually-consistent-by-accident — the mismatch window is handled explicitly in
`TweetService`, with the reasoning in the comment there.

---

## Silent-failure classes found while building

Every gate in this repository was green while each of the following was broken. That is
the useful part: not the individual bug, but the *class* — the reason the existing controls
could not see it, and the control that now can.

**1. Boot 4 split every auto-configuration into its own module.** A `spring.flyway.*` block
with no `spring-boot-flyway` on the classpath is not an error; it is inert. Migrations
simply never ran, and the service started healthy. Symmetrically, a dependency nobody uses
is not free: a leftover `spring-boot-data-redis` auto-configured a health indicator against
`localhost:6379` and held a service permanently unready. *Control:* treat any
`spring.<tech>.*` block as unproven until the matching `spring-boot-<tech>` jar is
confirmed, and read the startup log for the auto-configuration report rather than trusting
that configuration implies behaviour.

**2. Spring Security evaluates the ERROR dispatch.** An unhandled 500 inside a `permitAll`
endpoint is re-dispatched, re-filtered, and delivered to the client as **401**. Every
diagnosis that followed was therefore wrong by construction — the visible symptom pointed at
authentication and the cause was a null pointer. *Control:* all four `SecurityConfig`s now
permit the ERROR dispatch explicitly, and the e2e suite asserts on rendered pages rather
than status codes, so a masked 500 shows up as a missing element.

**3. Relaxed binding silently discards map keys containing `/`.** The gateway's route map
bound to an empty map, so every route 404'd, with no warning anywhere. Keys must be
bracketed: `"[/v1/tweets]"`. *Control:* the route map is asserted non-empty at startup.

**4. `secure: NODE_ENV === 'production'` is a trap, not a best practice.** Every image runs
a production *build*, but "built for production" is not "served over TLS". The browser
discarded the session cookie on a plaintext origin — and Next reflects a just-written cookie
within the same request, so the post-login redirect still rendered signed-in and only the
*next* page load was signed out. Nothing was logged, by anyone. *Control:* `Secure` now
defaults on and requires an explicit `SESSION_COOKIE_SECURE=false` to disable, so the
insecure case is a deliberate, greppable statement in `compose.yaml`.

**5. A green unit suite says nothing about seams.** Three Phase 4 defects — the cookie
above, a server action revalidating one route of three, and `likedByMe` never populated on
any listing — lived entirely between components that were each individually correct.
Contract tests, integration tests, the image smoke test and the size gate were all green
throughout. *Control:* Playwright against the built images, on pull requests, not just on
main ([docs/02-workflow.md §3](02-workflow.md)).

**6. `jdeps` cannot see reflection, and the AWS SDK is full of it.** A jlink module set
that satisfies the compiler can still produce a container that dies on its first call —
classically on `jdk.crypto.ec`, which fails *only* against ECDHE peers, which is to say only
against real AWS. *Control:* `scripts/image-verify.sh` runs the actual container and forces
a live TLS handshake to an AWS endpoint, and CI runs it on every built image. Testing the
Gradle classpath would prove nothing, because that classpath is a full JDK.

**7. LocalStack init hooks run only on a fresh volume.** A changed bootstrap script appears
to work, because the tables from the previous run are still there. *Control:* `make tables`
re-runs the bootstrap idempotently against a live container, so the script is exercised on
every invocation rather than once per volume lifetime.

---

## Maintenance

This register is only worth having if it stays true.

- Any change that makes a tier diverge **must** add or amend a row in the same pull
  request.
- Any row whose "how the repo proves it" column names an artefact that does not exist is
  a bug, not a plan.
- A product gap (`P*`) is closed by deleting its row, not by editing it to describe a
  workaround.
- Phase 6 rewrites the presumed rows; Phase 14 reviews the whole table before the final
  demo.
