# Twitter Clone — End-to-End DevOps on AWS

A Twitter-like product built as the vehicle for a complete software delivery system:
GitHub + GitHub Actions for SDLC and CI, Terraform for AWS infrastructure, Kubernetes
(EKS) as the runtime, ArgoCD for GitOps delivery, and a full observability stack.

Inspired by the structure of [devops-ai-playbook][playbook], but the system design,
product and infrastructure are original.

[playbook]: https://github.com/vishakhasadhwani/devops-ai-playbook

---

## The three-tier model

The AWS target is a **KodeKloud playground**: a 180-minute, disposable sandbox with hard
caps on instance size and count. That constraint is the most interesting thing about this
repo, so it is designed for explicitly rather than worked around.

| Tier | Where | Lifetime | Purpose |
|------|-------|----------|---------|
| **L — Local** | docker compose + kind | permanent | dev loop, all CI, full observability incl. Loki |
| **S — Sandbox** | KodeKloud AWS playground | 180 min, disposable | real EKS + RDS + ECR + ArgoCD + canary + k6, then destroyed |
| **P — Production** | code only, never applied | n/a | the design you'd actually ship — validated, never provisioned |

Tier P is not aspirational hand-waving. Every production resource is a written Terraform
module, asserted by `terraform test` with mocked providers, scanned by Checkov, and
costed by Infracost in CI — it is simply never `apply`-ed.

**→ [`docs/16-gap-register.md`](docs/16-gap-register.md) catalogues every difference
between what the sandbox permits and what production does, and names the artefact in
this repo that proves each production path.** It is the most useful document here.

---

## Documentation

| Doc | Contents |
|-----|----------|
| [`docs/01-system-design.md`](docs/01-system-design.md) | Requirements, scale targets, service decomposition, data model, timeline fan-out strategy |
| [`docs/02-workflow.md`](docs/02-workflow.md) | How code moves: developer → CI → registry → GitOps → cluster → observability |
| [`docs/03-deployment.md`](docs/03-deployment.md) | Helm charts, overlays, ArgoCD app-of-apps, PSS and NetworkPolicies |
| [`docs/04-infrastructure.md`](docs/04-infrastructure.md) | Terraform modules, the sandbox and production roots, and how the latter is validated without applying |
| [`docs/05-observability.md`](docs/05-observability.md) | Metrics, logs, traces, SLOs, alerts and the runbooks they link to |
| [`docs/06-load-and-delivery.md`](docs/06-load-and-delivery.md) | k6 load model, HPA scale-out, canary analysis, and the eight fail-open defects found proving it worked |
| [`docs/07-aiops.md`](docs/07-aiops.md) | Why the risk commenter's severities are decided by code and only its prose by a model |
| [`docs/16-gap-register.md`](docs/16-gap-register.md) | Sandbox vs. production, row by row — the headline deliverable |
| [`docs/adr/`](docs/adr/) | Architecture Decision Records |
| [`docs/diagrams/`](docs/diagrams/) | C4 context, C4 container, request-flow sequences, delivery pipeline |
| [`docs/runbooks/`](docs/runbooks/) | One runbook per alert |

---

## Status

Phases 1–2 of 14 complete. The build is real: five Spring Boot 4.1.1 services
compile and test on Java 25, with formatting, static analysis and a coverage gate
enforced. No business logic yet.

| # | Phase | Tier | State |
|---|-------|------|-------|
| 1 | System design, diagrams, ADRs, gap register | — | **done** |
| 2 | Monorepo scaffold, Gradle/Java 25/Boot 4.1.1, conventions | — | **done** |
| 3 | jlink + CDS distroless base image | — | **done** |
| 4 | Services + web + compose stack | L | **done** |
| 5 | CI pipeline | L | **done** |
| 6 | Playground capability probe | S | written and CI-gated; **awaits a session** |
| 7 | Helm charts, overlays, ArgoCD on kind | L | **done** |
| 8 | Terraform sandbox profile, `make sandbox-up` | S | written and validated; **apply awaits a session** |
| 9 | Terraform production profile + `terraform test` | P | **done** |
| 10 | Observability across tiers | L + S | **done** on L; EKS run awaits a session |
| 11 | k6 load test, HPA, canary auto-abort, rollback drill | L + S | **done** on L; EKS run awaits a session |
| 12 | AIOps: Terraform risk commenter, alert triage agent | L | **done** |
| 13 | Gap register finalised | — | **done** — enforced by `make gap-verify` |
| 14 | Docs pass, 180-minute demo script | — | next |

Anything marked *awaits a session* is written, tested and CI-gated; what is missing is a
180-minute KodeKloud window to run it against real AWS, not code.

---

## Stack

| Layer | Choice |
|-------|--------|
| Backend | Java 25 (LTS) + Spring Boot 4.1.1 |
| Build | Gradle 9.7.1, Kotlin DSL, multi-project |
| Frontend | Next.js 15 + TypeScript + Tailwind |
| Images | jlink custom runtime + CDS → `distroless/base-nossl`, non-root |
| Data | DynamoDB (operational) + PostgreSQL (search index), two Redis caches, S3 |
| Runtime | Kubernetes / EKS, Helm + Kustomize |
| Delivery | GitHub Actions → ECR → ArgoCD → Argo Rollouts |
| IaC | Terraform, shared modules, two env roots |
| Observability | Prometheus, Grafana, Loki, OpenTelemetry, Tempo |

---

## Licence

TBD.
