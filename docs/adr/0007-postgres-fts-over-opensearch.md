# ADR-0007 — PostgreSQL full-text search, not OpenSearch

- **Status:** Accepted
- **Date:** 2026-09-23

## Context

The product needs search over tweet bodies, user handles and hashtags. The obvious
production answer is a dedicated search engine.

The smallest viable Amazon OpenSearch Service domain costs roughly $90–100/month, which
is around 40% of the entire production budget for this project, and OpenSearch does not
appear in the KodeKloud playground's documented service list — so it could not be
demonstrated in the sandbox tier either.

## Decision

PostgreSQL full-text search:

- a GIN index on `to_tsvector('english', body)` for tweet text,
- `pg_trgm` trigram matching on `handle` and `display_name` for user lookup,
- hashtags extracted at write time into a `hashtags` table with a plain B-tree index,
  rather than parsed out of the text at query time.

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
- Search results are transactionally consistent with writes — there is no indexing lag
  and no reindexing pipeline to operate, which a separate engine would require.
- Works identically in all three tiers, so search is genuinely demonstrable in the
  sandbox.

**Negative**

- Relevance ranking is crude. `ts_rank` is a long way from BM25 with field boosting.
- No fuzzy matching on tweet bodies, no synonyms, no meaningful multi-language support —
  the configuration is hardcoded to `english`.
- Search queries compete for the same `db.t3.micro` as the write path. A GIN index scan
  during k6 load will be visible in Phase 11, which is informative but also a real
  contention risk.
- GIN index maintenance adds write cost to the hottest table in the system.
- This does not scale to the stated design target of 500k tweets/day indefinitely. At
  that volume a dedicated engine becomes necessary, and the honest statement is that this
  choice is right for the budget, not right for the scale target.

**Neutral**

- Extracting hashtags at write time rather than query time trades a little write work
  for a much cheaper and more accurate hashtag query, and makes trending topics a
  straightforward future addition.
