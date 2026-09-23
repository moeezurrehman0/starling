# ADR-0013 — Separate Redis caches for celebrity and normal-user data

- **Status:** Accepted
- **Date:** 2026-09-23

## Context

[ADR-0011](0011-dynamodb-operational-datastore.md) changed what the cache is *for*. With
PostgreSQL, Redis was a latency optimisation: a miss cost a few milliseconds. With
DynamoDB metered per read request, 50,000 timeline reads/s uncached is on the order of
**$1,000/day**. The cache hit rate is now a line item, and the cache tier is a
cost-control mechanism as much as a performance one.

That reframing makes a pre-existing problem important. The read distribution here is
extremely bimodal:

- **Celebrity data** — roughly 10,000 accounts. A tiny keyspace, but every timeline that
  contains a celebrity tweet must hydrate that author's profile, so these keys carry a
  disproportionate share of all reads and should approach a 100% hit rate.
- **Normal-user data** — 100,000+ cached timeline pages, profiles, rate-limit buckets and
  session markers. A large, churning keyspace where any individual key is read rarely.

Put both in one Redis under `allkeys-lru` and the second population evicts the first.
Normal-user churn is continuous and voluminous; celebrity keys are few and, between
bursts, momentarily cold. LRU will reclaim exactly the keys whose miss is most expensive.

A celebrity profile miss is not one DynamoDB read. It is a stampede of concurrent
requests against `gsi_author` — the one partition key where
[ADR-0011](0011-dynamodb-operational-datastore.md) already identified a 3,000 RCU
per-partition ceiling. The cheapest cache miss in the system to reason about is also the
most expensive one to suffer.

## Decision

Two separate Redis deployments, sized and configured for opposite access patterns.

| | `redis-celeb` | `redis-main` |
|---|---|---|
| Holds | `celeb:profile:{userId}`, `celeb:tweets:{userId}` (list, cap 200), `celebrities` (set) | `tl:{userId}:p0`, `profile:{userId}`, `rl:{key}`, `sess:{jti}` |
| Keyspace | ~10k accounts, bounded and predictable | 100k+ keys, unbounded and churning |
| `maxmemory-policy` | **`noeviction`** — expiry by explicit TTL only | **`allkeys-lru`** |
| Sandbox size | 256 Mi | 512 Mi |
| Production | ElastiCache `cache.t4g.micro` | ElastiCache sized from the Phase 11 measurement |
| Miss cost | stampede on a hot DynamoDB partition | one cheap `Query` |
| Refill | single-flight lock, so N concurrent misses cause one read | direct |

`noeviction` on the celebrity cache is deliberate: it is small enough to hold its entire
working set, so eviction would only ever be a symptom of something being wrong. Filling
it should raise an alarm, not silently degrade the hottest read path in the system. That
requires a memory-usage alarm at 80%, which is an obligation this decision creates.

Routing lives in one `CacheRouter` component that consults the `celebrities` set. It is a
**data-shape** decision, not an environment one, so it does not violate the rule that no
tier check may appear in domain code.

### What is deliberately *not* split

**The DynamoDB tables stay unified.** There is one `users` table, one `tweets` table, one
`timelines` table for everybody.

Celebrity status is **mutable**. Partitioning a store of record on a mutable attribute
means crossing the follower threshold becomes an online data migration between two
tables — which must be idempotent, reversible, and correct while reads and writes
continue. Today, crossing the threshold changes only which strategy
[ADR-0006](0006-hybrid-timeline-fanout.md) applies next; stale entries age out of the TTL
window harmlessly.

Splitting the *cache* costs nothing at the boundary, because a cache entry under the
wrong policy is a miss, not a corruption. Splitting the *store* would cost a migration.
The asymmetry is the whole argument.

## Alternatives considered

**One Redis with `allkeys-lru`.** The status quo, and simplest. Rejected for the eviction
interference above: the population whose miss is cheapest is the one that survives.

**One Redis, two logical databases (`SELECT 0` / `SELECT 1`).** Looks like isolation and
is not. `maxmemory` and `maxmemory-policy` are server-wide in Redis, so the two
populations still compete for the same memory under the same policy, and a single-threaded
process still serialises both workloads. It buys namespacing, which key prefixes already
provide. Rejected.

**Separate DynamoDB tables as well as separate caches** — the original request. Rejected
for the mutability argument above, and because DynamoDB's partition key already
distributes load and adaptive capacity absorbs hot partitions, so a separate table buys
far less than a separate cache does. Revisit if Phase 11 shows adaptive capacity failing
to keep up.

**An in-process Caffeine cache for celebrity profiles.** Genuinely attractive: no network
hop, nanosecond reads, and the celebrity working set is small enough to fit in every pod.
Deferred rather than rejected — it is the natural L1 *in front of* `redis-celeb`, not a
replacement for it, because N pods means N independent caches with N invalidation
problems and no shared TTL. Worth adding in Phase 11 if the measurement justifies it.

**Redis Cluster with hash-tag-based slot placement.** Correct at real scale. Rejected
here: cluster mode does not fit the sandbox, and it solves sharding, not eviction-policy
isolation, which is the actual problem.

## Consequences

**Positive**

- A bulkhead. A posting storm, a viral tweet or a rate-limit flood in `redis-main` cannot
  evict celebrity keys or consume the celebrity cache's CPU.
- Each population gets the eviction policy it actually wants, which is impossible in one
  instance.
- Celebrity cache hit rate becomes an explicit SLI with its own dashboard and alert,
  rather than being averaged into a global number that would hide it.
- It absorbs the `gsi_author` hot-partition risk that
  [ADR-0011](0011-dynamodb-operational-datastore.md) had to leave open. The two decisions
  cover each other's weakest point.
- Capacity can be reasoned about separately: the celebrity working set is bounded and
  calculable, the normal one is not.

**Negative**

- Two deployments, two connection pools, two dashboards, two alert sets, two runbooks.
  Real ongoing operational cost for a system this size.
- Roughly 256 Mi more in a sandbox already budgeting ~6.7 GiB of 9.6 GiB allocatable. It
  fits, with less headroom for the HPA scale-out demonstration than before.
- In production, two ElastiCache clusters instead of one — around double the cache line
  item, and two more things to patch and monitor.
- An account newly promoted to celebrity has a cold `redis-celeb` entry at exactly the
  moment its read volume spikes. Mitigated by the single-flight refill lock, but the
  first request still pays for it.
- `noeviction` means `redis-celeb` returns write errors rather than degrading if it fills
  unexpectedly — safer, but it converts a soft failure into a hard one and makes the
  memory alarm load-bearing.
- The `CacheRouter` is a new component on every read path, and a bug in it silently sends
  traffic to the wrong cache, where it will look like a mysterious hit-rate regression
  rather than an error.

**Neutral**

- Both instances run the same chart with different values, so the split adds
  configuration rather than code.
- `redis-main` losing all data is now purely a latency and cost event, because
  `timelines` is durable in DynamoDB ([ADR-0011](0011-dynamodb-operational-datastore.md)).
  The `emptyDir` compromise in Tier S is finally as harmless as it was always claimed
  to be.
- The threshold that decides which cache to use is the same
  `CELEBRITY_THRESHOLD` as [ADR-0006](0006-hybrid-timeline-fanout.md), so there is one
  tuning parameter, not two.
