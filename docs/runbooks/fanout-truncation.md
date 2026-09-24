# Runbook: fan-out truncation and malformed records

**Alerts:** `FanoutTruncating`, `FanoutRecordsMalformed`
**Severity:** page (truncation), ticket (malformed)

Both alerts share this runbook because both describe the same class of failure:
**silent, permanent data loss that no retry repairs.** Neither is a performance
problem, and neither will resolve on its own.

---

## FanoutTruncating

**Expression:** `increase(fanout_records_total{outcome="truncated"}[15m]) > 0`
**Threshold:** any occurrence at all, `for: 0m`

### What fired

The fan-out worker hit its safety ceiling on follower count and wrote to only
some of an author's followers. The remaining followers will never receive that
tweet. The record has already been checkpointed, so it will not be reprocessed,
and there is no repair path.

The alert has no tolerance band because the correct number of truncations is
zero. A ceiling that is being hit is a ceiling that is wrong, or a
classification that is wrong.

### First checks

1. **Which author, and how large.**

   ```
   kubectl -n twitter-clone logs deployment/fanout-worker | grep -i truncat
   ```

   The worker logs the author id and the follower count it saw. That count is
   the number to compare against the ceiling.

2. **Should that author be a celebrity.** This is almost always the answer. The
   design fans out on write for normal users and resolves celebrity authors at
   read time precisely so that a large follower set never needs a large
   fan-out. An author being truncated is an author who has crossed the celebrity
   threshold without being reclassified.

3. **Check the classification threshold against the ceiling.** If the celebrity
   threshold is above the fan-out ceiling, there is a band of follower counts
   that is neither fanned out completely nor read-resolved — a permanent hole in
   the design, not a tuning problem.

### Mitigation

Reclassify the author as a celebrity. Their followers will then resolve that
author's tweets at read time, and the truncation stops.

Raising the ceiling is the wrong fix in almost every case: the ceiling exists to
stop one write from consuming the worker for minutes, and raising it converts a
correctness failure into a latency failure affecting everyone.

### Repair

None automatically. The affected followers are missing that tweet from their
materialised timeline permanently. If the tweet matters, the only recovery is to
re-fan it manually from the tweet record — there is no tooling for this, which
is recorded in the gap register.

---

## FanoutRecordsMalformed

**Expression:** `increase(fanout_records_malformed_total[30m]) > 0`

### What fired

Stream records arrived without a tweet id or an author id. The worker cannot act
on them, so it drops them. They are **dropped, not retried** — a malformed
record retried forever would block the shard behind it, so the poison-record
policy is to count and discard.

### Almost always the cause

A contract change: an attribute renamed on the writer side and not on the
reader, or a new writer producing a shape the consumer does not know. This is
why `services/contracts` exists as a shared module — both sides are supposed to
be compiling against the same item records.

A deploy of `tweet-service` shortly before the alert is the first thing to
check.

### First checks

1. **What is actually in the record.**

   ```
   kubectl -n twitter-clone logs deployment/fanout-worker | grep -i malformed
   ```

   The worker logs the attribute keys it received. Compare against the
   `TableSchema` in `services/contracts`.

2. **Did the writer change.**

   ```
   kubectl -n twitter-clone rollout history deployment/tweet-service
   ```

3. **Is it every record or a subset.** A subset suggests a second writer — a
   migration job, a backfill script, or a manual `put-item`.

### Mitigation

Roll back the writer if a deploy correlates. If the schema change was
intentional, the consumer must be deployed before the producer, not after —
which is the ordering constraint this alert exists to enforce.

### Repair

The dropped records are gone from the fan-out path. The tweets themselves are
intact in DynamoDB; only the timeline materialisation was skipped. Affected
followers will not see those tweets in their home timeline, but the tweets are
still retrievable by author.
