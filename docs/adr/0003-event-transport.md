# ADR-0003 — One `EventPublisher` interface, two adapters

- **Status:** **Superseded** by [ADR-0012](0012-dynamodb-streams-event-transport.md)
- **Date:** 2026-09-23
- **Superseded:** 2026-09-23

> **Why this was superseded.** This record solved the dual-write problem with a
> transactional outbox because the datastore was PostgreSQL. Moving the operational data
> to DynamoDB ([ADR-0011](0011-dynamodb-operational-datastore.md)) dissolves that problem
> rather than solving it differently: DynamoDB Streams is already a durable ordered log
> of committed writes, so no code path can commit a tweet without emitting its event. The
> outbox table, its poller, its pruning job and the `EventPublisher` abstraction are all
> removed. The reasoning below is retained because the alternatives it rejected — direct
> publish, 2PC, Debezium — were rejected for reasons that still hold.

## Context

`tweet-service` must tell `fanout-worker` that a tweet was created. Two independent
problems have to be solved together.

**Durability.** The NFR is that no acknowledged tweet may be lost. Writing the tweet to
PostgreSQL and then publishing to a message broker is two operations without a shared
transaction: a crash between them silently drops the fan-out, and the tweet never
reaches any timeline.

**Portability.** The production design uses SNS → SQS. The KodeKloud playground does not
document SNS or SQS in its allow-list, so they may be unavailable. Whether they work will
not be known until the Phase 6 capability probe. A decision is needed that does not block
on that answer, and that does not leak the answer into domain code.

## Decision

Define one domain-level interface:

```java
public interface EventPublisher {
    void publish(DomainEvent event);   // must be called inside the caller's transaction
}
```

Provide two adapters behind it, selected by Spring profile:

| Adapter | Mechanism | Used in |
|---------|-----------|---------|
| `OutboxEventPublisher` | `INSERT` into an `outbox` table in the caller's transaction; a poller publishes committed rows and marks them published | Tier L, and Tier S if SQS is unavailable |
| `SqsEventPublisher` | writes the same outbox row, then a relay drains the outbox to SNS → SQS | Tier P, and Tier S if the probe succeeds |

Critically, **both adapters write to the outbox first.** The outbox is not the fallback —
it is the durability mechanism in every tier. The adapters differ only in where the relay
delivers the committed event. This means the atomicity guarantee is identical everywhere,
and the Tier S fallback is a delivery-path difference, not a correctness difference.

Consumers are idempotent: `fanout-worker` deduplicates on event ID, because both
transports are at-least-once.

## Alternatives considered

**Publish directly to SNS inside the transaction.** Not transactional. A commit followed
by a publish failure loses the event; a publish followed by a rollback invents one.
Rejected outright — it violates the durability NFR.

**Two-phase commit between PostgreSQL and SQS.** SQS does not support XA, and 2PC would
be the wrong answer even if it did.

**Debezium change-data-capture on the tweets table.** Genuinely elegant, and it removes
the poller. Rejected because it requires Kafka Connect plus logical replication slots,
which cannot run on a `db.t3.micro` in a 180-minute sandbox, and because it would make
Tier L and Tier S structurally different from each other.

**Only ever use the outbox, never SQS.** Simpler, and honestly adequate for this scale.
Rejected because demonstrating IRSA-scoped access to a managed queue, DLQ configuration
and redrive policies is a stated goal of the project. Keeping both adapters costs one
interface and one extra integration test suite.

**Only ever use SQS.** Would make the whole system undeployable if the probe finds SQS
blocked, with no path forward inside a 180-minute session.

## Consequences

**Positive**

- Atomicity is guaranteed in every tier: tweet and event commit or neither does.
- The Phase 6 probe result changes one Spring profile and no domain code.
- The same integration test suite runs against both adapters, so neither rots.
- A DLQ, redrive policy and IRSA-scoped queue permissions remain demonstrable when SQS
  is available.

**Negative**

- The outbox poller adds write load to PostgreSQL and latency proportional to the poll
  interval. Mitigated by a partial index on unpublished rows and a 200 ms interval, well
  within the 5-second freshness budget.
- Two delivery paths means two failure modes to understand and two runbooks.
- The outbox table needs periodic pruning of published rows — a scheduled job, and one
  more thing that can silently stop.

**Neutral**

- Event payloads are versioned JSON with an explicit `eventType` and `schemaVersion`,
  since both transports are schemaless.
