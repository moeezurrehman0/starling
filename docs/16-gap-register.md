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
| 20 | **AIOps** | Bedrock unavailable; `AIOPS_PROVIDER=none` renders the comment from the template and says so in its footer | `AIOPS_PROVIDER=bedrock` against a read-only IRSA role — a config swap, no code change | `providers.py` is pluggable and lazily imports `boto3`; the CI risk commenter plans the prod root against LocalStack and so needs **no AWS in any tier**. Severity is decided by `risk.py`, never by a model — see `docs/07-aiops.md` |
| 21 | **Session lifetime** | **180 minutes**, everything destroyed afterwards | permanent | forces every operation to be a scripted, idempotent, time-budgeted target — kept as a virtue, not a workaround. `scripts/sandbox-up.sh` is resumable via `--from <stage>` and reports each stage against the budget in [`docs/08-session-runbook.md`](08-session-runbook.md); `scripts/sandbox-down.sh` verifies the teardown by querying AWS rather than trusting Terraform (S43); `scripts/sandbox-selftest.sh` tests all three offline |
| 22 | **Metrics backend** | self-hosted Prometheus on `emptyDir`, 2h retention | Amazon Managed Prometheus, 150-day retention, cross-account | `prometheus-rules.yaml` is the promoted artefact: recording rules and alert expressions transfer to AMP unchanged. The scrape config and storage do not, and are not pretended to. |
| 23 | **Dashboards** | Grafana with anonymous admin and no auth | Amazon Managed Grafana behind IAM Identity Center, SAML groups | datasource and derived-field wiring is identical; only the auth story differs, and shipping one for a disposable cluster would be effort spent on the part that is thrown away |
| 24 | **Trace backend** | self-hosted Tempo, `emptyDir`, no sampling | AWS X-Ray or AMP-managed Tempo, tail sampling at the collector | the OTel collector sits between the apps and the backend precisely so the app configuration does not change when the backend or the sampling policy does |
| 25 | **Alert routing** | alerts evaluate; nothing routes them | Alertmanager to PagerDuty, with severity-based escalation | every alert carries `severity: page` or `severity: ticket` and a `runbook:` annotation pointing at a file that exists — the routing layer is the only missing piece |
| 26 | **Infrastructure metrics** | none: no kube-state-metrics, no node-exporter, no cAdvisor scrape | full node, pod and control-plane metrics | recorded rather than hidden: several runbook steps reference container CPU throttling and are explicitly marked unexecutable in Tier L |
| 27 | **Signal retention** | 2 hours; a rescheduled pod loses all history | 15 months of metrics, 30 days of logs and traces | `ErrorBudgetBurningSlow` has a 6h window and therefore **cannot fire locally**. The window is not shortened to make it demonstrable — the rules file is the Tier P artefact, and weakening it locally weakens it in production. |

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

**These are numbered `S1`–`S45`, in their own namespace.** They are not rows of the register
above — that table is numbered `1`–`27` and answers "what does the sandbox force". This
section answers a different question: "what was broken while every gate said it was fine".
The two schemes overlapped for most of this project's life, both referred to as "gap row
N", and two citations in the source tree were silently pointing at the wrong entry as a
result. `scripts/gap-verify.sh` now resolves every citation in the repository against this
file and fails if one does not exist.

Every gate in this repository was green while each of the following was broken. That is
the useful part: not the individual bug, but the *class* — the reason the existing controls
could not see it, and the control that now can.

**S1. Boot 4 split every auto-configuration into its own module.** A `spring.flyway.*` block
with no `spring-boot-flyway` on the classpath is not an error; it is inert. Migrations
simply never ran, and the service started healthy. Symmetrically, a dependency nobody uses
is not free: a leftover `spring-boot-data-redis` auto-configured a health indicator against
`localhost:6379` and held a service permanently unready. *Control:* treat any
`spring.<tech>.*` block as unproven until the matching `spring-boot-<tech>` jar is
confirmed, and read the startup log for the auto-configuration report rather than trusting
that configuration implies behaviour.

**S2. Spring Security evaluates the ERROR dispatch.** An unhandled 500 inside a `permitAll`
endpoint is re-dispatched, re-filtered, and delivered to the client as **401**. Every
diagnosis that followed was therefore wrong by construction — the visible symptom pointed at
authentication and the cause was a null pointer. *Control:* all four `SecurityConfig`s now
permit the ERROR dispatch explicitly, and the e2e suite asserts on rendered pages rather
than status codes, so a masked 500 shows up as a missing element.

**S3. Relaxed binding silently discards map keys containing `/`.** The gateway's route map
bound to an empty map, so every route 404'd, with no warning anywhere. Keys must be
bracketed: `"[/v1/tweets]"`. *Control:* the route map is asserted non-empty at startup.

**S4. `secure: NODE_ENV === 'production'` is a trap, not a best practice.** Every image runs
a production *build*, but "built for production" is not "served over TLS". The browser
discarded the session cookie on a plaintext origin — and Next reflects a just-written cookie
within the same request, so the post-login redirect still rendered signed-in and only the
*next* page load was signed out. Nothing was logged, by anyone. *Control:* `Secure` now
defaults on and requires an explicit `SESSION_COOKIE_SECURE=false` to disable, so the
insecure case is a deliberate, greppable statement in `compose.yaml`.

**S5. A green unit suite says nothing about seams.** Three Phase 4 defects — the cookie
above, a server action revalidating one route of three, and `likedByMe` never populated on
any listing — lived entirely between components that were each individually correct.
Contract tests, integration tests, the image smoke test and the size gate were all green
throughout. *Control:* Playwright against the built images, on pull requests, not just on
main ([docs/02-workflow.md §3](02-workflow.md)).

**S6. `jdeps` cannot see reflection, and the AWS SDK is full of it.** A jlink module set
that satisfies the compiler can still produce a container that dies on its first call —
classically on `jdk.crypto.ec`, which fails *only* against ECDHE peers, which is to say only
against real AWS. *Control:* `scripts/image-verify.sh` runs the actual container and forces
a live TLS handshake to an AWS endpoint, and CI runs it on every built image. Testing the
Gradle classpath would prove nothing, because that classpath is a full JDK.

**S7. LocalStack init hooks run only on a fresh volume.** A changed bootstrap script appears
to work, because the tables from the previous run are still there. *Control:* `make tables`
re-runs the bootstrap idempotently against a live container, so the script is exercised on
every invocation rather than once per volume lifetime.

**S8. A guard in a `.tpl` file is not a guard.** Helm extracts only `define` blocks from
`templates/*.tpl`; loose content there is never evaluated. The `dev-infra` chart's
"never install this outside a disposable cluster" check therefore passed `helm lint`,
rendered nothing, and protected nothing — a chart of single-replica `emptyDir` databases
would have installed cleanly into any environment pointed at it. The dangerous property is
that a *broken* safety control and an *absent* one are indistinguishable from the outside,
while the broken one also stops anybody looking. *Control:* the guard moved into a `define`
invoked from every real template, and `scripts/helm-validate.sh` asserts that it **fails** —
the guard is tested, not just present.

**S9. NetworkPolicy is accepted by clusters that cannot enforce it.** kind's default CNI
implements no policy engine. `kubectl apply` succeeds, `kubectl get networkpolicy` lists the
object, `kubectl describe` shows the rules — and every packet still flows. A default-deny
posture that is in fact wide open looks exactly like one that works, and it looks that way
in precisely the places an engineer would check. *Control:* `deploy/kind/cluster.yaml` sets
`disableDefaultCNI`, so a cluster cannot be created without an explicit CNI decision, and
`scripts/kind-up.sh` installs Calico and aborts rather than degrading silently.

**S10. Rendering both an HPA and `spec.replicas` is valid, and wrong.** Nothing rejects it:
both objects pass schema validation and both are legal. Under GitOps the HPA scales up, the
sync controller reverts the field to the number in Git, and the two oscillate — presenting
as unexplained pod churn rather than as a manifest error. The same shape of problem appears
in a `PodDisruptionBudget` whose `minAvailable` equals the replica count, which is a valid
object that makes `kubectl drain` block forever. *Control:* `scripts/helm-validate.sh`
checks the rendered output for both, alongside two product-specific invariants (the Redis
tiers must resolve to different hosts; the stream consumers must be pinned to one replica).
All four were verified by deliberately breaking them and confirming the suite goes red — an
assertion nobody has seen fail is an assertion nobody should trust.

**S11. A second copy of a configuration is a second source of truth.** The Tier L Helm chart
and `compose.yaml` both describe the two Redis instances. The chart was written with one
policy and one size for both — and it worked. Every test passed, the application behaved
correctly, and the celebrity cache quietly became a duplicate of the ordinary one, which is
the entire reason the split exists. The condition that distinguishes them is memory pressure
on a hot key, which Tier L never reaches, so the divergence would have survived until
production. *Control:* `scripts/helm-validate.sh` reads both files and fails if the eviction
policy or the size differs between them, and separately fails if the two tiers are
configured identically. Writing that check immediately found a second bug in the check
itself: Helm groups rendered objects by kind, so anchoring on a tier's Service reads forward
into the *other* tier's Deployment, and the assertion compared the main cache to itself.

**S12. A diagnostic that names the wrong cause is worse than none.** `scripts/kind-deploy.sh`
opened with `kind get clusters | grep -qx "$CLUSTER" || die "cluster not found — run: make
kind-up"`. With `kind` absent from `PATH` the command fails, the `||` fires, and the script
confidently reports that a cluster which was up and healthy did not exist — sending the
reader to re-create it. The check conflated "the tool is missing" with "the tool answered
no". *Control:* verify the tools first, in a separate step with its own message, so a missing
binary can never be reported as a missing cluster. The general form: any `cmd | test || die`
attributes every possible failure of `cmd` to the negative case of `test`.

**S13. The lock file the default `.gitignore` tells you to discard is the only thing pinning
your providers.** Terraform's own recommended ignore list contains `.terraform.lock.hcl`, and
following it means `~> 5.70` resolves to whatever shipped this morning. Two engineers then
plan different infrastructure from identical code, and the difference appears as an
unexplained diff rather than as a version change. There is a second edge behind it: a lock
generated on an Apple laptop records only `darwin_arm64` hashes, so CI on `linux_amd64` fails
an `init` that worked locally with a checksum error that reads like a supply-chain
compromise. *Control:* the lock is committed for both roots, generated with
`-platform=linux_amd64 -platform=darwin_arm64`, and `scripts/tf-validate.sh` counts the `h1:`
hashes per provider and fails a single-platform lock. Verified by stripping a hash and
confirming the suite goes red.

**S14. A build context that is one level too deep fails as a missing file.**
`scripts/kind-deploy.sh` built the web image with the context set to `web/`, while
`docker/Dockerfile.web` does `COPY web/ ./` against a repo-root context — the same context
`compose.yaml` uses. The error is `"/web": not found`, which reads as a deleted directory
rather than a context off by one, and the directory is plainly there. *Control:* the fix is
one word, but the lesson is that the Dockerfile and every caller share an unwritten contract
about where the context root is, and only compose stated it. Both callers now say so in a
comment next to the path.

**S15. `terraform validate` has no opinion about whether the configuration is correct.** It
type-checks. A production root with deletion protection off, public nodes, and a bucket open
to the world validates cleanly, and so does one that restates the DynamoDB schema in HCL
instead of reading `tools/dynamodb-tables.json` — at which point LocalStack and AWS drift and
the difference surfaces as a `ValidationException` against a GSI that exists locally.
*Control:* `scripts/tf-validate.sh` layers generic tools (`tflint`, `checkov`) over
design-specific assertions: both tiers must read the one schema file, Tier P must keep PITR,
deletion protection, a private API endpoint and a purpose-built VPC, Tier S must stay
destroyable, no real account id or state file may be committed, and every IRSA trust policy
must pin both `:sub` and `:aud`. Verified by breaking three of them.

**S16. An IRSA trust policy missing `:aud` still works.** The condition block needs both
`sub` and `aud`. With only `sub`, the role is assumable by any token the cluster's issuer
signs, for any audience; with only `aud`, by every service account in the cluster. Either way
the pods keep working and nothing is logged, so the loss of per-service isolation — the whole
reason the roles are split — is invisible. *Control:* asserted in `scripts/tf-validate.sh`,
and the service names in `modules/iam/variables.tf` are cross-checked against the workload
names in `deploy/envs/prod/`, because the `sub` condition is
`system:serviceaccount:<ns>:<name>` and a rename on either side silently drops every pod back
onto the node instance role.

**S17. A NetworkPolicy that is right in production is wrong locally, and nothing renders
differently.** The `allowExternalEgress` rule is `0.0.0.0/0` minus `169.254.169.254/32` and
the three RFC1918 ranges — the exclusions being the point, since without them every pod can
reach the node's instance metadata and borrow its role, which defeats IRSA. In Tier P that
rule is exactly how a service reaches DynamoDB. In Tier L, DynamoDB *is* LocalStack, a pod on
the cluster network, so the same rule blocks it. The policy rendered identically in both
tiers, `helm lint` and `kubeconform` passed, the pod went `1/1 Running`, and the only symptom
was `ApiCallTimeoutException ... 5000 millis` five layers up, presenting at the edge as a
gateway 504 — which reads like the gateway being unable to reach the service, not like the
service being unable to reach its database. Two hours were spent on the wrong hop.
*Control:* every Tier L workload that touches DynamoDB or S3 now names `localstack` in
`networkPolicy.allowTo`, with the reason written at each site rather than once. This is the
first genuinely load-bearing tier difference the register has recorded that is *invisible in
the manifests* — it exists only in where the dependency lives.

**S18. The fan-out worker logged `WARN`, started, reported Ready, and consumed nothing.**
Stream discovery runs once at boot. When it failed — for the reason in row 17 — the worker
logged `could not discover the stream for table tweets`, continued to `fan-out consumer
started`, passed both probes and sat at `1/1 Running` with an empty subscription. Tweets were
accepted with `201`, rows landed in DynamoDB, and follower timelines stayed empty. No error
surfaced anywhere: the failure is only visible as an absence. This is the worst shape a
failure can take, because every dashboard is green and the product is silently broken.
*Control:* **fixed, both ways.** Discovery moved out of the constructor into `StreamSource`,
which resolves lazily, retries on every poll until it succeeds and caches only success; and
`StreamHealthIndicator` puts the result in the **readiness** group, so a consumer that has not
found its stream is taken out of service instead of reporting Ready. Readiness rather than
liveness deliberately: restarting the pod does not make the stream appear, and a
CrashLoopBackOff would replace a diagnosable condition with a container nobody can exec into.
The indicator performs no I/O — a health check that calls a dependency lets that dependency's
latency decide the probe result.

Verified against the live cluster by re-creating the original conditions: the `localstack`
egress rule was removed from the fan-out NetworkPolicy and the pod restarted. It came up
`0/1 Running` with `Startup probe failed: HTTP probe failed with statuscode: 503` and the
rollout stalled while the previous pod kept serving — where before it came up `1/1` and
silent. Restoring the rule returned it to `1/1` **with no restart**, which is the retry
proving itself.

Two smaller decisions are load-bearing. The indicator is registered unconditionally and told
whether the process is *supposed* to consume, rather than only existing when it is: a health
group lists members by name and Boot refuses to start when one is missing, so a conditional
bean would have forced `validate-group-membership: false` on every service. And in
`tweet-service` it reports UP whenever the indexer is off, because that image also runs as the
request-serving Deployment — an undiscoverable stream must not be able to take every
request-serving replica out of the Service and stop writes entirely.

**S19. `helm upgrade --reuse-values -f file` silently reverted the image tag.** Re-applying one
env file to change a single NetworkPolicy field dropped the `--set image.tag=sha-local` from
the original install, and the Deployment went back to the chart default `sha-0000000`. With
`pullPolicy: Never` the new pod sat in `Pending`/`ErrImageNeverPull` while the old one kept
serving, so the service stayed up and the rollout simply never finished. `--reuse-values`
reads as "keep everything I had" and does not mean that. *Control:* never hand-run `helm
upgrade` against this cluster — `scripts/kind-deploy.sh` holds the full, correct invocation
(both values files plus all three `--set` flags) and is the only supported way to apply a
change.

**S20. `terraform validate` passed a `for_each` that could never plan.** The search database
module took `for_each = toset(var.allowed_security_group_ids)` and the production root passed
`module.eks.cluster_security_group_id` — a value that does not exist until the EKS cluster is
created. Terraform requires `for_each` *keys* to be known at plan time, so the first apply of
a clean root would have stopped with "Invalid for_each argument" before creating anything.
`terraform fmt`, `terraform validate` and every `refute_grep` in `tf-validate.sh` passed it,
because nothing in those layers evaluates the expression. Worse, the bug is self-concealing:
once the security group is in state its id is known, so every apply after the first succeeds —
and in a 180-minute disposable playground the first apply is the only apply there is. *Control:*
fixed to key the map by position (`{ for idx, sg in ... : tostring(idx) => sg }`), so the keys
come from the list's shape rather than its contents; and `scripts/tf-test.sh` now runs every
module and root under `mock_provider`, which is the only layer that evaluates configuration
rather than reading it. This class — *validate green, first apply fails, later applies
succeed* — is the reason the test layer exists at all.

**S21. A cost check with no API key would have reported green.** Infracost is part of the
Phase 9 gate, but this repository has never been pushed and therefore has no secrets, so
`INFRACOST_API_KEY` is empty. The obvious wiring — `if: secrets.INFRACOST_API_KEY != ''` on
the step — produces a job that succeeds having done nothing, and a required check that is
green because it was never configured is indistinguishable, on the pull request page, from
one that is green because the cost is fine. *Control:* the `cost` job always runs and always
writes to the run summary; when the key is absent it writes "**skip, not a pass**" in place of
a breakdown. The check still passes — blocking every merge on an unobtainable secret is worse
— but nobody reading the run can mistake the reason. The same shape applies to every
third-party gate added later.

**S22. A rebuild deployed nothing, and the deploy script reported success.** Tier L pins the
image tag at `sha-local`. A code change therefore produces a new image under the *same* tag, so
the rendered Deployment is byte-identical to the running one, Helm finds nothing to change, no
pod is replaced — and `kubectl rollout status` immediately returns success for the pods that
were already there. Every line of output says the deploy worked. The cluster runs the previous
build, and the only symptom is that the change you just made is still missing, which reads as
"my fix didn't work" rather than "my fix was never deployed". This cost a full debugging cycle:
the fan-out fix was tested against a pod built before it existed. *Control:* `kind-deploy.sh`
now passes the local image id as a pod annotation, so the spec changes exactly when the image
content changes. An unconditional `rollout restart` would also have worked but would bounce all
seven workloads whenever one is edited; this restarts only what actually changed, verified by
running the script twice with no source change and confirming pod ages keep climbing.

**S23. The wiring layer had no test at all.** Until this phase the repository contained no Spring
context test — not one `@SpringBootTest`. Every `@Configuration`, every `@ConfigurationProperties`
binding, every actuator group and every bean-name reference was therefore first exercised by a
pod. That is tolerable while configuration is inert, and stops being tolerable the moment a
string in YAML has to match a bean name: `management.endpoint.health.group.readiness.include`
names `stream`, and Boot refuses to start when it matches nothing. A rename would have passed
`./gradlew build` and failed every pod in the fleet. *Control:* `FanoutWorkerApplicationTest`
now loads the real context with nothing reachable, which is both the group-resolution proof and
the honest reproduction of the state row 18 was about. Writing it exposed a second-order
problem worth recording: the AWS SDK resolves region and credentials when a client is *built*,
so any context test fails on a machine with no AWS configuration and passes on a developer's
laptop that happens to have some. Deliberately invalid values are now set for every test JVM in
`java-conventions`, so the suite behaves identically everywhere and any test that does reach AWS
fails with an auth error rather than silently using someone's real account.

**S24. Spring Boot 4 moved tracing out of the actuator, and the old property still binds.** Every
service set `management.otlp.tracing.endpoint` and exported no spans. Two independent causes,
both silent. The autoconfiguration now lives in `spring-boot-micrometer-tracing-opentelemetry`,
which was not on the classpath — having `micrometer-tracing-bridge-otel` and
`opentelemetry-exporter-otlp` gives the libraries with nothing to wire them, so there is no
`Tracer` bean, which means no exporter **and** no `traceId` in the MDC. One missing dependency
broke traces and log correlation together. The property was also renamed to
`management.opentelemetry.tracing.export.otlp.endpoint`; the old name still binds, which is
worse than if it did not. *Control:* the technique that settles this class in one command —
`unzip -p <jar> META-INF/spring-configuration-metadata.json` across every `spring-boot-*.jar` in
`BOOT-INF/lib` — tells you definitively whether a property is bindable, and the Phase 10 gate is
a non-zero `otelcol_receiver_accepted_spans` rather than a rendered config.

**S25. `RestClient.builder()` severs the trace and nothing fails.** Only the *auto-configured*
`RestClient.Builder` carries the Micrometer observation interceptor that writes the
`traceparent` header. The gateway built its own, so it opened a span for each inbound request,
called upstreams without propagating, and produced traces that stopped at the edge — seven
gateway spans and no others, for a request that touched four services. A trace that is merely
incomplete looks exactly like a trace of a system that did no downstream work. *Control:* the
gateway and timeline-service inject the builder, and the gate is a trace containing more than
one `service.name`.

**S26. Calico evaluates NetworkPolicy before DNAT, so `6443` is the wrong port.** Prometheus and
promtail both had API-server egress allowing only the node's `6443`. In-cluster clients dial
`kubernetes.default.svc:443`, and the policy is evaluated against the service address, not the
translated one. Discovery was blocked while a shell on the node could reach the API server
fine, so every manual check said the network was healthy. *Control:* both policies allow 443
and 6443, and `NoApplicationTargets` fires when discovery returns nothing at all — which
`up == 0` cannot do, because there is no `up`.

**S27. A 401 on `/actuator/prometheus` is invisible except as an absence.** `tweet-service`
permitted `/actuator/health/**` and `/actuator/info` but not `/actuator/prometheus`. The pod was
healthy, the annotation was correct, the port was open, and the only symptom was that its
metrics — and `tweet-indexer`'s, same image — did not exist. No alert could fire on them because
no series existed to evaluate. Six of nine targets were down for the same class of reason
(three on auth, one on a 404 from a pod that serves no metrics, the rest on an egress port list
that had gone stale). *Control:* `TargetDown` is now understood as the compensating control for
an enumerated egress policy, and is documented as such in its runbook.

**S28. Promtail's Kubernetes service discovery fails to zero targets with no error.** With
`role: pod` the provider starts, logs `Using pod service account via in-cluster config`, and
discovers 0/0 forever — at `debug`, against a reachable API server, with a token that returns
200 from the same network namespace. The readiness message says *"Unable to find any logs to
tail. Please verify permissions, volumes, scrape_config"*, which points at three things that
were all correct. *Control:* replaced with path-based tailing, which is also the better design
— Kubernetes SD asks a node-local agent to hold cluster-wide pod read in order to learn what
the kubelet has already written into the filesystem it is mounting anyway. The ServiceAccount
is kept but **deliberately unbound**, so reintroducing SD fails with a 403 rather than silently
regaining that privilege.

**S29. A Helm upgrade changed a ConfigMap and no pod picked it up.** The promtail DaemonSet's pod
template was byte-identical across the change, so Kubernetes had nothing to roll. `helm upgrade`
reported success and all three pods kept running the previous config. This is the same class as
row 22 — a deploy that reports green and changes nothing — in a different layer. *Control:* the
config body is a named template and the pod template carries a `checksum/config` annotation over
`include` of it. Checksumming `.Values` instead, as is common, would have missed this exact
change, because the change was in the template body.

**S30. LocalStack loses every table on restart, and only the stream consumers notice.** A
Docker Desktop restart bounced the LocalStack pod. Its state is in-memory, so all DynamoDB
tables and their streams went with it. The four HTTP services stayed `1/1 Running` and kept
serving — they create items lazily and their readiness probes do not touch DynamoDB — while
`fanout-worker` and `tweet-indexer` went into `CrashLoopBackOff`. The only honest signal was
a `StreamArns` WARN and `stream_resolved{group=...} == 0`, which had been dismissed earlier
as a metric bug. *Control:* `stream_resolved` is now understood as a data-plane liveness
signal rather than a startup detail, and `FanoutStreamUnresolved` alerts on it with
`docs/runbooks/fanout-stream-unresolved.md` describing the re-bootstrap. The deeper lesson is
that **the services that fail loudly are not the ones that lost data** — the HTTP tier
reported healthy against a store that had been emptied underneath it. In Tier P the store is
managed DynamoDB and cannot evaporate, so this specific failure is Tier L/S only; the
*asymmetry* it exposes — readiness probes that never touch the datastore — is not, and is
carried as a known weakness rather than papered over with a probe that would make every
service unready during a transient DynamoDB blip.

**S31. A stub Docker config silently disabled BuildKit, and the failure looked like a missing
file.** Docker Desktop's credential helper wedged, hanging every `docker pull` indefinitely;
pointing `DOCKER_CONFIG` at an empty directory fixed the pulls. But CLI plugins are resolved
relative to `DOCKER_CONFIG` too, so `buildx` disappeared and `docker build` fell back to the
legacy builder. The legacy builder does not honour `.dockerignore` re-inclusion (`!pattern`
after `*`), so the build failed with `file not found in build context` for a jar that plainly
existed on disk. *Control:* none in the repo — this is a host-toolchain trap, recorded because
the presenting symptom ("file does not exist" for a file that does exist) points nowhere near
the cause, and because it is a concrete instance of a general rule: **an environment override
that fixes one subsystem can silently remove another.**

---

**S32. A canary gate that matches no time series reports success.** The `AnalysisTemplate`
selected `service="gateway"`. Nothing in this cluster carries a `service` label — the scrape
relabelling produces `app`. The query was therefore syntactically valid, semantically
meaningless, and returned an empty vector on every evaluation. Argo Rollouts scores an empty
result as `Successful`, so the gate promoted a build with a measured 51.6% error rate while
reporting that it had analysed it. *Control:* both `successCondition`s now begin
`len(result) > 0 &&`, so "no data" is a failure rather than a pass, and
`validate-rollout.py` asserts that property statically. The general rule: **a monitoring
query that cannot fail is not a control**, and the only way to know which one you have is to
run it against a deliberately broken build.

**S33. Background analysis is terminated at promotion, and a terminated run is scored
successful.** The canary ran its analysis as `backgroundAnalysis`. When the last canary step
completed, the rollout promoted and terminated the still-running `AnalysisRun`; the
controller logged `Metric Assessment Result - Successful: Run Terminated`. With short steps
the analysis never gathered enough samples to object, so the gate was structurally incapable
of blocking anything — it lost a race it was never told it was in. *Control:* an inline
`- analysis:` step after the first pause. An inline step blocks the rollout until it
concludes and therefore cannot be outrun. Background analysis is retained only as a
supplement.

**S34. A NetworkPolicy made the gate blind, and blindness read as health.** The `prometheus`
policy admitted Grafana only. The Argo Rollouts controller lives in its own namespace, so
its queries timed out: `context deadline exceeded`. Combined with row 32 this produced a
gate that could neither reach its data source nor object to the absence of data.
*Control:* an explicit ingress rule for the `argo-rollouts` namespace on 9090. Related trap:
a `kubectl port-forward` is proxied by the API server and arrives from the node, so it
**bypasses** ingress policy entirely — a working port-forward proves nothing about
in-cluster reachability, and was the reason this took so long to find.

**S35. `status.abort` latches, and the recovery path is not the obvious one.** After an abort
Argo sets `status.abort: true` and refuses to roll forward. Pushing a corrected spec does
nothing; `kubectl argo rollouts retry` was not sufficient. The corrected manifest sat in the
API server while the broken ReplicaSet served 100% 5xx for roughly fifteen minutes. The
trap compounds: because analysis measures the whole Service rather than only canary pods
(there is no traffic-routing provider in this tier, so no label distinguishes them), the
aggregate error rate was dominated by the broken *stable* pods — and the healthy replacement
failed a gate that was measuring the very thing it would have fixed. *Control:* `restore()`
in the drill now patches `status.abort=false, promoteFull=true` through the status
subresource and waits for `Healthy`, and the drill asserts the rollout is `Healthy` before
it starts. *Gap:* the catch-22 itself is not fixed in this tier and cannot be — it needs a
traffic provider that labels canary traffic separately. Production values assume one.

**S36. A rate() window at t=0 of a deploy describes the previous deploy.** The first
measurement fired immediately, and its `rate(...[2m])` lookback straddled the errors from
the deploy being replaced. With `failureLimit: 0` this aborted known-good builds — the gate
was accurate about a question nobody asked. *Control:* `initialDelay >= lookback`, made
configurable per environment (dev `1m`, prod `2m`), and the drill now starts steady traffic
**before** mutating the rollout, with a 90 s warm-up.

**S37. Spring Boot publishes no histogram buckets by default, so a p95 gate is silently
empty forever.** `histogram_quantile()` over `http_server_requests_seconds_bucket` looks
entirely reasonable and had never once returned a value, because the metric does not exist
unless `management.metrics.distribution.percentiles-histogram` is enabled. Enabling it
exposed a second trap: setting only `maximum-expected-value` throws
`maximumExpectedValue must be >= minimumExpectedValue` **per request, from inside the
metrics filter** — the service starts, passes both probes, and then 500s every call, so the
misconfiguration presents as an application bug. *Control:* all five services set the
histogram flag and **both** bounds; the buckets are now confirmed present (528 series for
the gateway alone).

**S38. In this tier the emulator is the load ceiling, not the application.** At 30 rps the
application was comfortable — 8385 requests, zero failures, timeline p95 21 ms, HPA scaling
2 → 3 → 5. LocalStack was not: it was `OOMKilled` at its 1 Gi limit, and because its state
is an `emptyDir` plus an in-process index, the restart destroyed every DynamoDB table
silently. The services then answered `ResourceNotFoundException`, the aggregate error rate
hit 90%, and the canary aborted — a failure that is indistinguishable, from the gate's point
of view, from a bad build. *Control:* the limit is raised to 3 Gi and the cause is recorded
here, because the presenting symptom (a control run that fails its own gate) points at the
rollout and not at the data store. *Gap:* real DynamoDB has no such ceiling; any capacity
number measured in L or S is a statement about the laptop, not about the system. Two further
approximations belong with it: `setWeight` is interpreted as a replica count rather than a
traffic share without a mesh, so a "20% canary" is 20% of pods and only approximately 20% of
requests; and the load generator, Kubernetes, and the emulated AWS control plane all share
one machine, so they contend for the very CPU the measurement is about.

**S39. Six independent defects, one failure mode.** Entries S32–S37 were found in a single
afternoon by one script. Every one of them made the canary gate fail **open**: a wrong label,
a terminated background run, a blocked NetworkPolicy, an empty numerator, a misaligned
lookback window, and a metric that was never published. Each, alone, would have promoted a
broken build while reporting success — and the CI pipeline, the Helm lint, the unit tests and
the smoke test were green throughout. *Control:* `scripts/rollback-drill.sh`, which deploys a
deliberately broken build and **asserts that the gate rejects it**. A control that has never
been observed rejecting anything is a hypothesis. The corresponding control run
(`--healthy`) matters just as much, because a gate that rejects everything is equally
useless and looks identical in a one-sided test.

**S40. A correct report can still be a useless one.** The risk commenter's first run against
the real production root produced 25 identical `medium` findings — one per IAM resource,
each saying `aws_iam_role changes who can do what`. Every one was accurate: the root is
greenfield, so every role in the design is a create, and creating a role does change who
can do what. A reviewer reads that table once, concludes the section is boilerplate, and
collapses it permanently — at which point the tool detects nothing while reporting full
coverage. That is the same **fail-open** outcome as S32–S39 reached from the opposite
direction: those gates failed open by measuring nothing, this one would have failed open by
measuring everything. *Control:* type-based flagging is replaced by a content check on
created security resources (wildcard action, wildcard principal, open ingress); modified
ones still get the generic finding, because there the risk is in the delta; clean creates
are **counted and disclosed, not silently dropped**. 25 findings became 2, and both
survivors are real. *Gap:* none — the control is identical in all three tiers.

**S41. A test suite proves nothing until it has been seen to fail.** `aiops-selftest.sh` was
green at 52 assertions. Five mutations were then introduced into `risk.py` one at a time;
four were caught and the fifth — deleting the `mode != "managed"` filter, which makes the
tool report findings for data sources describing infrastructure the plan does not touch —
was not. *Control:* the safe fixture now contains a `data "aws_security_group"` admitting
`0.0.0.0/0` for the sole purpose of making that filter load-bearing, and the mutation now
produces four failures. *Gap:* none, but the practice generalises — every assertion count
quoted anywhere in this repository should be read as "untested" until someone has watched
it go red.

**S42. A rule that is written down is not a rule.** This document's own maintenance section
has always said that *"any row whose 'how the repo proves it' column names an artefact that
does not exist is a bug, not a plan"*. Nothing checked it. When a script was finally written
to, it found that the register table (`1`–`27`) and these silent-failure classes (then also
`1`–`41`) were **two independent numbering schemes that the entire repository cited
identically**, as "gap row N". Two citations were resolving to the wrong entry as a result:
`deployment.yaml` sent a reader to row 32 for the `setWeight`-as-replica-count
approximation, and `load/ramp.js` to row 33 for the emulator load ceiling — both are S38.
A comment that misdirects is worse than no comment, because it spends the reader's trust
first. *Control:* the classes are namespaced `S1`–`S45`, the ambiguous `gap row N` form is
banned outright, and `scripts/gap-verify.sh` resolves every citation, artefact path and ADR
link in the repository against this file on every CI run — unfiltered, because a dead
citation can be written into any directory. It was mutation-tested on six defects and caught
all six. *Gap:* none.

**S43. `terraform destroy` succeeding does not mean nothing is left.** The project's stated
done criterion is that `make sandbox-down` leaves nothing behind, and for most of the
project the three sandbox lifecycle targets were `echo "Not yet implemented" && exit 1` —
the headline criterion had no implementation at all, twelve phases in. Writing it exposed
the more interesting problem: a successful `destroy` means only that every resource **in the
state file** was deleted, and this project creates a great deal outside state. The AWS Load
Balancer Controller creates a real ALB, target groups and security groups in response to an
Ingress object; a PVC becomes a real EBS volume; EKS creates its own CloudWatch log group.
None are in state, so Terraform can report complete success over a running load balancer —
and in fact will *fail confusingly* first, because destroying a VPC that still contains an
orphaned ALB hangs on a `DependencyViolation` that reads like a Terraform bug rather than an
ordering mistake. *Control:* teardown deletes the Kubernetes objects that own AWS resources
first and waits for the controller, then destroys, then **asks AWS directly** across ten
resource types by tag and name prefix. The sweep distinguishes three outcomes rather than
two — `clear`, `survived`, and **`could not check`** — because a denied API call reported as
clean is the same fail-open shape as S40, and a sweep that cannot tell the difference will
certify an empty account it never actually looked at. The literal string `None`, which
`--output text` prints for an empty result, is filtered explicitly: reading it as a surviving
resource is the fail-*closed* twin, and an operator who sees a spurious failure every time
stops reading the output at all. All of it is asserted against stub binaries in
`scripts/sandbox-selftest.sh` (47 assertions, mutation-tested on seven defects, all seven
caught), because these scripts run once per session against an account nobody can reproduce.
*Gap:* the sweep has never run against a real account — only against stubs.

**S44. A broken diagram renders as a blank box, not as an error.** Every diagram in this
repo is Mermaid, rendered by GitHub at view time, and nothing validated any of them. A
syntax error does not fail a build, produce a warning or mark the file — GitHub renders an
inert grey box and moves on. The README leads with a diagram, so the first thing a reader
sees could have broken on a one-character edit and stayed broken indefinitely, and the only
detection mechanism was someone happening to look. *Control:* the `docs` CI job renders all
five with `mmdc` and fails on a parse error. Negative-tested against a deliberately
malformed node, which the gate caught. *Gap:* none — the renderer is the same engine GitHub
uses, so a pass here is a pass there.

**S45. A security gate that cannot run must not look like one that passed.** CodeQL is free
on public repositories and requires GitHub Advanced Security on private ones. On a private
repository without it, `codeql-action/init` does not skip politely — it errors. Because the
`verdict` job needs `codeql`, that makes CI permanently red for a reason unrelated to the
code, and the reflex fix is `continue-on-error`, which converts a hard failure into a green
check over a repository where **no static analysis ran at all**. That is the worst available
outcome: the SAST row of the pipeline table would be true on paper and empty in fact.
*Control:* a preflight step asks the API whether Advanced Security is enabled, every
analysis step is guarded on the answer, and when it is absent the job writes a
`:warning: SAST did not run` block to the run summary stating explicitly that this is *a
skip, not a pass*. Same pattern as the `cost` job and a missing Infracost key (row 18).
*Gap:* on a private repository without Advanced Security there is genuinely no SAST — the
control makes that visible, it does not fix it. Making the repository public removes the
gap entirely.

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
