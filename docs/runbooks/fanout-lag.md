# Runbook: fan-out lag high

**Alert:** `FanoutLagHigh`
**Severity:** page
**SLO:** freshness — p95 fan-out lag under 30 seconds

## What fired

The 95th percentile of `fanout_lag_seconds` has been above the freshness SLO for
ten minutes. Tweets are reaching follower timelines, but late.

## What the metric measures

Lag is measured from the **stream record's own timestamp**, not from when the
worker picked it up. That is the whole point: it covers the write, the stream's
own propagation, the backlog of records queued ahead of this one, and the
worker's processing time. A metric started when the worker dequeues would read
near-zero during the exact incident this alert exists to catch — a worker
calmly processing a two-hour backlog.

## First checks

1. **Is it a backlog draining or a backlog growing.** This determines whether
   you wait or act.

   ```
   histogram_quantile(0.95, sum by (le) (rate(fanout_lag_seconds_bucket[5m])))
   ```

   Falling steadily means recovery from an earlier stall; let it drain.
   Flat or rising means the worker cannot keep up with the write rate.

2. **Throughput versus arrival rate.**

   ```
   sum(rate(fanout_records_total[5m]))
   ```

   Compare against the tweet write rate. If throughput has collapsed while
   writes are steady, the worker is the bottleneck. If both rose together, this
   is load.

3. **Check for errors, not just slowness.** A worker retrying a failing write
   looks like a slow worker.

   ```
   sum by (outcome) (rate(fanout_records_total[5m]))
   ```

   A non-trivial `failed` share means the problem is DynamoDB or Redis, not CPU.

4. **Check the worker is not CPU-throttled.**

   ```
   rate(container_cpu_cfs_throttled_seconds_total{pod=~"fanout-worker.*"}[5m])
   ```

   Note: this requires cAdvisor metrics, which are not scraped in Tier L. See
   gap register row 26 (infrastructure metrics).

## Common causes

| Cause | Signal |
| --- | --- |
| Recovery from a stalled worker | Lag falling monotonically; `FanoutStreamUnresolved` fired earlier |
| Celebrity write | One author with a very large follower set; check `fanout_records_total{outcome="truncated"}` and the fan-out size histogram |
| Downstream write latency | Redis or DynamoDB latency up; worker throughput down but error rate near zero |
| Worker under-provisioned | Sustained high CPU, throughput flat at a ceiling, arrival rate above it |
| Shard imbalance | A single hot shard serialises work regardless of replica count |

## Mitigation

Adding replicas helps only up to the shard count — DynamoDB Streams assigns at
most one consumer per shard within a group, so a fourth replica against three
shards is idle.

```
kubectl -n twitter-clone scale deployment/fanout-worker --replicas=<n>
```

If the cause is a celebrity write, this is expected behaviour rather than a
fault: the design deliberately fans out on write for normal users and resolves
celebrity authors at read time. A celebrity whose follower count has crossed the
threshold but who has not been reclassified will produce exactly this alert.
Check the celebrity classification before scaling.

## When not to act

A draining backlog will clear on its own and the alert will resolve. Scaling the
worker mid-drain adds rebalancing churn to a system that is already catching up,
and has made the drain slower more than once.
