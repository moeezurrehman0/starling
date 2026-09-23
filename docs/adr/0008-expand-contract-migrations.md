# ADR-0008 — Expand–contract database migrations

- **Status:** Accepted
- **Date:** 2026-09-23

## Context

The delivery pipeline promises that any release can be rolled back. Rolling deployments
and canary releases both mean that **two versions of a service run simultaneously against
one database** — during a canary, deliberately and for an extended period.

A migration that renames a column breaks this immediately. The old pods query the old
name, the new pods query the new one, and one of them is wrong from the moment the
migration runs. Rolling back the code then does not help, because the schema has already
moved.

## Decision

All schema changes follow the expand–contract pattern, spread across separate releases:

| Phase | Release | Action | Compatible with |
|-------|---------|--------|-----------------|
| **Expand** | N | add the new structure, nullable or defaulted; never remove or rename | old and new code |
| **Migrate** | N | backfill data; new code writes both old and new | old and new code |
| **Contract** | N+2 | drop the old structure, once no running or rollback-able version reads it | new code only |

Three rules are absolute:

1. **A migration is never destructive in the same release that introduces its
   replacement.** Renames become add-new, dual-write, backfill, drop-old.
2. **Every migration is backward compatible with the immediately preceding release.**
   This is what makes rollback safe without a database restore.
3. **Migrations run as a Helm `pre-upgrade` hook Job**, before any new pod starts, using
   the service's own database role. Never on application startup — five replicas racing
   to migrate is a corruption bug waiting to happen.

Flyway is the tool; each service owns its own migration history in its own schema.

## Alternatives considered

**Run migrations on application startup** (Flyway's `spring.flyway.enabled` default).
Simple, and common. Rejected because concurrent replicas race, and because a failed
migration then manifests as a crash-looping pod rather than a failed deployment with a
clear error.

**Take a maintenance window, stop all pods, migrate, start new pods.** Makes destructive
migrations safe. Rejected because it abandons zero-downtime deployment, which is a stated
goal, and because it makes rollback mean "restore a backup".

**Blue-green the database alongside the application.** Genuinely solves the problem, at
the cost of running two database instances and a replication and cut-over procedure.
Rejected on the same cost grounds as ADR-0004.

**Let the ORM manage the schema** (`hibernate.ddl-auto=update`). Rejected without
qualification: no review, no rollback, no version history, and silently destructive.
The setting is `validate` in every environment.

## Consequences

**Positive**

- Code rollback never requires a database rollback. The rehearsed drill in Phase 11
  reverts a promotion PR and the previous image runs unchanged against the current
  schema.
- Canary releases are safe: both versions of a service see a schema that satisfies them.
- Migration failures surface as a failed Helm hook before any new pod receives traffic.

**Negative**

- A column rename takes three releases instead of one. This is genuinely tedious, and it
  is the main reason teams abandon the discipline.
- The dual-write window means application code temporarily maintains two representations
  of the same data, which is a real source of bugs if the contract phase is forgotten.
- Someone must track outstanding contract migrations. A `docs/pending-contractions.md`
  checklist is maintained, and stale entries are treated as technical debt.
- Backfills on a large table must be batched to avoid long locks, which is extra work
  the naive migration does not need.

**Neutral**

- `NOT NULL` constraints on new columns are added in the contract phase, after the
  backfill completes, never in the expand phase.
- The Helm hook Job carries the same PodSecurity context and resource limits as the
  services, so it is not a hole in the security baseline.
