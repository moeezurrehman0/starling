# ADR-0007 — PostgreSQL full-text search, not OpenSearch

- **Status:** Accepted — **amended 2026-09-23**
- **Date:** 2026-09-23

> **Amendment.** The decision is unchanged — PostgreSQL FTS, on cost and sandbox
> availability grounds that still hold. What changed is PostgreSQL's role. After
> [ADR-0011](0011-dynamodb-operational-datastore.md) it is *only* a search index, fed
> asynchronously from the `tweets` DynamoDB stream rather than sharing a transaction with
> the write. **The "transactionally consistent, no indexing lag" claim in the original
> consequences is therefore retracted** and replaced below. This is the single largest
> cost of the DynamoDB move, and it should not be glossed over: search now has a
> reindexing pipeline, exactly the thing this record originally congratulated itself on
> avoiding.

## Context

The product needs search over tweet bodies, user handles and hashtags. The obvious
production answer is a dedicated search engine.

The smallest viable Amazon OpenSearch Service domain costs roughly $90–100/month, which
is around 40% of the entire production budget for this project, and OpenSearch does not
appear in the KodeKloud playground's documented service list — so it could not be
demonstrated in the sandbox tier either.

## Decision

PostgreSQL full-text search, in a single `search` schema that holds **only derived data**:

- a GIN index on `to_tsvector('english', body)` for tweet text,
- `pg_trgm` trigram matching on `handle` and `display_name` for user lookup,
- hashtags extracted at index time into a `hashtags` table with a plain B-tree index,
  rather than parsed out of the text at query time.

The schema is populated by a **stream consumer** reading the `tweets` DynamoDB stream
([ADR-0012](0012-dynamodb-streams-event-transport.md)), not by the write path. Nothing in
it is authoritative; it can be dropped and rebuilt by scanning the DynamoDB tables, and
the rebuild procedure is a runbook rather than a theory.

Search lives behind `tweet-service`'s API, not in a shared library, so the
implementation can be replaced without any caller changing.

## Alternatives considered

**Amazon OpenSearch Service.** The right answer for real relevance ranking, fuzzy
matching, faceting and multi-language analysis. Rejected on cost and sandbox
availability. Deferred, not dismissed: it is listed in the gap register with the
`search` module boundary that would accommodate it.

**OpenSearch self-hosted in the cluster.** Removes the AWS cost, but a single-node
OpenSearch pod wants 2 GiB of heap minimum. Against a 9.6 GiB sandbox budget already
hosting ArgoCD, Prometheus, Grafana and Tempo, it does not fit.

**`LIKE '%term%'`.** No index can serve a leading wildcard; it degrades to a sequential
scan. Rejected.

**No search at all.** Considered seriously, since search is not what this project is
demonstrating. Rejected because search is a defining Twitter feature and PostgreSQL FTS
costs one index and roughly a day of work.

## Consequences

**Positive**

- No additional infrastructure, no additional cost, no additional failure domain.
- Works identically in all three tiers, so search is genuinely demonstrable in the
  sandbox.
- Because the schema holds only derived data, PostgreSQL is no longer on the critical
  write path. Losing it degrades search and nothing else — which is what makes a
  single-AZ `db.t3.micro` an honest choice rather than a hidden risk.

**Negative**

- **Search is eventually consistent.** A tweet is searchable when the stream consumer has
  indexed it, typically within a second or two, and not at all while the consumer is
  down. The original version of this record claimed the opposite; that claim died with
  [ADR-0011](0011-dynamodb-operational-datastore.md). Indexing lag is now a metric with
  an alert, and a full reindex is an operation someone has to run.
- **There is now a reindexing pipeline** — a second consumer of the stream, subject to
  the two-reader-per-shard ceiling noted in
  [ADR-0012](0012-dynamodb-streams-event-transport.md), with its own checkpoints, its own
  dead-letter handling and its own backlog alarm.
- Relevance ranking is crude. `ts_rank` is a long way from BM25 with field boosting.
- No fuzzy matching on tweet bodies, no synonyms, no meaningful multi-language support —
  the configuration is hardcoded to `english`.
- GIN index maintenance makes indexing writes more expensive than plain inserts, which
  bounds how quickly a full rebuild can run.
- This does not scale to the stated design target of 500k tweets/day indefinitely. At
  that volume a dedicated engine becomes necessary, and the honest statement is that this
  choice is right for the budget, not right for the scale target.

**Neutral**

- Extracting hashtags at index time rather than query time trades a little indexing work
  for a much cheaper and more accurate hashtag query, and makes trending topics a
  straightforward future addition.
