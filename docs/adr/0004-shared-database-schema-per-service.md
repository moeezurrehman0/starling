# ADR-0004 — Shared PostgreSQL instance, one schema per service

- **Status:** **Superseded** by [ADR-0011](0011-dynamodb-operational-datastore.md)
- **Date:** 2026-09-23
- **Superseded:** 2026-09-23

> **Why this was superseded.** Two flaws. First, it claimed a write path it did not
> specify: 500 tweets/s sustained is ~43 million rows/day, which needs time-based
> partitioning and autovacuum tuning that appear nowhere in this record. Second, and
> decisively for this project, RDS `db.t3.micro` versus Aurora Serverless v2 is a
> sandbox-to-production gap that can only ever be *asserted*. DynamoDB is on the
> KodeKloud allow-list and runs identically in both tiers, which turns that assertion
> into a demonstration. Note in particular that the "DynamoDB for tweets" alternative
> below was rejected partly because PostgreSQL full-text search would then need a
> separate engine — the replacement decision resolves that by keeping PostgreSQL purely
> as an asynchronously-maintained search index.

## Context

Textbook microservice practice gives each service its own database. This project cannot
afford that literally: the sandbox allows one free-tier `db.t3.micro`, and the production
profile budgets a single Aurora Serverless v2 cluster at roughly $45/month. Three
separate instances would triple that and would not fit the sandbox at all.

At the same time, a shared database is the classic way microservices quietly become a
distributed monolith — one service joins another's tables, and independent deployability
is gone without anyone noticing.

## Decision

One PostgreSQL instance. One **schema** per owning service: `users`, `tweets`. Each
service connects with its own role, granted privileges only on its own schema.

Two rules make the compromise honest and are enforced, not merely documented:

1. **No cross-schema foreign keys, and no cross-schema joins.** A service reaches
   another service's data only through that service's HTTP API.
2. **Database roles enforce rule 1.** `tweet_service` has no `SELECT` on `users.*`. A
   cross-schema query does not produce a subtly coupled system — it produces a permission
   error in an integration test.

Each service owns its own Flyway migration history, versioned independently.

## Alternatives considered

**One database instance per service.** Correct at scale, unaffordable here, and
impossible in the sandbox. Deferred rather than rejected: rule 1 means any schema can be
lifted into its own instance by changing a connection string, with no query rewrites.

**One shared schema.** Rejected. It offers no ownership boundary and would make the
migration history a shared mutable resource across teams.

**Separate logical databases within one instance.** Nearly equivalent, and marginally
stronger isolation. Rejected because PostgreSQL cannot query across databases at all,
which sounds like an advantage, but it also breaks a shared connection pool and
complicates the single-container local setup for no practical gain over
role-restricted schemas.

**DynamoDB for tweets.** Tempting for the write path, and the fan-out pattern suits it.
Rejected because the timeline merge in ADR-0006 needs a range query over
`(author_id, created_at)` for celebrity tweets, and PostgreSQL full-text search
(ADR-0007) would then need a separate engine, adding cost this project is avoiding.

## Consequences

**Positive**

- Fits the sandbox's single free-tier instance and the production cost ceiling.
- Role-based grants turn an architectural rule into a mechanical one — violations fail
  tests rather than surviving review.
- Each schema remains independently extractable.
- One instance to back up, monitor and migrate.

**Negative**

- A shared instance is a shared failure domain: one service's runaway query degrades
  every other service. Mitigated by per-role `statement_timeout` and connection-pool
  caps per service, and monitored by a per-role connection-count metric.
- Noisy-neighbour contention on `db.t3.micro` under k6 load is expected and will be
  visible in Phase 11. That is worth seeing rather than hiding.
- It is a genuine deviation from microservice orthodoxy, and reviewers will notice. The
  defence is rule 1 plus its enforcement — not the claim that the compromise is free.

**Neutral**

- The `outbox` table lives in the `tweets` schema, because atomicity with the tweet
  insert is the entire point (ADR-0003).
