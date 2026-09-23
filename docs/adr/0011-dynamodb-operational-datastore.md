# ADR-0011 — DynamoDB as the operational datastore, PostgreSQL as a search index only

- **Status:** Accepted
- **Date:** 2026-09-23
- **Supersedes:** [ADR-0004](0004-shared-database-schema-per-service.md)

## Context

[ADR-0004](0004-shared-database-schema-per-service.md) chose one PostgreSQL instance with
a schema per service. Two things undermine that choice.

**The write path was never honestly sized.** The design target is 500 tweets/s at peak
and ~100,000 fan-out writes/s. Sustained, 500 writes/s is ~43 million rows/day into the
`tweets` table. PostgreSQL can do that, but only with time-based partitioning, autovacuum
tuning and a partition-maintenance job — none of which ADR-0004 specified. The decision
record claimed a capability the design did not actually contain.

**DynamoDB exists identically in both target environments.** This is the decisive point,
and it is specific to this project rather than a general argument. The KodeKloud
playground's allow-list names DynamoDB explicitly. Almost every other managed data
service this design wants — Aurora Serverless v2, ElastiCache, OpenSearch, MSK — is
absent, which is why [`16-gap-register.md`](../16-gap-register.md) exists. A datastore
that runs in Tier S exactly as it runs in Tier P converts an *asserted* production path
into a *demonstrated* one. Given that the gap register is this repository's headline
deliverable, closing a row is worth more here than it would be on a commercial project.

The counter-argument — that a relational model is easier to evolve and query — is real,
and is the reason PostgreSQL does not disappear entirely.

## Decision

All operational data moves to **DynamoDB**. PostgreSQL is retained for exactly one
purpose: a **full-text search index, maintained asynchronously** from DynamoDB Streams.

### Tables

Eight tables, not a single-table design (see alternatives).

| Table | PK | SK | Secondary index | Purpose |
|---|---|---|---|---|
| `users` | `userId` | — | `gsi_handle`: PK `handleLower` | profile, credentials, counters, `isCelebrity` |
| `handles` | `handleLower` | — | — | uniqueness claim for `@handle` |
| `tweets` | `tweetId` (UUIDv7) | — | `gsi_author`: PK `authorId`, SK `tweetId` | tweet bodies; profile and celebrity reads |
| `follows` | `followerId` | `followeeId` | `gsi_followers`: PK `followeeId`, SK `followerId` | forward edge for reads, reverse edge for fan-out |
| `timelines` | `userId` | `tweetId` | — | materialised home timeline, TTL 7 days |
| `likes` | `userId` | `tweetId` | `gsi_tweet`: PK `tweetId`, SK `userId` | both directions of "who liked what" |
| `idempotency` | `idempotencyKey` | — | — | `POST /tweets` replay suppression, TTL 24 h |
| `stream_checkpoints` | `shardId` | — | — | fan-out worker shard position ([ADR-0012](0012-dynamodb-streams-event-transport.md)) |

`tweets` has a stream enabled with `NEW_AND_OLD_IMAGES`.

Because tweet IDs are UUIDv7 and therefore lexicographically time-ordered
([ADR-0005](0005-uuidv7-identifiers.md)), every "most recent N" access pattern is a
`Query` with `ScanIndexForward=false` and a `Limit`. No sort key needs a separate
timestamp attribute, and no index needs a composite key.

### Idioms this forces, stated explicitly

- **Uniqueness.** A GSI does not enforce uniqueness. Claiming `@handle` is a
  `TransactWriteItems` of a `Put` into `handles` with `attribute_not_exists(handleLower)`
  plus a `Put` into `users`. Both succeed or neither does.
- **Counters.** `followerCount`, `likeCount` and friends are `UpdateItem … ADD`, which is
  atomic server-side. The separate `user_stats` and `tweet_stats` tables of ADR-0004 are
  gone, along with the asynchronous counter-update job.
- **Follow.** One `TransactWriteItems`: put the edge with `attribute_not_exists`,
  increment the followee's `followerCount`, increment the follower's `followingCount`.
  This is stronger than the ADR-0004 design, where counters were eventually consistent.
- **Timeline trimming.** A TTL attribute (`expiresAt`, 7 days) replaces Redis `LTRIM`.
  Depth is bounded by the query `Limit` rather than by destroying old entries, so deep
  scroll is served natively instead of falling through to a slower path.
- **Reads are eventually consistent by default.** Strongly consistent reads cost double
  and are used only where correctness requires them: the idempotency check, and reading
  a user's own tweet immediately after posting it.

### What stays in PostgreSQL

One schema, `search`, owned by `tweet-service`, populated by a stream consumer:

```
tweet_search   tweet_id, author_id, body, created_at,
               tsv tsvector generated, gin index on tsv
user_search    user_id, handle, display_name,  pg_trgm indexes
hashtags       tag, tweet_id, created_at,      btree on (tag, tweet_id desc)
```

It holds no data that is not derivable from DynamoDB. It can be dropped and rebuilt from
a table scan. That property is what makes losing it a degradation rather than an outage,
and it is why it is acceptable on a `db.t3.micro`.

## Alternatives considered

**Keep PostgreSQL for everything (ADR-0004), and add partitioning.** Entirely workable at
this scale, and cheaper. Rejected on the fidelity argument above: `db.t3.micro` versus
Aurora Serverless v2 multi-AZ is a gap that can only ever be asserted, and the write path
would need partition maintenance that adds operational surface the project does not
otherwise need.

**Single-table design.** The canonical DynamoDB answer, and it would collapse eight
tables into one with a generic `PK`/`SK`/`GSI1PK` shape. Rejected for two reasons. The
access patterns here do not form item collections that benefit — no request needs a
heterogeneous set of related items in one `Query`. And the resulting item shapes are
substantially harder to read, which matters in a repository whose secondary purpose is to
be understood by someone else. Per-table capacity, metrics and alarms are also a genuine
operational benefit that single-table gives up. The cost is more tables to provision and
no cross-entity transactional read; both are acceptable.

**DynamoDB plus OpenSearch, dropping PostgreSQL entirely.** Architecturally the cleanest
result, and it is what a real system would do. Rejected on the same grounds as
[ADR-0007](0007-postgres-fts-over-opensearch.md): ~$90–100/month, roughly 40% of the
entire production budget, and absent from the playground so it could not be demonstrated
in Tier S either.

**Aurora Serverless v2 for everything.** Solves the scaling concern properly and keeps
one datastore technology. Rejected because it is unavailable in the sandbox, so the
entire data layer would become an untestable Tier-P assertion — the exact failure mode
this project is built to avoid.

**DynamoDB for tweets and timelines only, leaving users and follows in PostgreSQL.**
Considered seriously. Rejected because it keeps both datastores on the hot path, so it
pays the operational cost of two technologies while closing only part of the gap, and
because the follow graph's reverse-edge query is the single hottest read in the system
and is precisely what a GSI is for.

## Consequences

**Positive**

- Gap-register row 6 narrows from "different engine, different topology" to a short list
  of configuration flags (PITR, CMK, deletion protection, capacity mode). The sandbox
  demonstration becomes a substantially honest production demonstration.
- `timelines` is durable. The old design kept the only copy of a materialised timeline in
  an `emptyDir` Redis; a pod restart silently lost it. Redis is now a cache in front of a
  durable store, which is what the failure-mode table always claimed it was.
- No capacity planning on the write path, and no partition-maintenance job.
- Atomic counters and transactional follows remove two eventually-consistent mechanisms
  and the bugs they would have produced.
- Single-digit-millisecond reads at any volume, with no vacuum, no bloat, no connection
  pool to size, and no failover.

**Negative**

- **It costs real money, and more than the design it replaces.** At the design target,
  fan-out is ~1,200 item writes/s on average: roughly **$560/month provisioned**, or
  ~$3,900/month on-demand. The PostgreSQL-plus-Redis design it replaces was a
  `cache.r7g.large` at ~$130/month plus the database. Order of magnitude, that is **4×
  the cost of the cache-only approach**, bought with durability and sandbox fidelity.
  Whether that is a good trade depends on whether losing every timeline on a cache
  restart is acceptable — for this project, the gap-register win decides it.
- **The read path would be ruinous uncached.** 50,000 timeline reads/s against DynamoDB
  is order $1,000/day on-demand. The Redis tier is therefore no longer only a latency
  optimisation — it is a **cost-control mechanism**, and its hit rate is a budget metric,
  not just a performance one. This directly motivates
  [ADR-0013](0013-split-celebrity-normal-caches.md).
- **Access patterns are frozen at design time.** This is the real long-term risk. A new
  query shape means a new GSI and a backfill, where PostgreSQL would have accepted an
  `ORDER BY` nobody anticipated. The eight tables above encode every access pattern the
  product has; adding a ninth is a migration, not an afternoon.
- **No joins, no aggregates, no ad-hoc queries.** "How many users joined last week" is
  not answerable without an export. Analytics is out of scope, which makes this tolerable
  rather than solved.
- **Search is now eventually consistent.** ADR-0007 claimed search results were
  transactionally consistent with writes because the index lived in the same database.
  That claim is now false and is retracted there. Indexing lag is a new metric with a new
  alert.
- **Two datastore technologies instead of one**, with two sets of credentials, two
  failure modes and two local-development dependencies.
- **Expand–contract migrations lose most of their subject matter.** See
  [ADR-0008](0008-expand-contract-migrations.md), amended accordingly. The discipline
  still applies — to the `search` schema, and to DynamoDB attributes — but the marquee
  demonstration is smaller than it was.
- A `t3.medium` node running LocalStack or DynamoDB Local for Tier L uses memory the
  previous PostgreSQL-only setup did not.

**Neutral**

- Hot-partition risk on `gsi_author` for celebrity authors is real — all of a celebrity's
  tweets share one partition key, against a 3,000 RCU per-partition ceiling. It is
  absorbed by the celebrity cache in [ADR-0013](0013-split-celebrity-normal-caches.md),
  which is a pleasing case of two decisions reinforcing each other rather than a
  coincidence.
- The `follows` reverse GSI puts a celebrity's entire follower list under one partition
  key. Harmless, because [ADR-0006](0006-hybrid-timeline-fanout.md) means that list is
  never enumerated for fan-out.
- Access via the AWS SDK v2 enhanced client with hand-written `TableSchema` definitions.
  Spring Data DynamoDB is not used: it is community-maintained and not verified against
  Spring Boot 4.
- Tier L uses LocalStack rather than `amazon/dynamodb-local`, because the same container
  also provides S3 and DynamoDB Streams.
