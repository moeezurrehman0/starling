# 08 — The 180-minute session

*Phase 14. The runbook for a single KodeKloud playground session, from an empty
account to a verified-empty account.*

---

## Why this document exists

Every other chapter in this repo explains a design. This one is an operational
script, and it is written differently on purpose: it is meant to be followed
with a timer running.

The KodeKloud playground gives 180 minutes and then deletes everything. That
constraint shaped the whole project — it is why nothing here is a click-path,
why `sandbox-up` is resumable, and why `sandbox-down` ends with a verification
sweep instead of a success message. The clock is the binding constraint, not
money and not quota.

The failure this document is designed to prevent is specific and it is not
technical: **discovering at minute 150 that the demo will not fit.** By then
the only options are bad ones. So every stage prints where it is against the
budget while there is still time to drop something and keep the rest.

---

## Before the clock starts

None of this consumes session time. Do it the day before.

```bash
make sandbox-selftest     # the lifecycle, against stub AWS — no account needed
make sandbox-plan         # dry-run: prints every command it will run, touches nothing
make gap-verify           # every citation in the gap register still resolves
make helm-lint            # every chart renders for every environment
make tf-validate          # fmt, validate, tflint, checkov on both roots
```

`make sandbox-plan` is the important one. It is the entire provisioning path
with `--dry-run`, so it answers "will this script get through its own logic"
without an account. It will not catch a quota denial, but it will catch the
missing tool, the typo'd flag and the unset variable — which is most of what
actually goes wrong.

Have ready:

- the playground's temporary credentials exported (`AWS_ACCESS_KEY_ID`,
  `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`)
- images already pushed to ECR by CI, or 8 spare minutes to push them
- a second terminal for `make sandbox-status`

> **The credential guard.** `sandbox-up` refuses to run if the credentials look
> like a long-lived IAM user rather than an assumed role, and the refusal is not
> overridable by accident. Applying this topology to a personal AWS account is
> not a recoverable mistake, and the guard costs one `sts` call.

---

## The budget

| Minutes | Stage | Command | What must be true at the end |
|---|---|---|---|
| 0–20 | Infrastructure | `make sandbox-up` | 8 DynamoDB tables, ECR, S3, RDS, EKS control plane up |
| 20–22 | Access | *(same command)* | `kubectl get nodes` shows 3 × `t3.medium` Ready |
| 22–28 | Platform | *(same command)* | metrics-server; ALB controller **or** a logged NodePort fallback |
| 28–35 | GitOps | *(same command)* | ArgoCD healthy, app-of-apps synced, pods Running |
| 35–45 | Observability | *(same command)* | Prometheus targets up, Grafana reachable, Tempo receiving |
| **45–150** | **Demo window** | below | — |
| 150–170 | Teardown | `make sandbox-down` | the sweep reports nothing left standing |
| 170–180 | Buffer | — | — |

It is one command to minute 45. That is the whole point of Phase 14.

```bash
make sandbox-up
```

If it dies at minute 30, it resumes — it does not restart:

```bash
make sandbox-up ARGS="--from argocd"
```

Re-running from zero would re-create an EKS cluster that already exists and
cost twelve of the remaining minutes. If you are already behind at minute 45,
drop observability rather than the demo:

```bash
make sandbox-up ARGS="--skip-observability"
```

Traces are the single best thing in this repo, so this is a real loss — but it
is a smaller loss than not reaching the canary.

---

## The demo window, minutes 45–150

Six things, in dependency order. Each is a `make` target, and each has already
been proven on kind — Tier S is showing it on real AWS, not debugging it there.

### 1. The product works end to end (45–55)

```bash
make sandbox-status          # second terminal, leave it
```

Sign up, post a tweet, follow an account, read the timeline. The path is
`gateway → user-service → tweet-service → DynamoDB Streams → fanout-worker →
timelines → timeline-service`. Two Redis instances sit in front of it, split by
celebrity status (ADR-0013).

### 2. A distributed trace crosses four services (55–65)

Grafana → Explore → Tempo → search by `trace_id` from the gateway access log.
The span tree crosses the stream boundary, which is the part that is hard and
the part worth showing.

This is also where Phase 10's worst finding lives: Boot 4 moved tracing
autoconfiguration into a module nobody had on the classpath, while leaving the
old property bindable. Every service exported nothing and logged no error
(gap register row 24).

### 3. Load drives an HPA scale-out (65–85)

```bash
make load-test PEAK_RPS=30 DURATION=10m
```

Watch `kubectl get hpa -w`. On kind this scaled 2 → 3 → 5 against a `Rollout`
scale subresource at 30 rps with p95 of 21 ms and zero failures. On EKS the
pods have real nodes to land on.

**Node** scale-out is not demonstrable here — the playground caps at 3 nodes,
so Karpenter is Tier P only. That is gap register rows 4 and 5, and stating it
out loud is better than implying the cap is a design choice.

### 4. A promotion PR canaries to prod (85–110)

Merge the promotion PR. ArgoCD syncs `prod`, Argo Rollouts steps 25% → 50% →
100%, and inline analysis queries Prometheus at every step.

### 5. The canary refuses a broken build (110–130)

```bash
make rollback-drill            # ship a deliberately broken canary — must abort
make rollback-drill-control    # ship a healthy one — must promote
```

Run **both**. The control run is not ceremony: a gate that rejects everything
passes the first test and is useless. Eight defects were found building this on
kind, and **every one made the gate fail open** — wrong metric label, analysis
terminated-and-scored-successful, a NetworkPolicy blinding the controller, an
empty-vector numerator, a `rate()` window straddling the previous deploy,
histogram buckets never published, a fault injected on a path the load
generator never called, and the drill's own assertion matching stale
AnalysisRuns (gap classes S32–S39).

### 6. An alert fires and reaches its runbook (130–150)

Let the error budget burn from step 5. The alert annotation links to
`docs/runbooks/error-budget-burn.md`. An alert without a runbook is a pager
that wakes someone with no next action.

---

## Teardown, minutes 150–170

```bash
make sandbox-down
```

**This is not a formality, and it is not for the money** — the account is wiped
regardless. It runs because it is the only way to know the destroy path is
complete, and the destroy path is the part of infrastructure code that is never
exercised until it is urgent.

It does three things in order:

1. **Deletes Kubernetes objects that own AWS resources first** — Ingresses,
   `LoadBalancer` Services, PVCs — then waits 45 seconds. Going straight to
   `terraform destroy` with a live ALB in the VPC does not fail cleanly; it
   hangs on a `DependencyViolation` that reads like a Terraform bug.
2. **`terraform destroy`.**
3. **Sweeps AWS directly** for EKS clusters, tagged EC2, load balancers,
   DynamoDB tables, RDS, S3, ECR, orphaned EBS volumes, `k8s-*` security groups
   and EKS log groups.

Step 3 is the deliverable. `terraform destroy` succeeding means only that
everything **in the state file** was deleted — it is structurally silent about
the ALB the load-balancer controller created, the EBS volume behind a PVC, and
the log group EKS made on its own. None of those are in state. A teardown that
reports success on Terraform's word alone is an assertion, and the point of
this repo is to stop accepting assertions.

The sweep distinguishes three outcomes, not two:

| Result | Meaning | Exit |
|---|---|---|
| `✓ clear` | the query ran and returned nothing | 0 |
| `✗ <ids>` | resources survived, listed by id | 1 |
| `? could not check` | **the query itself failed** | 1 |

The third row is the one that matters. "I could not check" is not "there is
nothing there", and a sweep that collapses them reports a clean teardown over a
running cluster. It is the same fail-open shape as gap class S40, and it is
asserted against a stub in `scripts/sandbox-selftest.sh`.

On survivors, the session state file is **kept** — deliberately, so you still
have the region and prefix needed to finish by hand. Re-run it; the sweep is
idempotent and the controller may simply have been slow.

---

## What a session cannot show you

Being explicit about this is the point of the gap register, and the honest
version of this runbook ends here rather than with a victory lap.

| Not demonstrable | Why | Row |
|---|---|---|
| CI authenticating to AWS via OIDC | IAM is wiped each session, so Tier S is laptop-driven | 1 |
| Remote Terraform state | a state bucket would be wiped with everything else | 2 |
| Private subnets and NAT | default VPC only; **nodes are public, and `sandbox-up` prints that as an output** | 3 |
| Karpenter node scale-out | 3-node cap makes it meaningless | 4, 5 |
| Per-service IRSA | probed, not assumed; falls back to the node role and says so | 9 |
| Secrets Manager + ESO rotation | Tier P only | 10 |
| Route53, ACM, CloudFront, WAF | no domain, no CDN in the playground | 11–13 |
| Multi-AZ, PITR, DR | single AZ, ephemeral by construction | 17 |

Everything in that table is written, `terraform validate`-d, `terraform test`-ed
against mocked providers, and Checkov-scanned in CI. It is never applied.

The two rows with **no gap at all** are worth naming: DynamoDB (row 6) and
DynamoDB Streams (row 8) are the same service, same API and same data model in
all three tiers. That was the reason for ADR-0011 and ADR-0012 — choosing the
managed service the sandbox actually permits converted the single largest
asserted row in the register into a demonstrated one.

---

## If it goes wrong

| Symptom | Cause | Do this |
|---|---|---|
| `sandbox-up` dies mid-way | anything | `make sandbox-up ARGS="--from <stage>"` |
| Behind at minute 45 | EKS was slow | `--skip-observability`, keep the canary |
| `kubectl` suddenly unauthorized | session token expired | re-export credentials, re-run `aws eks update-kubeconfig` |
| Pods `Pending` | 6 vCPU is genuinely full | scale the Rollout down before load-testing |
| `terraform destroy` hangs on a subnet | an ALB is still live | re-run `make sandbox-down`; step 1 handles it |
| Sweep reports survivors | the controller is behind | wait 60s, re-run — it is idempotent |

---

## Related

- [`docs/02-workflow.md`](02-workflow.md) — how code reaches a cluster
- [`docs/04-infrastructure.md`](04-infrastructure.md) — the modules this applies
- [`docs/06-load-and-delivery.md`](06-load-and-delivery.md) — the load and canary machinery
- [`docs/16-gap-register.md`](16-gap-register.md) — every row referenced above
