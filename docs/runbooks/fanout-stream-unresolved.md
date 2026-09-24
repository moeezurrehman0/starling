# Runbook: fan-out stream unresolved

**Alert:** `FanoutStreamUnresolved`
**Severity:** page
**Expression:** `max_over_time(stream_resolved{group="fanout"}[5m]) == 0`

## What fired

The fan-out worker cannot resolve the DynamoDB stream ARN for the `tweets`
table, so it is consuming nothing. No tweet is reaching any follower timeline.

## Why this alert exists

This is the failure that motivated the metric. The worker logs a single WARN at
startup, then retries on a timer. It passes its liveness probe, because the
process is healthy. It passed its readiness probe too, until the health
indicator was added. Writes to `/v1/tweets` continue returning 201 because the
write path never touches the worker. From every other signal the system looks
fine, and the only visible symptom is that timelines stop changing — which no
automated check was watching.

The alert is deliberately fleet-wide and time-windowed where the readiness
probe is per-pod and instantaneous. `max_over_time(...) == 0` requires that *no*
pod resolved the stream at any point in the window, which avoids paging on the
normal case of a freshly-started pod that has not yet made its first call.

## First checks

1. **Confirm the scope.** Is it every consumer group or only fan-out? The
   `tweets` stream has two: `fanout` and `search-indexer`.

   ```
   stream_resolved
   ```

   Both at 0 points at DynamoDB or at credentials. Only `fanout` at 0 points at
   the worker.

2. **Read the worker's log.** The resolution failure is logged with the
   underlying SDK exception, which is the single most informative line
   available:

   ```
   kubectl -n twitter-clone logs deployment/fanout-worker | grep -i stream
   ```

3. **Check the table actually has a stream enabled.** A table recreated without
   `StreamSpecification` is the most common cause after an environment rebuild,
   and the SDK error for it is not obviously distinguishable from a permissions
   error.

   ```
   aws dynamodb describe-table --table-name tweets \
     --query 'Table.{Stream:StreamSpecification,Arn:LatestStreamArn}'
   ```

   In Tier L, point the CLI at LocalStack with `--endpoint-url http://localhost:4566`.

## Common causes

| Cause | How to tell |
| --- | --- |
| Table recreated without streams enabled | `describe-table` shows no `LatestStreamArn` |
| IAM role lacks `dynamodb:DescribeTable` or `ListStreams` | `AccessDeniedException` in the worker log, naming the action |
| Wrong table name in config | The worker log names the table it is looking for; compare with the ConfigMap |
| LocalStack restarted (Tier L only) | Tables gone entirely; re-run the `create-tables` Job |
| Stream disabled and re-enabled | The ARN changes; the worker will pick up the new one on its next retry, so this self-heals within the retry interval |

## Mitigation

There is no safe manual override. The worker retries on a timer and will resume
on its own the moment the stream resolves, so the fix is always to repair the
stream rather than to restart the worker.

If the table was recreated, re-enabling the stream is enough:

```
aws dynamodb update-table --table-name tweets \
  --stream-specification StreamEnabled=true,StreamViewType=NEW_IMAGE
```

## Recovery and backfill

DynamoDB streams retain 24 hours. If the outage was shorter than that, the
worker resumes from its checkpoint in the `stream_checkpoints` item and
back-fills automatically — expect `fanout_lag_seconds` to spike and drain, and
expect `FanoutLagHigh` to fire during the drain. That is correct behaviour, not
a second incident.

If the outage exceeded 24 hours, the records are gone and the affected
timelines are permanently missing those tweets. There is no repair path in the
current design; this is recorded in the gap register.
