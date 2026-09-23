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
| Durability | no acknowledged tweet may be lost | transactional outbox, at-least-once delivery |
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
| `gateway` | TLS termination target, routing, JWT validation, rate limiting, request correlation IDs, API versioning | Redis (rate-limit counters) |
| `user-service` | Accounts, credentials, profiles, the follow graph | PostgreSQL schema `users` |
| `tweet-service` | Tweets, likes, retweets, replies, media metadata, search | PostgreSQL schema `tweets`, S3 |
| `timeline-service` | Home timeline reads, timeline cache management | Redis, PostgreSQL (fallback reads) |
| `fanout-worker` | Consumes tweet-created events, writes into follower timelines | Redis (writes), consumes queue |
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
- **Asynchronous: one `EventPublisher` interface with two adapters** — SNS→SQS in
  production, a PostgreSQL transactional outbox where SNS/SQS is unavailable.
  See [ADR-0003](adr/0003-event-transport.md). This is the single most important
  portability decision in the codebase: tier differences never reach domain code.

### 2.2 Request flow — posting a tweet

See [`diagrams/seq-post-tweet.mmd`](diagrams/seq-post-tweet.mmd).

1. Browser requests a presigned S3 PUT URL from `tweet-service` via `gateway`, uploads
   the image directly to S3. Media never transits a pod.
2. Browser `POST /api/v1/tweets` with text and the object key.
3. `gateway` validates the JWT, applies the rate limit, forwards the request.
4. `tweet-service` writes the tweet **and** an outbox row in one database transaction.
5. The event reaches `fanout-worker` (via SQS, or via the outbox poller).
6. `fanout-worker` asks `user-service` for the author's followers, then pushes the tweet
   ID onto each follower's capped Redis list — unless the author is a celebrity (§4).
7. `timeline-service` serves `GET /api/v1/timeline` from Redis, merging in celebrity
   tweets at read time.

The transactional outbox in step 4 is what makes "no acknowledged tweet may be lost"
true: the tweet and the intent to publish it commit atomically.

---

## 3. Data model

One PostgreSQL database, one schema per service, no cross-schema foreign keys or joins.
Services reach each other's data only through APIs. See
[ADR-0004](adr/0004-shared-database-schema-per-service.md) — this is a deliberate
compromise, and the constraint that keeps it honest is the absence of cross-schema
references, which means each schema could be extracted to its own instance without
rewriting a query.

### 3.1 Schema `users`

```
users            id (uuid, pk), handle (citext, unique), display_name, bio,
                 avatar_key, password_hash, created_at
follows          follower_id (fk users), followee_id (fk users), created_at
                 pk (follower_id, followee_id)
                 index on (followee_id)          -- "who follows X", the fan-out query
user_stats       user_id (pk), follower_count, following_count, tweet_count
                 -- denormalised counters, updated asynchronously
```

`user_stats.follower_count` is what `fanout-worker` reads to decide whether an author is
a celebrity. Keeping it denormalised avoids a `COUNT(*)` on the hot write path.

### 3.2 Schema `tweets`

```
tweets           id (uuid v7, pk), author_id, body (varchar 280), media_key,
                 reply_to_id (nullable), retweet_of_id (nullable), created_at
                 index on (author_id, created_at desc)   -- profile timeline
                 gin index on to_tsvector(body)          -- search
likes            user_id, tweet_id, created_at, pk (user_id, tweet_id)
tweet_stats      tweet_id (pk), like_count, retweet_count, reply_count
outbox           id (bigserial), aggregate_id, event_type, payload (jsonb),
                 created_at, published_at (nullable)
                 index on (published_at) where published_at is null
```

**UUIDv7 for tweet IDs**, not UUIDv4: v7 is time-ordered, so it keeps B-tree inserts
sequential and makes `ORDER BY id` equivalent to chronological order. See
[ADR-0005](adr/0005-uuidv7-identifiers.md).

### 3.3 Redis keyspace

```
timeline:{user_id}       LIST of tweet IDs, LTRIM to 800    -- home timeline cache
ratelimit:{key}          token bucket, TTL                  -- gateway
session:{jti}            revoked token markers              -- logout
celebrities              SET of user IDs with >10k followers -- refreshed periodically
```

Redis holds only derived data. Losing Redis entirely degrades latency and forces
timeline rebuilds from PostgreSQL, but loses nothing durable — which is what makes the
`emptyDir` Redis acceptable in the sandbox tier.

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
on tweet posted:
    if author.follower_count < CELEBRITY_THRESHOLD (10,000):
        push tweet_id into timeline:{follower} for every follower   # fan-out on write
    else:
        do nothing                                                  # fan-out on read

on timeline read for user U:
    cached   = LRANGE timeline:{U} 0 N                 # precomputed, from normal authors
    celebs   = celebrity accounts that U follows       # small set, from Redis
    recent   = SELECT ... FROM tweets
               WHERE author_id IN (celebs) AND created_at > now() - interval '2 days'
    return merge_sort_by_time(cached, recent)[0:N]
```

Because 99.9% of accounts are below the threshold, almost all reads are served from the
precomputed list. Because celebrity accounts are few, the read-time query touches a small
set of authors and is well served by the `(author_id, created_at desc)` index. The
pathological write amplification never happens.

**Costs of the hybrid**, stated honestly: two code paths to test; a threshold that needs
tuning; a merge step on every read; and eventual consistency — a follower may see a
normal-author tweet up to a few seconds late while the worker drains. The 5-second
freshness NFR in §1.3 is the budget for that lag, and it is measured, not assumed.

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

`POST /tweets` accepts an `Idempotency-Key` header, stored with the tweet, so a client
retry after a timeout cannot double-post.

### 5.5 Search

PostgreSQL full-text search over `to_tsvector(body)` with a GIN index, plus trigram
matching on handles. OpenSearch is the right answer at real scale and is entirely absent
here, for cost reasons. See [ADR-0007](adr/0007-postgres-fts-over-opensearch.md).

### 5.6 Failure behaviour

| Failure | Behaviour |
|---------|-----------|
| Redis unavailable | timelines fall back to PostgreSQL; rate limiting fails open; latency degrades |
| `fanout-worker` down | tweets still accepted; outbox/queue backs up; timelines go stale; a backlog alert fires |
| `user-service` down | login and profile fail; timelines still serve from cache |
| PostgreSQL down | writes fail with `503`; cached timeline reads continue |
| Queue poison message | after 3 attempts the message moves to a DLQ; an alert fires; the worker continues |

Every row here becomes a runbook in `docs/runbooks/` and, where practical, a rehearsed
drill in Phase 11.

---

## 6. What is deliberately absent

| Omitted | Why | Where it would go |
|---------|-----|-------------------|
| Service mesh | NetworkPolicies plus OpenTelemetry meet the requirement; Istio would not fit in 6 vCPU alongside ArgoCD and Prometheus | Tier P, if mTLS between services became a requirement |
| Kafka / MSK | ~$150/month minimum for a lesson SQS teaches adequately | Tier P, if replay or multiple independent consumer groups were needed |
| OpenSearch | ~$100/month; PostgreSQL FTS is sufficient at this scale | Tier P |
| CQRS / event sourcing | the outbox provides durability without the operational weight | never, for this product |
| GraphQL | one client, well-known access patterns | never |
| Multi-region | doubles every cost for a scenario we cannot exercise | Tier P, documented as accepted risk |

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
