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
| [`docs/16-gap-register.md`](docs/16-gap-register.md) | Sandbox vs. production, row by row |
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
| 3 | jlink + CDS distroless base image | — | next |
| 4 | Services + web + compose stack | L | |
| 5 | CI pipeline | L | |
| 6 | Playground capability probe | S | |
| 7 | Helm charts, overlays, ArgoCD on kind | L | |
| 8 | Terraform sandbox profile, `make sandbox-up` | S | |
| 9 | Terraform production profile + `terraform test` | P | |
| 10 | Observability across tiers | L + S | |
| 11 | k6 load test, HPA, canary auto-abort, rollback drill | L + S | |
| 12 | AIOps: Terraform risk commenter, alert triage agent | L | |
| 13 | Gap register finalised | — | |
| 14 | Docs pass, 180-minute demo script | — | |

---

## Stack

| Layer | Choice |
|-------|--------|
| Backend | Java 25 (LTS) + Spring Boot 4.1.1 |
| Build | Gradle 9.7.1, Kotlin DSL, multi-project |
| Frontend | Next.js 15 + TypeScript + Tailwind |
| Images | jlink custom runtime + CDS → `distroless/base-nossl`, non-root |
| Data | PostgreSQL (schema-per-service), Redis, S3 |
| Runtime | Kubernetes / EKS, Helm + Kustomize |
| Delivery | GitHub Actions → ECR → ArgoCD → Argo Rollouts |
| IaC | Terraform, shared modules, two env roots |
| Observability | Prometheus, Grafana, Loki, OpenTelemetry, Tempo |

---

## Licence

TBD.
