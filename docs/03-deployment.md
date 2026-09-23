# Deployment

How a container image becomes a running workload, in each of the three tiers.

This document covers the Kubernetes layer only. What gets built is
[docs/02-workflow.md](02-workflow.md); what the sandbox cannot express is
[docs/16-gap-register.md](16-gap-register.md).

---

## 1. One chart, seven workloads

`deploy/charts/service` is a single generic chart. Every workload — five Spring Boot
services, the Next.js frontend, and the search indexer — is an instance of it.

The alternative, a chart per service, is the more common choice and the wrong one here. The
services genuinely differ in four things: their name, their port, their configuration, and
who is allowed to talk to them. Everything else — the security context, the probe shape, the
resource posture, the rollout strategy — is identical, and duplicating it seven times means
that fixing it once fixes it in one place and leaves six.

Where the workloads really do differ, the chart says so rather than pretending:

| Difference | How it is expressed |
|---|---|
| The workers serve no traffic | `service.enabled: false` — no Service, no HPA, no Ingress |
| The frontend has no actuator | `probes.livenessPath` / `readinessPath` overridden |
| The frontend is not a JVM | `JAVA_TOOL_OPTIONS` is still rendered; Node ignores it |

### The two workloads that must never scale

`fanout-worker` and `tweet-indexer` are DynamoDB Streams consumers, and neither coordinates
shard ownership with its peers. Every replica reads every shard. Two replicas therefore
fan out every tweet twice and index every tweet twice — and neither produces an error, a
log line, or a failed health check. The symptom is duplicate timeline entries and duplicate
search results, discovered by a user.

`tweet-indexer` runs the same image as `tweet-service` with `SEARCH_INDEXER_ENABLED=true`,
as its own single-replica Deployment. It is split out rather than enabled inside
`tweet-service` because `tweet-service` autoscales to twenty replicas in Tier P — so the
constraint would have been a code comment directly contradicted by an HPA.

Both are pinned to one replica with autoscaling explicitly off, and
`scripts/helm-validate.sh` fails the build if that ever stops being true.

---

## 2. The decisions worth arguing about

**CPU requests, no CPU limits.** A CPU limit throttles at the quota boundary even when the
node is idle. For a JVM that lands hardest during startup and GC, both of which are bursty:
the pod fails its startup probe on a loaded node and is restarted, so spare capacity becomes
an outage. Memory limits *are* set, because memory is not compressible and an unbounded pod
takes the node with it.

**`MaxRAMPercentage`, not `-Xmx`.** The heap follows the memory limit, so the two cannot
drift apart. 70% leaves room for metaspace, code cache, thread stacks and direct buffers —
all of which a container OOM kill counts and `-Xmx` does not.

**A startup probe, not a long `initialDelaySeconds`.** A long liveness delay is slow to
notice a genuine hang for the entire life of the pod. A startup probe buys slow-cold-start
time once and then gets out of the way.

**The Deployment omits `replicas` whenever an HPA exists.** Rendering both is legal and both
objects pass schema validation, which is what makes it dangerous: the HPA scales up, the
sync controller reverts the field to the number in Git, and the two oscillate. It presents
as unexplained pod churn, not as a manifest error.

**No PodDisruptionBudget below two replicas.** `minAvailable: 1` against one replica cannot
be satisfied by any eviction, so `kubectl drain` blocks forever. The chart works this out
from the rendered replica count rather than trusting each values file to remember.

**`readOnlyRootFilesystem`, `automountServiceAccountToken: false`, PSS `restricted`.** The
last of these is set as a namespace label by `scripts/kind-deploy.sh` and enforced, so a
chart change that violates the profile is rejected at admission rather than discovered on a
cluster that happens to be permissive.

---

## 3. Environments

`deploy/envs/dev` and `deploy/envs/prod` are the same seven files twice. The **diff between
them is the deliverable** — it is the honest inventory of what Tier S proves and what it
only claims.

| | dev (Tier L, Tier S) | prod (Tier P) |
|---|---|---|
| Registry | GHCR | ECR |
| Identity | none — node instance role | IRSA, one role per service |
| DynamoDB | LocalStack | real, `prod-` table prefix |
| Redis | two pods | two ElastiCache clusters |
| Search DB | a Postgres pod | RDS |
| Replicas | 1 | 3, HPA to 20 |
| Ingress | NodePort | ALB with TLS |
| Secrets | an applied manifest | External Secrets Operator |

Two details in that table matter more than they look.

The Secret **name** is identical in both. Only its origin differs, so no workload manifest
changes between tiers and the rollout path is genuinely the same one.

`SESSION_COOKIE_SECURE` is set to `false` in dev and **absent** in prod. The application
defaults it on, so serving an insecure session cookie requires writing the override down
where a reviewer sees it in a diff. Defaulting it off and remembering to enable it in
production is the same configuration with the failure mode reversed.

---

## 4. GitOps

`deploy/argocd` is an app-of-apps: one `Application` per workload per environment, plus the
`dev-infra` stack at sync wave `-1` so Redis, Postgres and LocalStack are healthy before any
application pod starts.

**dev auto-syncs with prune and self-heal. prod does neither.** Self-heal reverts a manual
`kubectl edit` within minutes. In dev that is exactly right — it makes drift impossible and
forces every change through Git. In production it removes the operator's ability to stop a
bad rollout by hand, which is a capability you want at 3am.

Server-side apply everywhere, and `spec/replicas` is listed under `ignoreDifferences`, so
field ownership is stated rather than fought over.

The `resources-finalizer` is on every Application. Without it, deleting an Application
leaves everything it created running and unowned — the tree looks clean and the cluster is
full of orphans.

---

## 5. Tier L

```
make kind-up      # 3-node cluster, Calico, ArgoCD
make kind-deploy  # build, load, helm install
make kind-down
```

Three nodes, because Tier S has three. A single-node cluster satisfies topology spread
trivially, never blocks on a PDB, and schedules pods that could not be scheduled anywhere —
so it hides exactly the class of problem the sandbox will hit.

The default CNI is **disabled**. kindnet implements no policy engine: `kubectl apply`
succeeds, `kubectl get networkpolicy` lists the object, `kubectl describe` shows the rules,
and every packet still flows. A default-deny posture that is wide open is indistinguishable
from one that works, in precisely the places anyone would check. `kind-up` installs Calico
and aborts rather than degrading quietly; `CNI=kindnet` is available and says loudly what it
costs.

`make kind-deploy` exists because ArgoCD reads from a git server and cannot read a working
directory. It installs the same charts with the same values files, directly. That is not a
workaround for GitOps — it is the faster path to finding a broken manifest, because a Helm
error appears immediately and a sync failure appears in a UI two minutes later.

---

## 6. What validates this

`make helm-lint` → `scripts/helm-validate.sh`, also a required CI job.

`helm lint` alone is close to worthless: it checks that a chart is well-formed, not that it
produces valid Kubernetes objects. A template rendering `replicas: "3"` as a string lints
perfectly and fails at apply time. So the suite renders all seven services in both
environments and schema-checks every object with `kubeconform -strict`.

Then it asserts four things no schema can express:

1. No release contains both an HPA and a hardcoded `spec.replicas`.
2. No PDB has `minAvailable` greater than or equal to its replica count.
3. The two Redis tiers resolve to different hosts. Pointing the celebrity cache at the
   ordinary one produces no error and no symptom — just the silent loss of the isolation
   the whole design exists for.
4. The stream consumers have one replica and no HPA.

And three negative checks, that the guards still fire: `image.tag=latest` is rejected,
`dev-infra` refuses a non-dev tier, and the app-of-apps refuses an empty `repoURL`.

Every one of those seven was verified by deliberately breaking it and confirming the suite
goes red. An assertion nobody has seen fail is an assertion nobody should trust — see
`docs/16-gap-register.md` §8, where a safety guard that lint-passed and protected nothing
was found exactly this way.
