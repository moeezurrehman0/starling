# ADR-0014 — One environment overlay per tier, and a chart that refuses the wrong one

- **Status:** Accepted
- **Date:** 2026-10-08

## Context

[ADR-0009](0009-three-tier-environment-model.md) defines three tiers: **L** (docker
compose and kind), **S** (a 180-minute KodeKloud EKS session) and **P** (written,
validated, never applied). `deploy/envs/` contained two overlays: `dev` and `prod`.
Tier S borrowed `dev`.

That looked defensible when it was written. Tier S and Tier L are both non-production,
both run the same images, both want debug-friendly settings. `deploy/envs/dev/values.yaml`
even says so in its header: *"Tier L / Tier S shared values."* The bootstrap agreed —
`deploy/argocd/root.yaml` hardcoded `root-dev` with `env: dev`, and `scripts/sandbox-up.sh`
applied that same file.

Re-deriving the Tier-S checklist against [`../08-session-runbook.md`](../08-session-runbook.md),
before booking a session that cannot be repeated, found what sharing actually meant.

**Every `deploy/envs/dev/*.yaml` sets `DYNAMODB_ENDPOINT: http://localstack:4566` and
`SEARCH_DB_URL: jdbc:postgresql://postgres:5432/starling_search`.** On EKS, that resolves
to an in-cluster LocalStack pod and an in-cluster Postgres pod. The nine real DynamoDB
tables, the real RDS instance and the real S3 bucket that `sandbox-up` spends its first
twenty minutes creating would never have been touched.

The failure has no symptom. Every pod is Running. ArgoCD reports Synced and Healthy.
The product works end to end — you can post a tweet, follow an account, load a timeline.
The only observable difference is that the tables stay empty, which nobody looks at during
a demo of a working product. It would have been discovered, if at all, on the teardown
sweep at minute 150, by which point the session is over and not repeatable.

The giveaway was in the bootstrap all along. `sandbox-up.sh` writes **empty** AWS
credentials into `starling-secrets`, with a comment explaining that Tier S authenticates
through IRSA so the pod must not carry static keys. That is correct, and it is IRSA
pointed at a service that ignores credentials entirely. Two correct-looking things that
cannot both be true.

A second, smaller instance of the same root cause: `deploy/envs/dev/web.yaml` sets
`ingress.className: nginx`, `sandbox-up.sh` installs the AWS Load Balancer Controller, and
nothing anywhere installs nginx. The Ingress would have been admitted and would have sat
without an ADDRESS forever. Demo 1 — "the product works end to end" — has no URL. The ALB
controller installed at minutes 22–28 would have reconciled nothing.

Neither of these appears anywhere in [`../16-gap-register.md`](../16-gap-register.md),
because a gap register records what was found and both halves of the repository were
internally consistent. The register's own tier table states that Tier S uses real
DynamoDB, real RDS and in-cluster Redis. The manifests state otherwise. The two were
written months apart and never reconciled, because **nothing ever ran that could
disagree with either of them.** `make sandbox-plan` is a `terraform plan`; it validates
the infrastructure and never looks at a Helm value. `helm-validate.sh` rendered `dev` and
`prod` and asserted nothing about which AWS a rendered manifest points at.

## Decision

**Three tiers get three overlays.** `deploy/envs/sandbox/` is added as a first-class
environment, and Tier S stops borrowing Tier L's.

Four supporting changes, because an overlay on its own is a file somebody can forget:

1. **The bootstrap is parameterised, not duplicated.** `deploy/argocd/root.yaml` carries
   `ENV_PLACEHOLDER` in both the Application name and the `env` Helm parameter.
   `kind-up.sh` substitutes `dev`; `sandbox-up.sh` substitutes `sandbox`. Neither can
   apply the file unsubstituted and get something that works — the Application would be
   named `root-ENV_PLACEHOLDER` and point at an overlay directory that does not exist.
   That is a deliberate choice of a loud failure over a silent default.

2. **`dev-infra` becomes per-component, and the guard refuses the wrong combination.**
   The chart bundled Redis, Postgres and LocalStack behind one switch. Tier S needs Redis
   — the playground has no ElastiCache — and must not have the other two. The chart now
   takes `components.{redis,postgres,localstack}`, and its guard template **fails** if
   `tier=sandbox` is rendered with Postgres or LocalStack enabled. The app-of-apps
   *derives* those two from the tier rather than reading them from a value, so there is
   no flag to set correctly.

3. **Endpoints that are outputs of an apply are injected, not committed.** `SEARCH_DB_URL`
   and `DYNAMODB_TABLE_PREFIX` land in `starling-secrets` from `terraform output`. The
   sandbox overlay **omits** them rather than setting them empty. An earlier draft of
   this ADR justified that with a precedence claim -- that an explicit `env:` entry beats
   `envFrom` -- which is true of Kubernetes and irrelevant to this chart: overlay values
   render into a ConfigMap, and `deployment.yaml` lists the `secretRef` *after* the
   `configMapRef`, so for duplicate keys the injected Secret wins either way. The reason
   to omit is weaker and still sufficient: an empty string in Git is a statement that
   somebody configured this and chose nothing, and the next person to read it has to
   reconstruct the runtime override to find out otherwise.
   `TWEETS_STREAM_ARN` is deliberately *not* injected — a stream ARN embeds the table's
   creation timestamp, so a captured value is correct until the first teardown and then
   names a stream that no longer exists. The services discover it from the table instead.

4. **`allowCidrs` on the service chart.** `allowExternalEgress` excludes the link-local
   metadata address (so a pod cannot borrow the node instance role and defeat IRSA) and
   the three RFC1918 ranges. The KodeKloud playground's default VPC is `172.31.0.0/16`,
   inside `172.16.0.0/12` — so RDS sits in the excluded range and is unreachable through
   that rule. Widening `allowExternalEgress` would have punched a hole in the IRSA
   protection to fix a database connection; an explicit CIDR list does not.

And the part that makes it stick: **`helm-validate.sh` now renders `sandbox` alongside
`dev` and `prod`, and asserts that no non-`dev` environment renders `DYNAMODB_ENDPOINT`,
references `localstack`, points at `jdbc:postgresql://postgres:`, or asks for an ingress
class its tier does not install.** Those four greps are the only thing in the repository
that can see this class of mistake. Every object involved is individually valid, so a
schema check cannot; the resources are correct, so `terraform plan` cannot; the product
works, so a smoke test cannot.

## Consequences

Tier S now costs a real overlay to maintain. Eight more files that can drift from `dev`,
and the drift is the kind that does not fail — it just quietly means something different.
The four invariants above are what converts that from a hope into a check, and they are
the reason this ADR is worth more than the overlay is.

**The honest part.** None of this has been run. Tier S is one 180-minute session that has
not been booked, so what is claimed here is: the overlay renders, kubeconform accepts it,
the guard refuses the combinations it should refuse, and `terraform validate` passes on
the new output. Whether the product actually reaches DynamoDB over IRSA through a
NetworkPolicy with an explicit VPC CIDR is **unproven**, and is the first thing minute 45
tests.

It is also worth being plain that this was found by reading, not by running, and only
because a session had to be booked. Had Tier S been cheap and repeatable, it would have
been found on the first attempt in ten minutes. The scarcity of the environment is what
made the review necessary — and a review is a weaker control than an execution, which is
why the four invariants above exist rather than a note in the runbook saying "check the
overlay".

A residual gap, recorded rather than fixed: the ECR repositories the Terraform root
creates are unused. Images come from GHCR. Closing that means either pushing to ECR in
CI or deleting the repositories, and both are a larger change than this one.
