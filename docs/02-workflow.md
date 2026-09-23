# 02 — Workflow: how code reaches production

This document traces a single change from a developer's laptop to a running pod under
observation. It is the companion to [`01-system-design.md`](01-system-design.md): that
one describes what is built, this one describes how it ships.

See [`diagrams/pipeline.mmd`](diagrams/pipeline.mmd).

---

## 1. Principles

Five rules that the rest of this document is merely the implementation of.

1. **Git is the only source of truth.** Nothing reaches a cluster except by a commit.
   There is no `kubectl apply` in any workflow, and no console click that survives.
2. **Build once, promote the artefact.** The image that runs in production is
   byte-identical to the one that passed CI. Environments differ by configuration, never
   by rebuild.
3. **Every gate is blocking or it is not a gate.** A warning nobody has to act on is
   noise. Non-blocking checks exist, and are labelled as reports.
4. **Rollback is a first-class path, not an emergency improvisation.** It is rehearsed in
   Phase 11 and timed.
5. **The pipeline is identical in all three tiers.** Tier differences live in Terraform
   variables and Helm values, never in workflow logic.

---

## 2. Branching and commits

Trunk-based. `main` is always releasable and always protected.

- Short-lived `feat/`, `fix/`, `chore/` branches, merged by squash.
- **Conventional Commits**, enforced by a CI check on the PR title. The type drives the
  version bump; `feat!` or a `BREAKING CHANGE:` footer drives a major.
- **release-please** maintains a release PR with the changelog and version derived from
  the commit history. Merging it tags the release. No human writes a version number.
- `main` requires: passing checks, one approving review, up-to-date branch, signed
  commits, no force-push, and `CODEOWNERS` approval for `deploy/`, `infra/` and
  `.github/`.

Pre-commit hooks run Spotless and gitleaks locally, so the fastest feedback is the one
that costs nothing.

---

## 3. CI on a pull request

A `changes` job (`dorny/paths-filter`) determines which services a PR touches and feeds a
matrix into the reusable `service-ci.yml` workflow. A one-service change does not pay for
six builds.

| Stage | Tool | Gate |
|-------|------|------|
| Format and lint | Spotless, Checkstyle, SpotBugs, ESLint | blocking |
| Unit tests | JUnit 5 / vitest | blocking |
| Integration tests | **Testcontainers** — LocalStack (DynamoDB + Streams + S3), real PostgreSQL, real Redis | blocking |
| Coverage | JaCoCo → PR comment | blocking below 70% |
| SAST | CodeQL | blocking on high |
| Secret scan | gitleaks, full history | blocking |
| Dependency audit | Dependabot, `dependencyCheck` | report only |
| Image build | Buildx with GHA layer cache | blocking |
| SBOM | Syft → CycloneDX artefact | report only |
| Image scan | Trivy | blocking on **fixable** HIGH/CRITICAL |
| IaC | `terraform fmt`, `validate`, tflint, **Checkov** | blocking |
| IaC tests | `terraform test` with `mock_provider` | blocking |
| Plan | `terraform plan` per environment → PR comment | blocking on error |
| Cost | **Infracost** diff → PR comment | report only |

Two choices worth defending:

**Trivy gates on fixable findings only.** An unfixable CVE in a base image is
information, not a decision — blocking on it teaches the team to bypass the gate, which
is worse than not having it.

**Integration tests use Testcontainers, not mocks.** A mocked repository test passes
against a schema that does not exist. Tests run against LocalStack for DynamoDB and its
streams, and against real PostgreSQL for the search index — which is also how the Flyway
migrations get exercised before they touch a cluster. Running the stream consumers
against a real stream matters more than the relational coverage does: shard handling is
the part of this system most likely to be subtly wrong
([ADR-0012](adr/0012-dynamodb-streams-event-transport.md)).

The 70% coverage threshold is deliberately modest. It is a floor against untested
additions, not a target to game.

---

## 4. Merge to main

1. Build and push to **ECR**, tagged `sha-<short>`. Never `latest` — a mutable tag makes
   "what is running?" unanswerable.
2. **cosign keyless signing** via the workflow's OIDC identity. The signature is bound to
   the repository and workflow that produced it, with no key to leak or rotate.
3. A bot commit bumps the image tag in `deploy/envs/dev/`.

That third step is the entire handoff. CI's responsibility ends at a commit; it has no
cluster credentials and no `kubectl`. A compromised workflow can push an image and open a
PR — it cannot deploy.

Playwright end-to-end tests run against docker compose on `main` only. They are slow and
would tax every PR for little marginal signal.

---

## 5. CD — ArgoCD

App-of-apps: one root `Application` renders the per-service `Application`s, so adding a
service is a file, not a cluster operation.

**`dev`** — auto-sync with self-heal and prune. Manual drift is reverted within minutes,
which is the point: the cluster is not a place where state is authored.

**`prod`** — sync only from a merged **promotion PR** that moves a tag from
`envs/dev` to `envs/prod`. That PR is the deployment record: it shows exactly which image
digest is being promoted, who approved it, and what changed since the last promotion.
In Tier P a GitHub Environment approval gate sits in front of it.

Both namespaces run on kind (Tier L) and on real EKS (Tier S). The 180-minute session is
comfortably enough for ArgoCD to install, bootstrap and sync — so GitOps is demonstrated
on AWS, not merely locally.

### Release strategy

`gateway` and `tweet-service` deploy through an **Argo Rollouts canary**: 25% → 50% →
100%, with an analysis template querying Prometheus between steps. An error-rate SLO
breach aborts and reverts automatically, without a human in the loop. The remaining
services use a rolling update with a `maxSurge` of 1 and `maxUnavailable` of 0.

The automatic abort is rehearsed in Phase 11 by deploying a deliberately broken build and
timing the recovery.

### Migrations

Two kinds, one discipline.

**PostgreSQL (`search` schema only).** Flyway, expand–contract, as a Helm `pre-upgrade`
hook Job — never on application startup, where replicas would race.

**DynamoDB.** No schema, but the same hazard: during a canary two versions of a service
read and write the same items. New attributes are additive, readers tolerate absence,
removals happen two releases later, and backfills are separate idempotent resumable jobs
rather than deployment hooks. A partition-key change is not a migration at all — it is a
new table and a cutover.

Backward compatibility with the preceding release is a hard rule in both cases, which is
what makes a code rollback safe without a data restore. See
[ADR-0008](adr/0008-expand-contract-migrations.md), which is honest about how much
smaller the relational half of this story became after
[ADR-0011](adr/0011-dynamodb-operational-datastore.md).

### Rollback

Two paths, both rehearsed:

- **Fast** — `argocd app rollback` to the previous synced revision. Seconds, but leaves
  Git and the cluster disagreeing until the revert lands.
- **Correct** — revert the promotion PR. Slower, but the cluster and Git stay consistent,
  which matters because self-heal will otherwise fight the fast path.

Use the fast path to stop the bleeding, then immediately open the revert.

---

## 6. Secrets

| Tier | Mechanism |
|------|-----------|
| L, S | Kubernetes `Secret` seeded by `tools/seed-secrets.sh` from a gitignored `.env` |
| S (if the probe permits) | External Secrets Operator + Secrets Manager via IRSA |
| P | Secrets Manager + ESO with rotation |

The Helm chart renders an `ExternalSecret` *or* a plain `Secret` behind a single values
flag. The application reads the same mounted keys regardless, so no tier awareness
reaches the code.

---

## 7. How CI authenticates to AWS

This is the one place the sandbox forces a genuine compromise.

GitHub OIDC federation needs an IAM OIDC provider and roles that **persist between
runs**. The playground wipes IAM at the end of every session, so it cannot work there.

- **Tier S** is driven from a laptop with the session's temporary credentials, via
  `make sandbox-up`. CI's only AWS interaction is pushing images to ECR at session start.
- **Tier P** contains the OIDC provider and scoped plan/apply roles, with the trust
  policy `sub` pinned to this repository and environment. They are written,
  Checkov-scanned and asserted by `terraform test` — and never applied.

Recorded as row 1 of the [gap register](16-gap-register.md).

---

## 8. Observability closes the loop

A deploy is not finished when the pods are `Ready`. It is finished when the golden
signals say it is healthy.

- **Metrics** — Prometheus scrapes Micrometer; Grafana dashboards per service; SLO
  burn-rate alerts. The same query that drives the Grafana panel drives the canary's
  automatic abort, so the dashboard and the release gate cannot disagree.
- **Traces** — OpenTelemetry auto-instrumentation → Tempo. The originating trace ID is
  written as an attribute on the DynamoDB item, so the stream record carries it and an
  asynchronous fan-out links back to the request that caused it.
- **Logs** — structured JSON with the trace ID in every line. Loki on Tier L; Fluent Bit
  → CloudWatch in Tier P. Tier S has no log aggregation: it does not fit in 9.6 GiB
  alongside Tempo, which is gap-register row 15.

---

## 9. The 180-minute sandbox session

Tier S is destroyed at the end of every session, which turns operational discipline from
a virtue into a requirement. Every step is a `make` target; nothing is a click-path.

| Minutes | Activity |
|---------|----------|
| 0–3 | `make sandbox-up` — EKS and RDS applies start in parallel |
| 3–15 | EKS control plane provisioning — the long pole |
| 15–20 | node group and add-ons |
| 20–28 | ArgoCD install and app-of-apps bootstrap |
| 28–35 | application synced and healthy |
| 35–45 | Prometheus, Grafana, Tempo |
| 45–150 | **demo window** — canary, abort drill, HPA under k6, promotion PR, rollback |
| 150–170 | `make sandbox-down` |
| 170–180 | buffer |

`make sandbox-status` reports elapsed session time alongside cluster health, because the
deadline is the binding constraint and a forgotten clock means a failed teardown.

---

## 10. Related

- [`01-system-design.md`](01-system-design.md) — what is being built
- [`16-gap-register.md`](16-gap-register.md) — where the tiers diverge, and why
- [ADR-0008](adr/0008-expand-contract-migrations.md) — migration safety
- [ADR-0009](adr/0009-three-tier-environment-model.md) — the tier model
- [ADR-0010](adr/0010-jlink-distroless-base-image.md) — the artefact CI produces
