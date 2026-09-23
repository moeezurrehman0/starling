# ADR-0005 — UUIDv7 primary keys

- **Status:** Accepted
- **Date:** 2026-09-23

## Context

Identifiers are needed for users and tweets. Two properties matter for tweets in
particular: they are the highest-insert-rate table, and timelines are ordered
chronologically and paginated.

The candidates are auto-incrementing integers, UUIDv4, and UUIDv7.

## Decision

UUIDv7 for all entity primary keys, stored as PostgreSQL `uuid`.

UUIDv7 encodes a millisecond Unix timestamp in its high bits followed by randomness. It
is therefore both globally unique and lexicographically time-ordered.

## Alternatives considered

**`bigserial` auto-increment.** Compact, fast, and naturally ordered. Rejected on two
grounds. It requires a database round trip before an ID exists, which complicates the
outbox pattern in ADR-0003 — the event payload needs the aggregate ID at the moment of
insert. And sequential integer IDs in a public URL leak business volume and enable
trivial enumeration of other users' resources.

**UUIDv4.** Globally unique, generated client-side, no enumeration risk. Rejected for
index behaviour: fully random keys scatter inserts across the entire B-tree, causing
page splits and inflating write amplification and cache pressure. On the highest-insert
table in the system, on a `db.t3.micro`, this is the one place where index locality
actually matters.

**ULID.** Functionally equivalent to UUIDv7 and was the better option before UUIDv7 was
standardised. Rejected in favour of the standard: `java.util.UUID` handles v7 natively,
PostgreSQL's `uuid` type stores it, and no custom converters or dependencies are needed.

## Consequences

**Positive**

- Inserts remain append-like at the right edge of the B-tree, as with a sequence.
- `ORDER BY id DESC` is chronological, so timeline pagination needs no secondary sort
  and keyset pagination works on the primary key alone.
- The merge step in ADR-0006 sorts two lists by ID rather than by fetching timestamps —
  the cached Redis list holds IDs only, so ordering requires no hydration.
- IDs are generated in application code before insert, which is what makes the atomic
  tweet-plus-outbox write in ADR-0003 straightforward.
- No enumeration of user or tweet IDs from a URL.

**Negative**

- 16 bytes versus 8 for `bigint`, in the table and in every index. At 500k tweets/day
  this is a few hundred megabytes per year — acceptable.
- The embedded millisecond timestamp leaks creation time. For tweets this is public
  information anyway. For users it reveals registration time, which is a minor and
  accepted disclosure.
- UUIDs are unpleasant to type in a `psql` session during debugging.

**Neutral**

- Redis stores tweet IDs as strings in timeline lists; the 800-entry `LTRIM` cap means
  roughly 29 KB per cached timeline, which is budgeted for.
