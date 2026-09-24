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
| **S** | sandbox — KodeKloud EKS, 180-minute sessions | **no** — prepare the command, hand it over. `make sandbox-plan` and `make sandbox-selftest` are the dry-run and offline equivalents, and are always allowed |
| **P** | production — written, never applied | validate only |

See [ADR-0009](docs/adr/0009-three-tier-environment-model.md).

## Safe-execution mode

**Allowed without asking:**

- `./gradlew` anything — build, test, `spotlessApply`, coverage
- `docker compose` and `kind` against the local tier
- `terraform fmt`, `validate`, `test`, `plan`, `tflint`, `checkov`
- any `*-selftest.sh`, `make sandbox-plan`, `make gap-verify`, `make diagrams` — all offline
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
- **Migrations are expand–contract**, for PostgreSQL *and* for DynamoDB attributes. Never
  write a destructive change in the same release as its replacement. See
  [ADR-0008](docs/adr/0008-expand-contract-migrations.md).
- **DynamoDB is the operational store, PostgreSQL is a search index only.** Nothing
  authoritative may be written to PostgreSQL. A GSI does not enforce uniqueness — use
  `TransactWriteItems` with `attribute_not_exists`. Counters are `ADD`, never
  read-modify-write. See [ADR-0011](docs/adr/0011-dynamodb-operational-datastore.md).
- **AWS SDK v2 only.** The `com.amazonaws` group is SDK v1, end of support December 2025,
  and must not appear on any classpath — including transitively via the KCL DynamoDB
  Streams adapter. See [ADR-0012](docs/adr/0012-dynamodb-streams-event-transport.md).
- **Stream consumers are idempotent.** Delivery is at-least-once and a crash between
  processing and checkpointing replays the batch.
- **No tier checks in domain code.** Differences live in Helm values and Terraform
  variables. If application code needs to know which tier it is in, the design is wrong.
- **Every architectural decision gets an ADR**, including the ones that turned out badly.
- **Any change that makes tiers diverge updates**
  [`docs/16-gap-register.md`](docs/16-gap-register.md) in the same PR.

## Agents that live in this repository

The rules above govern *you*, an agent working on the code. This section governs the two
agents the repository itself ships, under `tools/aiops/`: the Terraform risk commenter and
the alert triage agent. It is a contract, not an aspiration —
`scripts/aiops-selftest.sh` asserts the checkable parts of it and CI runs that self-test.

**The rule everything else follows from: an agent explains, it does not act.**

The failure mode of an agent with write access is not malice. It is doing something
reasonable, at the wrong moment, to the wrong environment, with a confidence nobody
calibrated. Turning evidence into prose is a narrow, checkable job. Deciding what to do
about it is not.

1. **Never applies.** Neither agent runs `terraform apply`, `kubectl apply/delete/scale`,
   `helm upgrade`, or any mutating AWS call. The commenter reads a plan file that already
   exists; the triage agent issues HTTP GETs to Prometheus and Loki and nothing else. This
   is asserted — the self-test greps `triage.py` for `subprocess`, `kubectl`, `boto3`,
   `os.system` and POST, and fails if any appears.
2. **Read-only credentials, or none.** The Terraform CI job has no AWS credentials at all.
   If Bedrock is ever enabled, its IRSA role is scoped to `bedrock:InvokeModel` and
   `bedrock:Converse` and nothing else.
3. **The model never decides severity.** Every judgement a reviewer might act on is made by
   deterministic code in `risk.py`, with fixtures and a two-sided self-test. The model is
   handed findings that are already ranked and told not to re-rank them. An LLM asked
   whether a change is dangerous answers confidently in both directions and differently
   tomorrow; that is not a control.
4. **The model never sees more than it needs.** It receives the computed findings as JSON —
   never the plan file, state, credentials, logs or source. Nothing in that payload is
   invisible to anyone who can read the pull request.
5. **An agent cannot fail the build.** The risk comment is advisory. Blocking a merge is the
   job of `tf-validate.sh`, `tf-test.sh`, Checkov and the test suites, which are
   deterministic and negative-tested. An advisory check that can block is one people learn
   to argue with instead of read.
6. **Unconfigured is a supported state, and it is disclosed.** `AIOPS_PROVIDER` defaults to
   `none`, which renders findings from a template; a provider that errors falls back to the
   same template. The comment says which happened, in the comment. A tool that silently
   produces less than it claims is the exact failure this project exists to catch.

| Variable | Default | Meaning |
|---|---|---|
| `AIOPS_PROVIDER` | `none` | `none`, `openai`, `github-models`, `bedrock` |
| `AIOPS_MODEL` | provider default | Model id |
| `AIOPS_API_KEY` | — | Required for the OpenAI-compatible providers |
| `AIOPS_BASE_URL` | GitHub Models | Any OpenAI-compatible endpoint |
| `AWS_REGION` | `us-east-1` | Bedrock only |

Moving from no model to Bedrock in Tier P is a change to these variables. No code changes.
See [`docs/07-aiops.md`](docs/07-aiops.md).

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
