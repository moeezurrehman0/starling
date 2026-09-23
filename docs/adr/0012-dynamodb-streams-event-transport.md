# ADR-0012 — DynamoDB Streams as the event transport

- **Status:** Accepted
- **Date:** 2026-09-23
- **Supersedes:** [ADR-0003](0003-event-transport.md)

## Context

[ADR-0003](0003-event-transport.md) solved two problems at once: **durability** (a tweet
must never commit without its fan-out event) and **portability** (SNS/SQS is not on the
KodeKloud allow-list, so it might not exist in Tier S). Its answer was a transactional
outbox written inside the tweet's own database transaction, with two relay adapters.

Adopting DynamoDB ([ADR-0011](0011-dynamodb-operational-datastore.md)) dissolves the
first problem rather than solving it differently. **DynamoDB Streams is already an
ordered, durable, at-least-once log of committed writes to the table.** There is no
window in which a tweet commits and its event does not, because the event *is* the
commit. An outbox table alongside a stream would be a second copy of information the
stream already carries — dual bookkeeping, not dual-write protection.

The second problem also softens: a stream is a property of the table, so it exists
wherever the table exists. Nothing needs to be probed.

## Decision

The `tweets` table stream, configured `NEW_AND_OLD_IMAGES`, is the event transport.
The outbox table, its poller, its partial index and its pruning job are all removed.

Two independent consumer groups read it:

| Consumer | Does |
|---|---|
| `fanout-worker` | materialises `timelines` entries, or marks the author a celebrity |
| search indexer (inside `tweet-service`) | upserts `tweet_search` and `hashtags` in PostgreSQL |

Each maintains its own shard positions in the `stream_checkpoints` table, keyed by
`shardId`, so the two are independent and one falling behind does not affect the other.

### Consumption mechanism

A small polling consumer built directly on `DynamoDbStreamsClient`:
`DescribeStream` → `GetShardIterator` → `GetRecords`, checkpointing the last processed
sequence number per shard after the batch is handled.

**Why not the Kinesis Client Library.** The bridge, `dynamodb-streams-kinesis-adapter`,
is published only under the `com.amazonaws` group — the AWS SDK for Java **v1** lineage,
which reached end of support in December 2025. Pulling an end-of-support SDK into a Java
25 / Spring Boot 4 codebase to avoid roughly 300 lines of well-understood polling code is
a bad trade, and it would fail the supply-chain gate the pipeline already enforces. KCL
3.x additionally wants its own lease table and CloudWatch metrics, neither of which is
free in a 180-minute sandbox.

Handling is **idempotent**, keyed on the record's sequence number and the target item, so
at-least-once redelivery after a crash between processing and checkpointing is safe.

A record that fails three times is written to a `deadletter` table with its payload and
the exception, then skipped so the shard can advance. Head-of-line blocking on a poison
record would otherwise stall the whole shard.

## Alternatives considered

**Keep the outbox pattern on top of DynamoDB.** Write an outbox item in the same
`TransactWriteItems` as the tweet. This works, and it preserves ADR-0003 unchanged.
Rejected because the stream already guarantees exactly what the outbox was invented to
guarantee; keeping both means two logs, two pruning jobs and two places for the fan-out
to be lost.

**Lambda triggered by the stream.** The most idiomatic AWS answer, natively supported,
with retries and a failure destination built in — and Lambda *is* on the playground
allow-list (256 MB, 10 s). Rejected because the fan-out worker would then be a different
artefact with a different runtime, deployment model and observability story from the
other five services. "One image, built once, promoted unchanged" is a stated goal of the
delivery pipeline, and a function breaks it. Recorded in the gap register as the
production-idiomatic option deliberately not taken.

**EventBridge Pipes** (`DynamoDB Streams → Pipes → SQS`). Removes the polling code
entirely and is the cleanest managed answer. Rejected: EventBridge is not on the
playground allow-list, so this would be a Tier-P-only assertion, which is precisely the
thing this project exists to avoid.

**Kinesis Data Streams for DynamoDB.** Supports far more than two concurrent consumers
and integrates with KCL 3.x properly. Rejected: Kinesis is not on the allow-list, and it
adds a shard-hour cost for a consumer count we do not currently need.

**SNS → SQS, as in ADR-0003.** Still unproven in the sandbox, and now redundant: it would
be fed *from* the stream, adding a hop and a second at-least-once boundary to reach the
same worker.

## Consequences

**Positive**

- The dual-write problem is not mitigated — it is structurally impossible. There is no
  code path that can commit a tweet without emitting its event.
- One table, one stream, no outbox, no poller, no pruning job, no partial index. This is
  a net reduction in moving parts against ADR-0003.
- **Identical in Tier L, S and P.** LocalStack implements DynamoDB Streams, the sandbox
  has it because the table has it, and production is the same API. Gap-register row 8
  moves from "presumed unavailable, fallback in place" to no gap at all.
- Ordering is guaranteed per partition key, so all events for one tweet arrive in order
  without any sequencing logic.
- The Phase 6 capability probe has one fewer unknown to resolve.

**Negative**

- **24-hour retention, and that is a hard limit.** SQS offers 14 days. If both consumers
  are down for more than 24 hours, those events are gone permanently and timelines must
  be rebuilt by scanning `tweets`. The outbox had no such expiry. A "stream iterator age"
  alarm at 1 hour is mandatory rather than nice to have, and the rebuild procedure is a
  runbook, not a theory.
- **Maximum two simultaneous readers per shard**, and we have exactly two consumer
  groups. **We are at the documented ceiling.** A third consumer — analytics, a
  notification service, an audit log — cannot simply subscribe; it would force a move to
  Kinesis Data Streams for DynamoDB or a relay into SNS. This is the single most
  constraining consequence of the decision and it should be re-read before any new
  consumer is proposed.
- **Shard management is our code.** Shards split and merge as table throughput changes,
  and a consumer must discover children and drain parents before them. This is the most
  likely source of a subtle bug in the whole system, and it only manifests under load —
  which means Phase 11 must deliberately drive enough throughput to trigger a split.
- No managed DLQ. The `deadletter` table and its redrive procedure are ours to build,
  test and operate, where SQS would have supplied them.
- Polling consumers cost a `GetRecords` call per shard per interval whether or not there
  is work, unlike SQS long polling.

**Neutral**

- The `EventPublisher` interface from ADR-0003 disappears from the write path — services
  no longer publish anything, they simply write. The abstraction was portability
  insurance against a question that no longer needs asking.
- Event payloads are no longer hand-authored JSON with a `schemaVersion`; they are item
  images. Schema evolution therefore follows the DynamoDB attribute rules in
  [ADR-0008](0008-expand-contract-migrations.md), not a message-contract convention.
- Consumers must tolerate `REMOVE` records produced by TTL expiry on `timelines`, which
  are indistinguishable from genuine deletes except by the `userIdentity` attribute.
