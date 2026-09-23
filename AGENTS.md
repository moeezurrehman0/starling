# AGENTS.md — working agreement for AI agents

This repository is worked on with AI assistance. These rules exist so that help stays
help.

## What this project is

A Twitter clone used as the vehicle for a complete DevOps delivery system: GitHub
Actions, Terraform, EKS, ArgoCD and observability. **The product is the excuse; the
pipeline is the point.** When a choice trades product richness for delivery-system
clarity, take the delivery-system clarity.

Read [`README.md`](README.md), then [`docs/01-system-design.md`](docs/01-system-design.md)
and [`docs/02-workflow.md`](docs/02-workflow.md) before proposing changes.

## The three tiers

Every change targets one or more of:

| Tier | Meaning | Agents may run things here |
|------|---------|---------------------------|
| **L** | local — docker compose, kind | yes, freely |
| **S** | sandbox — KodeKloud EKS, 180-minute sessions | **no** — prepare the command, hand it over |
| **P** | production — written, never applied | validate only |

See [ADR-0009](docs/adr/0009-three-tier-environment-model.md).

## Safe-execution mode

**Allowed without asking:**

- `./gradlew` anything — build, test, `spotlessApply`, coverage
- `docker compose` and `kind` against the local tier
- `terraform fmt`, `validate`, `test`, `plan`, `tflint`, `checkov`
- reading, searching and editing files; opening pull requests

**Never, under any circumstances:**

- `terraform apply` or `terraform destroy` against AWS
- `kubectl apply`, `delete` or `argocd app sync` against a non-local cluster
- `aws` mutating commands
- pushing to `main`, force-pushing, or rewriting history
- committing a credential, token or `.env` file

For anything in the second list, print the exact command and stop. A human runs it.

## Conventions that are not negotiable

- **Spring Boot 4, not 3.** `spring-boot-starter-webmvc`, never `-web`. Jackson 3
  (`tools.jackson.*`). `@MockitoBean`, never the removed `@MockBean`. Most tutorials
  you have seen are for 3.x and are wrong here.
- **Testcontainers 2, not 1.** Artifacts are `org.testcontainers:testcontainers-<name>`,
  and containers moved out of `org.testcontainers.containers` into per-module packages
  (`org.testcontainers.postgresql.PostgreSQLContainer`). The 1.x classes still exist but
  are deprecated, and `-Werror` will reject them. The self-type generic parameter is gone.
- **Java 25** via the Gradle toolchain. Do not lower `languageVersion` to make something
  compile; fix the something.
- **Conventional Commits.** The PR title is linted. `feat:`, `fix:`, `chore:`,
  `docs:`, `refactor:`, `test:`, `ci:`, `build:`, with `!` for breaking.
- **Migrations are expand–contract.** Never write a destructive migration in the same
  release as its replacement. See [ADR-0008](docs/adr/0008-expand-contract-migrations.md).
- **No tier checks in domain code.** Differences live in Helm values and Terraform
  variables. If application code needs to know which tier it is in, the design is wrong.
- **Every architectural decision gets an ADR**, including the ones that turned out badly.
- **Any change that makes tiers diverge updates**
  [`docs/16-gap-register.md`](docs/16-gap-register.md) in the same PR.

## Before opening a pull request

```bash
./gradlew spotlessApply
./gradlew build          # lint, checkstyle, unit tests, 70% coverage gate
./gradlew integrationTest # Testcontainers; needs a running Docker daemon
```

Do not disable a failing gate to make a build pass. Either fix the code or argue, in the
PR, for why the gate is wrong — and then change the gate deliberately, in its own commit.

## Honesty requirements

This repository's most valuable artefact is its record of what was compromised and why.

- Do not describe something as working unless you have run it.
- Do not claim a production capability that is only written and never validated — say
  "written, validated by `terraform test`, never applied", which is what it is.
- If a constraint forced a worse design, write that down in the ADR's Consequences
  section rather than presenting the result as the ideal.
