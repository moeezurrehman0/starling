# ADR-0006 — Hybrid timeline fan-out

- **Status:** Accepted — **amended 2026-09-23**
- **Date:** 2026-09-23

> **Amendment.** The decision is unchanged: choose the fan-out strategy per author, on
> follower count. The *mechanism* changed when the datastore moved to DynamoDB
> ([ADR-0011](0011-dynamodb-operational-datastore.md)) and the cache tier split
> ([ADR-0013](0013-split-celebrity-normal-caches.md)). Fan-out now writes durable items
> into a `timelines` table rather than pushing onto a Redis list, and the celebrity read
> path is served from a dedicated cache in front of a GSI rather than from a SQL range
> scan. The pseudocode and consequences below reflect the amended mechanism; the
> alternatives section is unchanged because none of the rejections depended on the
> storage engine.

## Context

The home timeline is the product's core read path and, at the design target of
50,000 reads/s against 500 writes/s, its hardest engineering problem. Section 4 of
[`01-system-design.md`](../01-system-design.md) derives the numbers; this record captures
the decision.

With an average of 200 followers, 500 tweets/s implies 100,000 timeline insertions per
second if every tweet is pushed to every follower. But the distribution of follower
counts is extremely long-tailed: almost all accounts have a few hundred followers, while
a handful have millions.

That skew is what makes a single uniform strategy fail.

## Decision

Choose the strategy per author, at write time, on follower count.

```
CELEBRITY_THRESHOLD = 10_000

write path (fanout-worker, consuming the tweets stream):
    if author.followerCount < CELEBRITY_THRESHOLD:
        for each page of follows.gsi_followers where followeeId = authorId:
            BatchWriteItem timelines            # 25 items per call
                { userId: follower, tweetId, authorId, expiresAt: now + 7d }
    else:
        SADD celebrities {authorId}             # redis-celeb, no per-follower writes
        LPUSH celeb:tweets:{authorId} tweetId   # redis-celeb, LTRIM 0 199

read path (timeline-service):
    cached = GET tl:{userId}:p0                 # redis-main, 60 s TTL
    if miss:
        cached = Query timelines
                   KeyCondition  userId = :u
                   ScanIndexForward = false     # UUIDv7 ⇒ newest first
                   Limit N
    celebs = celebrities ∩ (Query follows where followerId = :u)
    recent = LRANGE celeb:tweets:{c} 0 N for each c in celebs     # redis-celeb
             # miss falls through to Query tweets.gsi_author
    return merge_by_id_desc(cached, recent)[0:N]
```

The merge sorts by UUIDv7, which is chronologically ordered (ADR-0005), so no timestamp
hydration is needed to order the two lists.

`users.followerCount` is an atomically incremented DynamoDB counter, so the threshold
check is a single-item `GetItem` rather than an aggregate on the hot write path.

The `timelines` table uses a 7-day TTL rather than the previous 800-entry `LTRIM`. Depth
is bounded by the query `Limit`, not by discarding data, so deep scroll is served by the
same `Query` with a `LastEvaluatedKey` instead of falling through to a slower path.

## Alternatives considered

**Pure fan-out on read.** Query the tweets of everyone the user follows and merge on
every request. Writes cost nothing. Rejected because a user following 500 accounts
triggers a 500-author scan on every timeline load; at 50,000 reads/s the database is the
bottleneck immediately, and caching is ineffective because every user's timeline is a
different query.

**Pure fan-out on write.** Reads become a single `LRANGE` — genuinely O(1). Rejected
because one celebrity post generates millions of Redis writes, saturating the worker and
the queue and delaying fan-out for every other user on the platform. The tail latency of
ordinary users becomes hostage to celebrity posting.

**Fan-out on write into a durable table rather than Redis.** Originally rejected on the
grounds that 100,000 row inserts/s is far beyond a `db.t3.micro` and the data is trivially
rebuildable, so durability buys nothing. **This rejection no longer holds and the
amendment reverses it**: DynamoDB absorbs that write rate without capacity planning, and
durability turned out to buy something real — it removes the sandbox compromise where the
only copy of every materialised timeline lived in an `emptyDir` Redis. What it costs is
money, quantified in [ADR-0011](0011-dynamodb-operational-datastore.md).

**Threshold at 1,000 instead of 10,000.** More accounts treated as celebrities, so fewer
fan-out writes but a larger read-time merge for most users. 10,000 was chosen because it
keeps the celebrity set small enough that the read-time `IN` list stays short for typical
users. The value is a configuration property precisely because it is a guess until
Phase 11 measures it.

## Consequences

**Positive**

- The pathological write amplification never occurs, regardless of how large an account
  grows.
- The overwhelming majority of reads are a single cache read, or one `Query` against a
  partition key.
- The read-time query for celebrity tweets is served from `redis-celeb`, falling through
  to `tweets.gsi_author` over a small author set
  ([ADR-0013](0013-split-celebrity-normal-caches.md)).
- Materialised timelines are durable, so cache loss now costs latency and DynamoDB read
  spend — not the timelines themselves.

**Negative**

- Two code paths, both of which must be tested, including the boundary where an account
  crosses the threshold. An account that crosses upward leaves stale entries in follower
  timelines, which age out of the 7-day TTL naturally; an account crossing downward has a
  gap until it starts being fanned out. Both are accepted and documented.
- Eventual consistency: a follower may see a tweet up to a few seconds late while the
  worker drains. Budgeted by the 5-second p99 freshness NFR and measured as fan-out lag.
- The merge adds CPU and a second data source to every read.
- Fan-out is now **metered**. Every follower write is a DynamoDB write request, so the
  200-follower average translates directly into a monthly bill in a way that `LPUSH`
  never did. The threshold is now a cost lever as well as a latency one.
- The threshold is a tuning parameter with no correct value known in advance.

**Neutral**

- The `celebrities` set is refreshed by a periodic job rather than maintained
  transactionally; brief staleness around the threshold is harmless.
- This is the decision the repository is most likely to be asked about in an interview,
  which is a reason to have written it down properly.
