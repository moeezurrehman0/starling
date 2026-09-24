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

**These are numbered `S1`–`S60`, in their own namespace.** They are not rows of the register
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
first. *Control:* the classes are namespaced `S1`–`S60`, the ambiguous `gap row N` form is
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

**S46. A rename that verifies contents has not verified names.** Renaming the project
touched 583 identifiers across 264 files, and the gate written to enforce it — no tracked
file may contain the old name — reported clean immediately afterwards. The build did not
compile. Gradle encodes a convention plugin's id in its *filename*, so
`build-logic/src/main/kotlin/<old>.java-conventions.gradle.kts` still carried the old name
while every `plugins { id("...") }` block referencing it had already moved, and `grep` over
file contents cannot see a filename. The same shape applies to Java package directories,
Helm chart directory names and anything else where the path *is* the identifier. What makes
this a register entry rather than a footnote is that the check was written specifically to
make the rename safe and it certified a broken tree — the reassurance was the failure.
*Control:* the assertion scans `git ls-files` output as paths as well as grepping contents,
and the compile step is the independent witness. *Gap:* neither covers untracked files or
the working directory's own name, which sits outside the repository entirely.

**S47. A gate that disagrees with itself teaches people to re-run it.** Checkov 3.3.19,
run six times against an unchanged tree, returned 0, 1, 0, 0, 1 and 3 failures. The
offender is `CKV2_AWS_19` — a graph check asserting every Elastic IP is attached to an EC2
instance — evaluated over a `for_each` set, where resolution appears to depend on
iteration order. The finding was also wrong on the merits: the addresses are attached to
NAT gateways, which the check does not recognise. But the wrongness is the lesser problem.
A control that is red on roughly a third of runs cannot be acted on, and the behaviour it
actually trains is the reflex to press re-run until the colour changes — a reflex that is
then applied to the genuine failures alongside it. One flaky check devalues the other 258.
*Control:* the check is skipped with its reasoning recorded at the resource, and the
scanner is pinned to an exact version in both places CI installs it, so the ruleset cannot
change underneath the skips that were written against it. *Gap:* the skip is
project-wide, so a genuinely unattached EIP now passes; nothing here distinguishes a check
that is flaky from one that is correctly intermittent, and the only evidence of the
flakiness is a paragraph — re-running six times is not something CI does or could afford
to do.

**S48. Eleven of twenty-five findings were hidden by a `tail`.** The Checkov step printed
`tail -30` of the scanner's output, which rendered 7 of 25 failing resources with no
indication that anything had been cut. Every property of a complete report was present:
the command ran, the findings were formatted, the section ended. The gap surfaced only
because the scanner was installed locally and run directly. This is gap S40's shape
reaching a second tool, and the reason it is dangerous is the fix loop it produces — a
reader resolves the visible findings, sees the list shorten, and infers progress toward
zero that the truncation invented. *Control:* the step now prints the failing-resource
count first and then every finding, never a tail. *Gap:* nothing prevents the next
reporting step from being written the same way; the property "output is not silently
truncated" is not itself asserted anywhere.

**S49. The local build and the CI build used different Docker drivers, and only one of
them could fail.** All five image builds failed on the CDS training run with `Invalid
endpoint, must start with http:// or https://: unix:///dev/otel-grpc.sock` — a value no
file in this repository sets and no build argument passes. buildx's `docker-container`
driver, which `docker/setup-buildx-action` creates, injects its own tracing configuration
into every build step: `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `..._TRACES_PROTOCOL=grpc`
and `OTEL_TRACES_EXPORTER=otlp`. The application constructs an OTLP exporter during
context refresh, the gRPC exporter rejects a unix scheme, and the training run exits
non-zero. `make image` used the default `docker` driver, which injects none of this, so
the local gate was structurally incapable of reproducing the failure — it was not a weaker
check, it was a check of a different thing wearing the same name. Two plausible fixes were
written and verified to do nothing before the real one: `ENV` loses because BuildKit
injects over it, and overriding the generic `OTEL_EXPORTER_OTLP_ENDPOINT` loses because
the signal-specific `_TRACES_` variable takes precedence. Both look correct in a diff.
*Control:* the CDS step exports the signal-specific variables inside the `RUN`, and `make
image` and `scripts/kind-deploy.sh` both build through the same `docker-container` builder
so every local path and the CI command exercise the same machinery. The kind path needs
`--load` on top, because the container driver keeps its result in its own store while
`kind load docker-image` reads the host daemon — without it the build succeeds and the
pods sit in `ErrImagePull`. *Gap:* driver parity is asserted by those two files using the
right flag, not by anything that checks it — and the class is general. Any CI
runner that injects environment into builds can break a step that reads it, and there is
no inventory of what this build reads from its environment.

**S50. A pinned action that installs an unpinned script is not pinned.** The image jobs
used `aquasecurity/trivy-action` at an exact tag, which reads as a controlled dependency.
The action does not contain the scanner: it checks out the install script held under
`contrib/` in `aquasecurity/trivy@main` — a branch — and runs it. That script began exiting 1 with no
diagnostic immediately after resolving the release, and all five jobs failed on a day
nothing in this repository had changed near them. The pin was real and bought nothing,
because it pinned the wrapper and not the thing being installed. The scan now runs the
official scanner image at an exact tag from `scripts/image-scan.sh`, which also makes it
runnable locally — the same property S49 is about. *Gap:* the vulnerability database is
still fetched at run time and still moves, so the gate's verdict can change without any
commit. That is inherent to scanning rather than a defect, but it means a green scan is a
statement about today and re-running an old commit may not reproduce it.

Turning the scan on for the first time found eight real fixable findings: three CRITICAL
in `tomcat-embed-core` 11.0.24 (all authentication or security-constraint bypasses,
managed by the Spring Boot 4.1.1 BOM and fixed in 11.0.25), and five HIGH in the
`distroless/java-base-debian12` base with no fix available in that base at all. Tomcat is
now held ahead of the BOM by a constraint; the base moved to `debian13`, which scans
clean. Both were only visible because the scan was made to run, which is the honest
summary of what a gate that has never executed is worth.

**S51. A correct readiness signal made a correct image unverifiable.** With the scan
fixed, four of the five image jobs passed and `fanout-worker` failed at verification with
"service did not become healthy". Nothing was wrong with it. It is the only service that
consumes a DynamoDB stream, its `stream` indicator is deliberately in the readiness group
rather than liveness, and until a stream resolves it reports `OUT_OF_SERVICE` — so
`/actuator/health` answered 503 and the probe, which asks only whether the service is
healthy, read that as a broken image. The container log was dominated by OTLP span-export
failures against an absent collector, which were pure noise: the four passing services
emit exactly the same errors. The fix is to tell the worker it is not supposed to be
consuming (`FANOUT_ENABLED=false`), which is a case the indicator already models
explicitly — it reports `UP` with `consuming: false`. *Gap:* the verification therefore
never exercises the stream path, and the class is broader than this one service. A smoke
test that boots a service with its dependencies absent can only assert readiness for
services whose readiness does not depend on them; every honest readiness signal narrows
what such a test can prove, and the two have to be designed against each other rather
than discovered in CI.

**S52. The release automation had never once succeeded, and the cause was not in the
repository.** With CI green, the Release workflow was still failing on every push, as it
had from the first run. `release-please` did all of its work correctly — computed the
version, wrote the changelog, created the branch and the commit — and then failed on the
last call with `GitHub Actions is not permitted to create or approve pull requests`. That
is a repository setting, off by default, and nothing in the checked-out tree can express
it or detect that it is wrong. The workflow's `permissions:` block was correct and
irrelevant: it grants what the token may request, not what the repository allows the
token to do. Enabling the setting made the same unchanged workflow pass. Its first PR
then sat with its checks in `action_required`, because workflows raised by a bot author
need manual approval, so the automation could have appeared to work while silently never
being verified. *Gap:* both are account-level state held outside version control, so a
rebuild of this repository elsewhere reproduces neither the fix nor the diagnosis. The
class is the general one — a pipeline can be entirely correct as code and still be
disabled by configuration that the code cannot see, and the only signal is a job that has
never been green.

**S53. Half the pipeline had only ever run on one event.** Every gate in this repository
was written to run on both `push` and `pull_request`, and until the release automation
opened PR #9 only the push half had ever executed. On the first real PR run two jobs
failed immediately, both with `Resource not accessible by integration`: `paths-filter`
and `gitleaks` each ask the API to list what the pull request contains, and the default
token grants no `pull-requests` scope. Neither makes that call on a push — the filter
diffs commits locally and gitleaks scans history — so both had passed every time while
being broken for the event they matter most on. The fix is one `pull-requests: read` in
each. *Gap:* what this exposes is not the missing scope but that a green history proves
nothing about an event that has never fired. The same is true of everything still only
triggered from a tag or a schedule here, and CI has no way to tell the difference between
a job that passes and a job that has never been asked to run.

The first attempt at the fix made it worse in an instructive way. Granting the scope
inside the called workflow alone is invalid — a reusable workflow cannot request more than
its caller holds — and the result is not a failed job but a `startup_failure`: the entire
run refuses to begin, so there is no log, no annotation and no failed step to read, on
both CI and Publish at once. The permission has to be granted at the call site too.
*Gap:* nothing in this repository validates a workflow file before GitHub does. There is
no `actionlint` in the Scripts job, so every workflow change is verified by pushing it,
and the failure mode for an invalid one is the least diagnosable output the platform
produces.

*Partly closed:* `actionlint` now runs in the Scripts job, pinned to a release archive
with a checksum rather than an action. It does not close the gap that prompted it. Run
against the exact broken file — the call site missing `pull-requests: read` — it reports
nothing, because permission inheritance between a caller and a reusable workflow is not
something it models. What it does catch is syntax, expression and `run:` shell errors,
which is most of what goes wrong in a workflow but not the class that produced a
`startup_failure` here. Recording that distinction matters more than the tool: a lint
step that a reader assumes covers workflow validity, when it covers most of it, is the
same trap as a gate that has never run.

**S54. The advisory comment's first-ever finding was one nobody could act on.** Opening
the first pull request that touched `.github` finally ran the AIOps job on a pull request,
which is the only event it posts on: it planned the production root against LocalStack —
109 resources — and left its risk comment. The comment worked. Its two findings were both
`wildcard-resource` on IRSA policies, and both were `dynamodb:ListStreams`, which AWS
defines as taking no resource: IAM rejects the policy if it is scoped to anything but
`*`. The module already said so in a comment. So the tool's first real output was correct,
permanent and impossible to fix, on every plan, forever — which is precisely how a
reviewer learns that this comment is something to scroll past, and an advisory that gets
scrolled past is worse than none, because its presence is mistaken for coverage. The rule
now suppresses per *action* rather than per statement, from a short literal list, so an
unscopable action folded in beside a scopable one cannot launder the second. *Gap:* the
list is maintained by hand and grows only when a policy here needs it, so the first
encounter with any other resource-less action is a false positive again. A prefix rule
would be worse — `dynamodb:ListTagsOfResource` does take an ARN — and a suppression that
silently over-matches is the one defect this tool must not have.

Finding this at all was luck. The fix was opened as its own pull request specifically to
watch the finding disappear, and the AIOps job did not run: its condition keys on the
`terraform` and `ci` filters, and `tools/aiops/**` was in neither. A pull request that
rewrote the risk rules ran every other gate and not the one it changed — and had the
rewrite been wrong, `main` would have taken it green. The job now has its own filter.
*Gap:* nothing checks that a job's condition covers the job's own inputs, so this class
is only ever found by noticing a job that should have run and did not.

**S55. The single required check was required by nothing.** This file has said from the
start that the `verdict` job is "the single required check", and for the whole of the
project's life it was not required anywhere: `main` had no protection, so every commit in
this history was pushed straight to it, including the ones that fixed the pipeline. The
workflow triggers were never the problem — `pull_request` fires on any branch and `push`
only on `main`, which is correct — the problem is that a trigger describes what runs, not
what must pass. Enforcing it turned out to be impossible on the plan the repository was
on: both branch protection and rulesets return `403 Upgrade to GitHub Pro or make this
repository public` for a private free repository, so the rule could be written down and
followed by hand but not applied. *Gap, now closed by changing the repository rather than
the code:* it is public, and a ruleset requires a pull request and a green `CI` before
anything reaches `main`, with no bypass actors — the rule applies to the owner too. What
does not generalise is the fix. On a private repository under a free plan this control
cannot exist at all, and a pipeline's guarantees can therefore depend on billing, which
is not a property any amount of care in the repository can compensate for.

Going public had a cost worth recording. Fifty-one of fifty-five commits were authored
under an employer email address, and rewriting history does not retract what GitHub keeps:
commits attached to a pull request stay reachable under `refs/pull/*` indefinitely, so the
address would have remained visible on merged PRs even after `main` was clean. The
history was rewritten, the tree verified byte-identical to a pre-rewrite bundle, and the
repository recreated rather than flipped — which cost the pull requests and the AIOps
comments on them. *Gap:* the identity a commit carries is decided by local git
configuration at the moment of the commit, and nothing in this pipeline checks it. Every
gate here reads the content of a change; none of them read who it says wrote it, and that
is the one field that cannot be corrected after publication.

**S56. A dependency bot can only bump what it can see, and it reports success either
way.** The aws provider constraint is restated in nine files — two roots and seven
modules — and Dependabot watches the two roots, because a root is the only thing with a
`.terraform.lock.hcl` for it to resolve against. So the 5.100 → 6.66 bump arrived as two
pull requests that could not possibly pass: the roots moved, the modules did not, and
`init` failed with *locked provider 6.66.0 does not match configured version constraint
~> 5.70, ~> 6.66* — a message that names neither the file that moved nor the file that
stayed. Pointing Dependabot at the module directories would not have helped; it would
have produced seven more PRs, each individually just as broken, because the constraint is
one decision expressed in nine places and a per-directory bot cannot make it once. *Gap,
now closed:* `tf-validate.sh` asserts the nine constraints agree and prints every file
with its value when they do not, so a partial bump fails on a line that says which file
is behind. The upgrade stays manual, which is the honest outcome — a major provider
version is a change to make deliberately, not one to merge because a bot opened it.

Worth recording what the upgrade itself showed, because it is the opposite of what a
major version number suggests. Planned against LocalStack and diffed resource-by-resource
against a 5.100 plan, v6 produced an identical set of 119 resources and no semantic
change to any of them: every difference was either the new per-resource `region`
attribute, a newly-added optional block materialising as empty, or a sparser encoding of
the same value — the logs bucket's encryption rule dropped a `kms_master_key_id = ""` it
never used while staying `AES256`. The risk in a major provider bump was not in the
configuration; it was in the bump arriving in pieces. *Gap:* that comparison is
something the pipeline cannot do for itself. It required planning both versions and
diffing the JSON by hand, and nothing here would have caught a semantic change if one had
occurred — the LocalStack plan proves a configuration still plans, not that it still
means what it meant.

**S57. A minor dependency bump replaced the HTTP client underneath the runtime.** The
AWS SDK 2.46 → 2.55 bump is a patch-level version change by the look of it, and what it
actually did was deprecate `apache-client` in favour of `apache5-client` — a different
artifact, a different package, and Apache HttpClient 5 instead of 4 inside a jlink runtime
whose JDK module list was measured by running the old one. Nothing about the version
number says that. It surfaced only because `-Werror` is on: two deprecation warnings
failed the compile, which is the entire value of treating warnings as errors on a
dependency bump — without it the build would have gone green on a deprecated transport
and the migration would have happened later, under worse circumstances.

The part that matters is what verified the fix. Swapping the HTTP client changes which
classes get loaded reflectively at runtime, and `jdeps` cannot see any of it — the same
reason `docker/jlink-modules.txt` exists as a measured list rather than a generated one.
A green Gradle build proves nothing here, because Gradle runs on the full JDK. What
proves it is `image-verify.sh` performing a live TLS handshake to an AWS endpoint from
inside the built distroless image: that exercises the new transport through the trimmed
runtime, and it is the only check in this repository that would have caught a missing
module. It passed for both affected services. *Gap:* the coverage is narrower than it
looks. That probe makes one kind of call; a module needed only by some other code path —
a retry, a checksum algorithm, a credential provider not used at startup — would still
reach production as a `NoClassDefFoundError` on first use, and no gate here would have
said otherwise.

**S58. A grouped bump split one decision across two groups, and the majors underneath were
not ready.** Next 15 → 16 arrived as two pull requests: `next` in the "next" group and
`eslint-config-next` in "dev-dependencies". They are released together and versioned
together, and neither PR can pass without the other — the same shape as S56, reached by a
different route. Grouping is configured by where a dependency sits in `package.json`, and
a framework that ships its lint config as a devDependency does not fit that. The upgrade
had to be assembled by hand, which is the general lesson: a bot can group by
configuration, but coupling is a property of the release, not of the manifest.

The upgrade itself found three things no version number advertised. Next 16 removed
ESLint from the build and the `eslint` key from `NextConfig`, so `next.config.ts` became a
type error — the lint gate survived only because the web job already ran `npm run lint` as
a separate step, which was luck rather than foresight. `eslint-config-next` 16 ships flat
config natively, so the `FlatCompat` shim around it stopped working: the validator
`JSON.stringify`s the config and the native one holds a cycle through the react plugin.
And the new ruleset caught a real defect that had been in `Composer.tsx` since it was
written — a `useEffect` calling `setText("")`, which resets the box in a second render
after the browser has already painted the first. It is now a render-time reset guarded by
the previous value, which React restarts before committing.

Two bumps were *not* taken, and refusing them is part of the result. TypeScript 7 is
rejected by `typescript-eslint` outright, and ESLint 10 breaks `eslint-plugin-react`
inside `eslint-config-next` on an API change. Both are held at their current majors.
*Gap, now closed:* a held-back dependency looks exactly like one nobody has looked at, so
both are now `ignore` entries in `.github/dependabot.yml` carrying the reason and the
condition for revisiting, and `eslint-config-next` has been moved into the `next` group so
the next release of that framework arrives as one pull request. What does not generalise
is the grouping fix — it works because this coupling is known. An unknown one still
arrives as two PRs that each fail for reasons that do not mention each other.

**S59. A plausible diagnosis that fits the evidence and is still wrong.** Merging the
release pull request produced `✔ No latest release found for path: ., component: , but a
previous version (0.2.0) was specified in the manifest` followed by `⚠ There are untagged,
merged release PRs outstanding - aborting`. release-please compares a tracked file,
`.github/.release-please-manifest.json`, against the newest GitHub release, which is not
tracked. The repository had just been recreated (S55), which carries the first across and
drops the second. Manifest says released, no tags exist, tool refuses: the story explains
every symptom, and it is the wrong story.

Acting on it — creating the `v0.2.0` release by hand at the release commit and moving the
pull request's label to `autorelease: tagged` — made the next run report the correct
no-op, which read as confirmation. It was not. There were no commits to release yet, so a
tool that was still broken and a tool that was fixed produce identical output. The
diagnosis survived because the test could not distinguish them.

The next real release failed the same way, and the line that matters had been on screen
the whole time, one row above the one that was read: `⚠ PR component: undefined does not
match configured component: starling`. `release-please-config.json` set `package-name`,
which becomes the component, alongside `include-component-in-tag: false`, which keeps the
component out of both the tag and the pull request title. So release-please looked for a
release belonging to component `starling`, every release and every merged release PR
reported component `undefined`, and nothing ever matched — `⚠ Expected 1 releases, only
found 0`. This had been true since the configuration was written. **No release had ever
been tagged by this repository, in either incarnation.** The recreation destroyed no
working state; it only removed the last evidence that the state had never worked.
Dropping `package-name` fixes it, because a single root package has no component to name.

Two things are worth keeping. The first is that the workflow **exited zero** throughout. A
green Release run, a merged release pull request, an updated `CHANGELOG.md`, and a bumped
manifest all appeared, and no version existed; the only dissent was a warning in a log
nobody reads on a green run. The second is the failure of reasoning. A recent, dramatic
event was available as an explanation, it accounted for the symptoms, and it displaced
reading the rest of the output. The correct cause was less interesting and older, which is
the usual shape.

*Gap, now closed:* the Release workflow asserts its own bookkeeping after the action runs.
It fails if any merged pull request still carries `autorelease: pending` — precisely the
"cut but never tagged" state, detectable whether or not any release exists — and it fails
if the manifest version and the newest release tag disagree. This check is what caught the
recurrence: it failed the run, named the pull request, and forced the log to be read
properly instead of accepting another silent green. Writing it also surfaced a trap worth
more than the check itself: the obvious implementation, `gh pr list --state merged --label
'autorelease: pending'`, returns zero *while the label is attached*, because `--label`
routes the query through the search index, which lags. Tested against the real repository
in both directions, it never fired. The label is therefore filtered locally from the plain
listing. A check that cannot fail is worse than no check, because it also removes the
suspicion that would have led someone to look.

*Residual gap:* the assertion proves the bookkeeping is consistent, not that the
configuration is right. A repository that never releases anything satisfies it forever.

**S60. The repository rebuild did break publishing, and the workflow reported success
while never once pushing an image.** Unlike S59, this one really is the rename. The
original repository was renamed to
`starling-archive-private` and a new one took the name `starling`. GHCR package ownership
follows the repository, not its name, so all five container packages stayed linked to the
archived repository while the new one pushed to byte-identical paths — `github.repository`
is the same string it was before. Every push returned `denied: permission_denied:
read_package`: the token had `packages: write`, but write on a package it does not own is
not write.

The failure is ordinary. What it exposed is not. Two Publish runs on `main` were **green**,
and both were green because the path filter skipped every matrix job — a skipped job and a
completed one render as the same tick, so "Publish succeeded" was true and "an image was
published" was false, and nothing in the repository could tell them apart. Publishing had
never worked here at all, and the only reason it was investigated is that a third run
happened to touch a service path. Had the release gone out on docs-only commits, the gap
register would have described a supply chain that did not exist.

Recovery was to delete the five orphaned packages so the new repository could create its
own; they now read `public | moeezurrehman0/starling` and the tags resolve. *Gap, now
closed:* the push step's success is the builder's opinion, so the job now asks the
registry — it reads back the tag it just wrote and fails unless the digest matches
`steps.push.outputs.digest`. Writing that check repeated the lesson from S59 almost
exactly: the obvious form, `imagetools inspect --format '{{.Manifest.Digest}}'`, is
accepted and then ignored by the buildx in use, which prints its default block instead, so
the comparison would have run against text that was never a digest. It parses the field
and rejects anything not shaped like one. *Residual gap:* this proves a tag resolves, not
that the matrix ran — a filtered-out service still publishes nothing and still reports
success, and that remains indistinguishable from the outside.

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
