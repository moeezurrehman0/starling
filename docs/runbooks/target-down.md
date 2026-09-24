# Runbook: target down / no application targets

**Alerts:** `TargetDown`, `NoApplicationTargets`
**Severity:** page

These are the alerts about the monitoring system itself. While either is firing,
some or all of the other alerts in `slo.yaml` are unevaluable — a metric that is
not being scraped cannot cross a threshold, and an absent series looks exactly
like a healthy one to every expression in the file.

---

## TargetDown

**Expression:** `up == 0`, `for: 5m`

### What fired

Prometheus has a target registered and cannot scrape it.

### The two failure modes look identical from here

Either the pod is gone, or the pod is fine and the network path to it is
blocked. Prometheus reports both as `up == 0` with a connection error. This
distinction is the first thing to establish, because the fixes have nothing in
common.

### First checks

1. **Is the pod running.**

   ```
   kubectl -n twitter-clone get pods -l app=<app>
   ```

   Gone or `CrashLoopBackOff` — this is an application incident, and `TargetDown`
   is a symptom. Go look at the pod.

2. **If the pod is healthy, it is the network or the endpoint.** Check the
   scrape error text directly, which names which:

   ```
   curl -s localhost:19092/api/v1/targets \
     | python3 -c "import sys,json;[print(t['labels'].get('app'), t['health'], t['lastError']) for t in json.load(sys.stdin)['data']['activeTargets'] if t['health']!='up']"
   ```

   - `context deadline exceeded` → NetworkPolicy. The connection is being
     dropped, not refused.
   - `connection refused` → nothing listening on that port.
   - `401` / `403` → the endpoint exists and is rejecting Prometheus.
   - `404` → wrong path, or a pod annotated for scraping that does not serve
     metrics at all.

### Known causes, all of which have happened here

| Error | Cause | Fix |
| --- | --- | --- |
| Timeout | The target's port is not in `prometheus.scrapePorts` | Add it to `deploy/charts/observability/values.yaml`. The egress policy enumerates ports; a service on a new port is invisible until the list is updated. |
| 401 | Spring Security does not permit `/actuator/prometheus` | Add the path to the `permitAll` matcher in that service's `SecurityConfig`. Three services had it and one did not, and the only symptom was a missing target. |
| 404 | A non-Spring pod annotated `prometheus.io/scrape: "true"` | Set `metrics.enabled: false` for it in `deploy/envs/<env>/<app>.yaml`. The Next.js `web` pod was scraped for weeks this way. |
| Timeout, pod healthy, port correct | The app's own NetworkPolicy does not admit the `observability` namespace | Check the ingress rule on the app's policy. |

### The enumeration trade-off

The Prometheus egress policy lists scrape ports explicitly rather than allowing
all TCP. This is accepted, not solved: allowing all egress to all namespaces
would make the policy decorative. The cost is that the list goes stale, and the
stale-list failure mode is exactly this alert. That is the intended
relationship — `TargetDown` is the compensating control for the enumeration.

---

## NoApplicationTargets

**Expression:** `absent(up{job="kubernetes-pods"}) == 1`, `for: 10m`

### What fired

There are no application targets at all. Not "a target is down" — discovery
itself has broken, so there is no `up` series to be zero.

### Why a separate alert is necessary

`up == 0` cannot fire when `up` does not exist. If service discovery breaks —
an annotation renamed, RBAC narrowed, a namespace label changed — every series
disappears, every alert in the file silently stops evaluating, and the
dashboards go blank rather than red. A blank dashboard at 3am reads as "quiet
night".

### First checks

1. **Does Prometheus have any targets.**

   ```
   curl -s localhost:19092/api/v1/targets | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['activeTargets']))"
   ```

2. **Can Prometheus reach the API server.** Discovery needs both port 443 and
   port 6443 in the egress policy: in-cluster clients dial
   `kubernetes.default.svc:443`, and Calico evaluates NetworkPolicy **before**
   DNAT, so allowing only the node's 6443 blocks discovery while leaving direct
   node access working. This has bitten this cluster.

3. **Is the ServiceAccount still bound.**

   ```
   kubectl auth can-i list pods --as=system:serviceaccount:observability:prometheus --all-namespaces
   ```

4. **Did the pod annotations change.** The `service` chart gates
   `prometheus.io/scrape` on `.Values.metrics.enabled`; a values change that set
   it false everywhere would produce exactly this.

### Mitigation

Restore discovery. There is no partial workaround worth applying — while this is
firing the entire alerting surface is dark, so treat it with the urgency of a
production outage even though nothing user-facing has failed yet.
