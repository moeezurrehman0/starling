# ADR-0006 — Hybrid timeline fan-out

- **Status:** Accepted
- **Date:** 2026-09-23

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

write path (fanout-worker):
    if author.follower_count < CELEBRITY_THRESHOLD:
        for each follower:  LPUSH timeline:{follower} tweetId; LTRIM 0 799
    else:
        SADD celebrities {authorId}      # no per-follower writes at all

read path (timeline-service):
    cached = LRANGE timeline:{userId} 0 N              # from normal authors
    celebs = celebrities ∩ following(userId)           # small set
    recent = SELECT id FROM tweets
             WHERE author_id = ANY(celebs)
               AND created_at > now() - interval '2 days'
             ORDER BY id DESC LIMIT N
    return merge_by_id_desc(cached, recent)[0:N]
```

The merge sorts by UUIDv7, which is chronologically ordered (ADR-0005), so no timestamp
hydration is needed to order the two lists.

`user_stats.follower_count` is a denormalised counter, so the threshold check is a
primary-key lookup rather than a `COUNT(*)` on the hot write path.

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

**Fan-out on write into PostgreSQL rather than Redis.** Durable, and survives cache loss.
Rejected: 100,000 row inserts per second into a `timeline_entries` table is far beyond a
`db.t3.micro`, and the data is trivially rebuildable, so durability buys nothing.

**Threshold at 1,000 instead of 10,000.** More accounts treated as celebrities, so fewer
fan-out writes but a larger read-time merge for most users. 10,000 was chosen because it
keeps the celebrity set small enough that the read-time `IN` list stays short for typical
users. The value is a configuration property precisely because it is a guess until
Phase 11 measures it.

## Consequences

**Positive**

- The pathological write amplification never occurs, regardless of how large an account
  grows.
- The overwhelming majority of reads are a single `LRANGE` from Redis.
- The read-time query for celebrity tweets is served by the existing
  `(author_id, created_at desc)` index over a small author set and a 2-day window.
- Redis holds only derived data, so an `emptyDir` Redis in the sandbox is acceptable —
  losing it degrades latency and nothing else.

**Negative**

- Two code paths, both of which must be tested, including the boundary where an account
  crosses the threshold. An account that crosses upward leaves stale entries in follower
  timelines, which age out of the 800-entry window naturally; an account crossing
  downward has a gap until it starts being fanned out. Both are accepted and documented.
- Eventual consistency: a follower may see a tweet up to a few seconds late while the
  worker drains. Budgeted by the 5-second p99 freshness NFR and measured as fan-out lag.
- The merge adds CPU and a second data source to every read.
- The 800-entry `LTRIM` cap means deep scrolling falls through to PostgreSQL. Acceptable:
  almost nobody scrolls past 800 tweets, and the fallback is correct, only slower.
- The threshold is a tuning parameter with no correct value known in advance.

**Neutral**

- The `celebrities` set is refreshed by a periodic job rather than maintained
  transactionally; brief staleness around the threshold is harmless.
- This is the decision the repository is most likely to be asked about in an interview,
  which is a reason to have written it down properly.
