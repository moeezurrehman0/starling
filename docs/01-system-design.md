# 01 — System Design

> Part 1 of the series. No code yet. This document decides *what* we are building and
> *why* the architecture looks the way it does, so that every later phase has something
> to refer back to.

---

## 1. Product requirements

### 1.1 Functional scope

| # | Feature | In scope | Notes |
|---|---------|----------|-------|
| 1 | Sign-up, login, JWT sessions | ✅ | own auth, RS256, not Cognito |
| 2 | User profile: handle, display name, bio, avatar, counts | ✅ | |
| 3 | Post a tweet, ≤280 characters | ✅ | |
| 4 | Media upload (image) | ✅ | presigned S3 PUT direct from browser |
| 5 | Follow / unfollow | ✅ | |
| 6 | Home timeline | ✅ | hybrid fan-out — see §4 |
| 7 | Like, retweet, reply | ✅ | replies one level deep, no deep threading |
| 8 | Search users and hashtags | ✅ | PostgreSQL full-text search |
| 9 | Notifications | ✅ | in-app only, no push or email |
| 10 | Rate limiting | ✅ | Redis token bucket at the gateway |

### 1.2 Explicit non-goals

Direct messages, trending topics, mobile apps, content moderation, advertising,
analytics warehousing, internationalisation, real-time WebSocket updates, verified
badges, outbound email.

Each of these was cut for a stated reason, recorded in the relevant ADR. The most
significant is **direct messages** — see [ADR-0001](adr/0001-service-decomposition.md).

### 1.3 Non-functional requirements

| Attribute | Target | How it is verified |
|-----------|--------|--------------------|
| Availability | 99.5% monthly for read paths | SLO with multi-window burn-rate alerts |
| Latency | p95 < 300 ms for `GET /timeline` | k6 load test, Prometheus histogram |
| Latency | p95 < 500 ms for `POST /tweets` | k6 |
| Timeline freshness | new tweet visible to followers within 5 s (p99) | fan-out lag metric |
| Durability | no acknowledged tweet may be lost | the write *is* the event — DynamoDB Streams, at-least-once |
| Security | no plaintext secrets, non-root containers, least privilege | Trivy, Checkov, gitleaks, PSS `restricted` |
| Recoverability | rollback of any release without data loss | expand-contract migrations + rehearsed drill |

### 1.4 Scale target (design for, do not provision for)

| Metric | Value | Derivation |
|--------|-------|------------|
| Daily active users | 100,000 | chosen target |
| Tweets per day | 500,000 | 5 posts per active user |
| Tweet writes, average | ~6/s | 500k / 86,400 |
| Tweet writes, peak | ~500/s | ~80× average, allowing for event spikes |
| Timeline reads, peak | ~50,000/s | 100:1 read:write ratio |
| Average followers | 200 | |
| Fan-out writes at peak | ~100,000/s | 500 tweets/s × 200 followers |
| Media stored per year | ~15 TB | 20% of tweets carry a 150 KB image |

**The fan-out number is the design driver.** 100k writes/s into a per-follower structure
is what rules out naive approaches and motivates §4.

We design the architecture for this. We *provision* for roughly 1/1000th of it, because
the runtime is a 3-node sandbox. That gap is deliberate and documented.

---

## 2. Architecture overview

Four services, one asynchronous worker, one web frontend.

See [`diagrams/c4-container.mmd`](diagrams/c4-container.mmd).

| Component | Responsibility | State |
|-----------|----------------|-------|
| `gateway` | TLS termination target, routing, JWT validation, rate limiting, request correlation IDs, API versioning | `redis-main` (rate-limit counters) |
| `user-service` | Accounts, credentials, profiles, the follow graph | DynamoDB `users`, `handles`, `follows` |
| `tweet-service` | Tweets, likes, retweets, replies, media metadata, search | DynamoDB `tweets`, `likes`, S3; PostgreSQL `search` |
| `timeline-service` | Home timeline reads, cache management, the hybrid merge | DynamoDB `timelines`; `redis-main` + `redis-celeb` |
| `fanout-worker` | Consumes the `tweets` stream, materialises follower timelines | DynamoDB `timelines`, `stream_checkpoints`; `redis-celeb` |
| `web` | Next.js UI, server-side rendered timeline | none |

**Why four and not one, and not twelve** — see
[ADR-0001](adr/0001-service-decomposition.md). Briefly: a monolith would give the
delivery pipeline nothing interesting to orchestrate, while twelve services would
multiply operational cost without teaching anything the fourth service has not already
taught. Four is the smallest number that produces a genuine service boundary, a genuine
asynchronous boundary, and independent deployability.

### 2.1 Communication

- **Synchronous: REST over HTTP/JSON.** gRPC is more efficient but makes debugging
  opaque in a repository whose purpose is partly pedagogical.
  See [ADR-0002](adr/0002-rest-over-grpc.md).
- **Asynchronous: the `tweets` DynamoDB stream.** There is no message broker and no
  outbox. A write to the table *is* the event, so no code path can accept a tweet and
  fail to publish it. See [ADR-0012](adr/0012-dynamodb-streams-event-transport.md).
  The stream also exists in Tier L (LocalStack) and Tier S exactly as it does in Tier P,
  which removes what used to be the design's largest portability unknown.

### 2.2 Request flow — posting a tweet

See [`diagrams/seq-post-tweet.mmd`](diagrams/seq-post-tweet.mmd).

1. Browser requests a presigned S3 PUT URL from `tweet-service` via `gateway`, uploads
   the image directly to S3. Media never transits a pod.
2. Browser `POST /api/v1/tweets` with text and the object key.
3. `gateway` validates the JWT, applies the rate limit, forwards the request.
4. `tweet-service` claims the `Idempotency-Key` and writes the tweet in one
   `TransactWriteItems`. That single write is the durability boundary.
5. DynamoDB Streams delivers the new item image to two independent consumers.
6. `fanout-worker` pages the author's followers from `follows.gsi_followers` and
   `BatchWriteItem`s a `timelines` entry for each — unless the author is a celebrity (§4).
7. The search indexer upserts `tweet_search` and `hashtags` in PostgreSQL.
8. `timeline-service` serves `GET /api/v1/timeline` from cache, falling through to the
   `timelines` table, and merges in celebrity tweets at read time.

Step 4 is what makes "no acknowledged tweet may be lost" true. The previous design needed
a transactional outbox to achieve it; here the guarantee is structural rather than
engineered.

---

## 3. Data model

**DynamoDB holds everything operational. PostgreSQL holds a search index and nothing
else.** See [ADR-0011](adr/0011-dynamodb-operational-datastore.md) for why, including
what it costs.

Every "most recent N" access pattern below is a `Query` with `ScanIndexForward=false` and
a `Limit`, because tweet IDs are UUIDv7 and therefore sort chronologically
([ADR-0005](adr/0005-uuidv7-identifiers.md)). No table needs a separate timestamp sort
key.

### 3.1 DynamoDB tables

| Table | PK | SK | Index | Notes |
|---|---|---|---|---|
| `users` | `userId` | — | `gsi_handle`: PK `handleLower` | `displayName`, `bio`, `avatarKey`, `passwordHash`, `followerCount`, `followingCount`, `tweetCount`, `isCelebrity` |
| `handles` | `handleLower` | — | — | uniqueness claim only |
| `tweets` | `tweetId` | — | `gsi_author`: PK `authorId`, SK `tweetId` | `body`, `mediaKey`, `replyToId`, `retweetOfId`, counters; **stream enabled** |
| `follows` | `followerId` | `followeeId` | `gsi_followers`: PK `followeeId`, SK `followerId` | forward edge for reads, reverse for fan-out |
| `timelines` | `userId` | `tweetId` | — | materialised home timeline; TTL `expiresAt` = +7 days |
| `likes` | `userId` | `tweetId` | `gsi_tweet`: PK `tweetId`, SK `userId` | |
| `idempotency` | `idempotencyKey` | — | — | TTL 24 h |
| `stream_checkpoints` | `shardId` | — | — | one item per consumer group per shard |

Three idioms carry most of the weight, and they are the parts a relational reader will
find unfamiliar:

- **Uniqueness is a transaction, not a constraint.** A GSI does not enforce it. Claiming
  `@handle` is a `TransactWriteItems` putting into `handles` with
  `attribute_not_exists(handleLower)` alongside the `users` put.
- **Counters are atomic server-side.** `UpdateItem … ADD followerCount 1`. The separate
  `user_stats` / `tweet_stats` tables of the previous design are gone, and so is the
  asynchronous job that maintained them.
- **Following is one transaction.** Put the `follows` edge with `attribute_not_exists`,
  increment both counters. Previously these were eventually consistent; now they are not.

Reads are eventually consistent by default. Strong consistency costs double and is used
in exactly two places: the idempotency check, and a user reading their own tweet
immediately after posting it.

### 3.2 PostgreSQL schema `search`

```
tweet_search   tweet_id (pk), author_id, body, created_at,
               tsv tsvector generated always as to_tsvector('english', body)
               gin index on tsv
user_search    user_id (pk), handle, display_name
               gin trgm indexes on handle, display_name
hashtags       tag, tweet_id, created_at, pk (tag, tweet_id)
```

Populated asynchronously by a stream consumer, never by the write path. It holds no data
that is not derivable from DynamoDB, so it can be dropped and rebuilt from a table scan.
That property is what makes a single-AZ `db.t3.micro` an honest choice rather than a
hidden single point of failure — and it is also why search is now eventually consistent,
which [ADR-0007](adr/0007-postgres-fts-over-opensearch.md) records as the cost.

### 3.3 Redis — two caches, not one

The cache tier is split by access pattern, because after the move to DynamoDB a cache
miss costs money and not merely milliseconds. See
[ADR-0013](adr/0013-split-celebrity-normal-caches.md).

```
redis-celeb   (256 Mi, maxmemory-policy noeviction, single-flight refill)
  celeb:profile:{userId}     profile JSON, TTL 5 min
  celeb:tweets:{userId}      LIST of recent tweet IDs, capped 200
  celebrities                SET of user IDs above CELEBRITY_THRESHOLD

redis-main    (512 Mi, maxmemory-policy allkeys-lru)
  tl:{userId}:p0             first timeline page, TTL 60 s
  profile:{userId}           profile JSON, TTL 5 min
  rl:{key}                   token bucket, TTL
  sess:{jti}                 revoked token markers
```

A celebrity profile is read by every timeline render that contains one of their tweets,
so its miss is amplified across thousands of concurrent requests against a single
DynamoDB partition key. A normal user's profile is read rarely. Sharing one instance
under LRU would let the second population evict the first — reclaiming precisely the keys
whose absence is most expensive.

Both caches hold only derived data. Losing either degrades latency and increases DynamoDB
spend; it loses nothing, because `timelines` is now durable. That is what finally makes
the `emptyDir` Redis in the sandbox as harmless as earlier versions of this document
claimed it was.

---

## 4. The timeline problem

This is the core design decision of the product, and the reason the architecture has an
asynchronous worker at all.

### 4.1 The two naive approaches

**Fan-out on read.** On `GET /timeline`, query the tweets of everyone the user follows
and merge. Writes are trivial. Reads are catastrophic: a user following 500 accounts
triggers a 500-way scan on every timeline load, at 50,000 reads/s.

**Fan-out on write.** When a tweet is posted, push its ID into a precomputed list for
every follower. Reads become a single `LRANGE` — O(1) and fast. But a celebrity with 10
million followers generates 10 million writes from one `POST`, which stalls the queue
and starves every other user's fan-out.

### 4.2 The hybrid

```
on tweet posted (fanout-worker, consuming the tweets stream):
    if author.followerCount < CELEBRITY_THRESHOLD (10,000):
        page follows.gsi_followers where followeeId = author        # fan-out on write
        BatchWriteItem timelines { userId: follower, tweetId, expiresAt: +7d }
    else:
        SADD celebrities {authorId}                                 # fan-out on read
        LPUSH celeb:tweets:{authorId} tweetId ; LTRIM 0 199

on timeline read for user U:
    cached   = GET tl:{U}:p0  ||  Query timelines where userId = U
                                        ScanIndexForward=false Limit N
    celebs   = celebrities ∩ (Query follows where followerId = U)   # small set
    recent   = LRANGE celeb:tweets:{c} 0 N  for each c in celebs
               # on miss: Query tweets.gsi_author where authorId = c
    return merge_sort_by_id(cached, recent)[0:N]
```

Because 99.9% of accounts are below the threshold, almost all reads are served from the
materialised list. Because celebrity accounts are few and their recent tweets live in a
dedicated cache, the read-time portion touches a small set of authors and rarely reaches
DynamoDB at all. The pathological write amplification never happens.

**Costs of the hybrid**, stated honestly: two code paths to test; a threshold that needs
tuning; a merge step on every read; and eventual consistency — a follower may see a
normal-author tweet up to a few seconds late while the worker drains. The 5-second
freshness NFR in §1.3 is the budget for that lag, and it is measured, not assumed.

One cost is new since the move to DynamoDB: **fan-out is metered.** Every follower entry
is a billed write request, so the 200-follower average turns directly into a monthly
figure — roughly $560/month provisioned at the design target. The threshold is now a cost
lever as well as a latency one, and the read cache is a cost-control mechanism as much as
a performance one. Both are quantified in
[ADR-0011](adr/0011-dynamodb-operational-datastore.md).

See [ADR-0006](adr/0006-hybrid-timeline-fanout.md).

---

## 5. Cross-cutting design

### 5.1 Authentication

`user-service` issues RS256 JWTs (15-minute access token, 7-day refresh token).
`gateway` validates signatures against a JWKS endpoint and caches the public key.
Downstream services trust `gateway` and read the authenticated subject from a header;
NetworkPolicies ensure they are unreachable except through it.

### 5.2 API versioning

Spring Boot 4's built-in versioning (`@RequestMapping(version = "1")`) is used from the
first commit, so the repository has a genuine backward-compatibility story rather than a
retrofitted one.

### 5.3 Rate limiting

Redis token bucket at `gateway`: 300 requests/min per authenticated user, 60/min per IP
for anonymous traffic, 10/min for `POST /tweets`. Returns `429` with `Retry-After`.

### 5.4 Idempotency

`POST /tweets` accepts an `Idempotency-Key` header. The key is claimed in the same
`TransactWriteItems` as the tweet, with `attribute_not_exists`, so a client retry after a
timeout cannot double-post even if the two requests race. The `idempotency` table expires
entries after 24 hours by TTL.

### 5.5 Search

PostgreSQL full-text search over `to_tsvector(body)` with a GIN index, plus trigram
matching on handles — in a `search` schema that holds only derived data and is fed
asynchronously from the `tweets` stream. Search is therefore **eventually consistent**,
typically within a second or two, and stale while the indexer is down. OpenSearch is the
right answer at real scale and is entirely absent here, for cost reasons. See
[ADR-0007](adr/0007-postgres-fts-over-opensearch.md).

### 5.6 Failure behaviour

| Failure | Behaviour |
|---------|-----------|
| `redis-main` unavailable | timelines fall through to the `timelines` table; rate limiting fails open; latency and DynamoDB spend rise |
| `redis-celeb` unavailable | celebrity reads fall through to `tweets.gsi_author`; a hot-partition throttle is likely under load — the worst of the cache failures |
| `fanout-worker` down | tweets still accepted; stream iterator age climbs; timelines go stale; **hard deadline at 24 h**, after which records expire and timelines must be rebuilt by scanning `tweets` |
| Search indexer down | new tweets are not findable; everything else is unaffected; a reindex job recovers it |
| `user-service` down | login and profile changes fail; timelines still serve |
| DynamoDB throttling | writes retry with backoff and jitter; sustained throttling fails `POST /tweets` with `503` and raises an alarm |
| PostgreSQL down | search returns `503`; every other path is unaffected |
| Poison stream record | after 3 attempts it is written to the `deadletter` table and skipped, so the shard advances; an alert fires |

Every row here becomes a runbook in `docs/runbooks/` and, where practical, a rehearsed
drill in Phase 11. Two of them — the 24-hour stream retention deadline and the
`redis-celeb` hot-partition case — are new consequences of the DynamoDB move and did not
exist in the previous design.

---

## 6. What is deliberately absent

| Omitted | Why | Where it would go |
|---------|-----|-------------------|
| Service mesh | NetworkPolicies plus OpenTelemetry meet the requirement; Istio would not fit in 6 vCPU alongside ArgoCD and Prometheus | Tier P, if mTLS between services became a requirement |
| Kafka / MSK | ~$150/month minimum; DynamoDB Streams already provides an ordered, durable, replayable log for the two consumers we have | Tier P, if a third consumer group or >24 h replay were needed — see [ADR-0012](adr/0012-dynamodb-streams-event-transport.md) |
| SNS / SQS | redundant once the table's own stream is the event source; it would only add a hop and a second at-least-once boundary | Tier P, as a relay if the two-reader-per-shard ceiling is ever reached |
| Lambda stream consumer | the idiomatic AWS answer, and available in the sandbox — but it breaks "one image, built once, promoted unchanged", which is a stated pipeline goal | Tier P, if the fan-out worker were ever decoupled from the service fleet |
| OpenSearch | ~$100/month; PostgreSQL FTS is sufficient at this scale | Tier P |
| Single-table DynamoDB design | no access pattern here needs a heterogeneous item collection, and it costs readability and per-table metrics | never, for this product |
| CQRS / event sourcing | the stream already is an append-only change log; formalising it buys nothing here | never, for this product |
| GraphQL | one client, well-known access patterns | never |
| Multi-region | doubles every cost for a scenario we cannot exercise; DynamoDB global tables would make it unusually cheap to add | Tier P, documented as accepted risk |

Recording what was *not* built, and why, is as much a part of the design as what was.

---

## 7. Where this design meets reality

The architecture above is sized for 100k DAU. The sandbox runs three `t3.medium` nodes
for 180 minutes. Nothing in this document is diluted to fit that — instead, every point
where the sandbox cannot express the design is recorded in
[`16-gap-register.md`](16-gap-register.md), together with the Terraform module,
`terraform test` assertion, or local demonstration that proves the production path is
real rather than imagined.

---

## Related

- [`02-workflow.md`](02-workflow.md) — how code reaches production
- [`16-gap-register.md`](16-gap-register.md) — sandbox vs. production
- [`adr/`](adr/) — the decisions above, each with its alternatives and consequences
