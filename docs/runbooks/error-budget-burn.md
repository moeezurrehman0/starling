# Runbook: error budget burn

**Alerts:** `ErrorBudgetBurningFast`, `ErrorBudgetBurningSlow`
**Severity:** page (fast), ticket (slow)
**SLO:** 99.5% availability, measured as non-5xx responses over all responses

## What fired

The service is consuming its error budget faster than the SLO window allows. The
alerts are multi-window, multi-burn-rate: `Fast` means the current rate would
exhaust a 30-day budget in about two days, `Slow` means it would exhaust it in
about ten. Each alert pairs a long window with a short one, and both must be
over the threshold — the short window is the reset condition, so the alert stops
within minutes of the incident ending rather than an hour later.

## What it does not mean

It is not a threshold on the error rate. A brief spike that consumes a trivial
slice of the budget will not fire, and a low, steady 0.6% error rate that will
consume the entire budget by Friday will. If you are looking for "how many
errors right now", this is the wrong alert to read.

4xx responses are excluded. A credential-stuffing run against `/v1/sessions`
produces thousands of 401s and must not page anyone.

## First checks

1. **Which service.** The alert carries an `app` label. The recording rules are
   aggregated `by (app)`, so one bad service does not hide behind five healthy
   ones.

   ```
   sli:errors:ratio_rate5m
   ```

2. **Is it one endpoint or all of them.** Drop to the raw metric, which still
   has the `uri` and `status` dimensions the recording rule aggregates away:

   ```
   sum by (uri, status) (rate(http_server_requests_seconds_count{app="<app>",status=~"5.."}[5m]))
   ```

   One `uri` means a code path. All of them means a dependency.

3. **Is it one pod or all of them.** A single bad replica — a pod that lost its
   DynamoDB credentials, or one scheduled onto a node with a broken route — is
   the most common cause and the fastest fix.

   ```
   sum by (pod) (rate(http_server_requests_seconds_count{app="<app>",status=~"5.."}[5m]))
   ```

4. **Find a failing trace.** In Grafana, query Loki for the service's error
   lines and click the `TraceID` derived field:

   ```
   {namespace="starling", container="<app>"} | json | http_status >= 500
   ```

   The trace shows which hop failed. This is faster than reading logs from four
   services and trying to line up timestamps by hand.

## Common causes

| Symptom | Likely cause |
| --- | --- |
| 5xx on every endpoint of one service, all pods | Downstream dependency down — check `stream_resolved`, Redis, DynamoDB |
| 5xx on one endpoint only | Recent deploy to that code path; check the rollout history |
| 5xx on one pod only | Bad node, expired credentials, or a pod that never finished warming |
| Errors start exactly at a deploy | Roll back first, diagnose after |
| Gateway 5xx but upstreams healthy | Gateway upstream timeout — 5xx here is the gateway refusing to wait, which is deliberate |

## Mitigation

Roll back before diagnosing if the onset correlates with a deploy. The error
budget is being spent while you read logs.

```
kubectl -n starling rollout undo deployment/<app>
kubectl -n starling rollout status deployment/<app>
```

## Known limitation in Tier L

Local Prometheus retains 2 hours, so `ErrorBudgetBurningSlow` — whose long
window is 6 hours — can never fire on kind. This is deliberate: the rules file
is the artefact promoted to Tier P, and weakening the window to make it
demonstrable locally would weaken it in production too. See gap register row 27
(signal retention).
