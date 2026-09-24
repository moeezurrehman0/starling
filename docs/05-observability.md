# Observability

Three signals, one system. Metrics in Prometheus, logs in Loki, traces in Tempo,
and a Grafana that can pivot between them on a shared `trace_id`.

```
deploy/charts/observability/
├── prometheus.yaml        scrape config, service discovery, storage
├── prometheus-rules.yaml  6 recording rules, 8 alerts, 4 groups
├── loki.yaml              single-binary, filesystem storage
├── promtail.yaml          DaemonSet, path-based tailing
├── tempo.yaml             single-binary, OTLP ingest
├── otel-collector.yaml    OTLP/HTTP in from apps, OTLP/gRPC out to Tempo
├── grafana.yaml           datasources, derived fields, dashboards
└── networkpolicy.yaml     default-deny plus per-component exceptions
```

The chart deploys into two namespaces. `observability` runs under the
`restricted` Pod Security Standard and holds everything that does not need node
access. `observability-agents` runs `privileged` and holds only promtail, which
needs a hostPath into `/var/log/pods`. Splitting them means the privilege
exception is one namespace containing one workload, rather than a label on the
namespace that holds Grafana.

## 1. Why this stack and not a managed one

Tier P would use Amazon Managed Prometheus, Amazon Managed Grafana and AWS X-Ray,
and the Terraform for that is a fraction of this chart. This is self-hosted for a
specific reason: the point of the project is to show the system, and a managed
backend hides exactly the parts worth showing — the scrape config, the relabelling,
the retention trade-off, the network path from a pod to a collector. A chart that
reads `amp_workspace_id = aws_prometheus_workspace.this.id` demonstrates that you
can read AWS documentation.

The cost is recorded honestly in the gap register: this is not what production
should look like, and the chart is not a production artefact. The `prometheus-rules.yaml`
ConfigMap *is* — recording rules and alert expressions transfer to AMP unchanged.

## 2. Metrics

### Discovery

Prometheus discovers pods cluster-wide and keeps the ones annotated
`prometheus.io/scrape: "true"`. The annotation is set by the shared `service`
chart and gated on `.Values.metrics.enabled`, which defaults true for the Spring
services and is explicitly false for the Next.js `web` pod — that pod serves no
metrics endpoint and was returning 404 to every scrape until the gate was added.

### The SLI recording rules

Six recording rules compute the service level indicators so that alerts and
dashboards read the same number. An SLO defined twice — once in an alert
expression and once in a panel query — is an SLO that will eventually disagree
with itself, and the disagreement is always discovered mid-incident.

Two details in those rules are load-bearing and non-obvious:

**5xx only.** `sli:errors:rate5m` counts `status=~"5.."`. A 4xx is the client
being wrong. Counting 401s against the error budget means a credential-stuffing
run against `/v1/sessions` pages the on-call for someone else's bad requests.

**`or (… * 0)`.** The error ratio is `errors / requests`. At zero traffic that is
0/0, which is NaN, and NaN compares false against every threshold. Without the
`or` clause a service that has stopped receiving requests entirely is
indistinguishable from a service with no errors. The `or` substitutes an explicit
zero.

### Alerting

Error budget alerts are multi-window, multi-burn-rate, from the SRE workbook.
The obvious alert — "error ratio above X for five minutes" — is wrong in both
directions: it pages for a brief spike that consumes a trivial slice of the
budget, and it stays silent through a slow bleed that consumes all of it by
Friday. Each pair uses a short window as the reset condition so the alert clears
within minutes of the fix rather than an hour later.

Four fan-out alerts cover the asynchronous path, which has no HTTP traffic and
therefore none of the SLI alerts above. `FanoutStreamUnresolved` is the
interesting one: it exists because a worker that cannot find its stream logs one
WARN, passes both probes, and consumes nothing, while writes keep returning 201.

Two meta alerts watch the monitoring stack. `NoApplicationTargets` exists
because `up == 0` cannot fire when `up` does not exist — if discovery breaks,
every series disappears and every other alert silently stops evaluating.

Each alert carries a `runbook` annotation pointing at `docs/runbooks/`.

## 3. Logs

### Path-based tailing, not Kubernetes service discovery

Promtail tails `/var/log/pods/*/*/*.log` directly and recovers namespace, pod and
container from the path with a regex on the `filename` label.

The conventional configuration is `kubernetes_sd_configs` with `role: pod`. That
was tried first and **does not work** in promtail 3.3.2 on this cluster: the
provider starts, logs `Using pod service account via in-cluster config`, and then
discovers 0 targets forever. No error at any log level, against a reachable API
server with a token that returns 200 from the same network namespace. The
readiness message — *"Unable to find any logs to tail. Please verify permissions,
volumes, scrape_config, etc."* — points at volumes and permissions when the fault
is discovery.

Path-based tailing is also the better design for a node agent. Kubernetes SD asks
a node-local process to hold cluster-wide pod read in order to learn things the
kubelet has already written into the filesystem it is mounting anyway.

Promtail's ServiceAccount is retained but **deliberately unbound** — the
ClusterRole and ClusterRoleBinding are deleted. An accidental reintroduction of
`kubernetes_sd_configs` then fails loudly with a 403 rather than silently
regaining cluster-wide pod read.

### The regex

Kubelet writes `/var/log/pods/<namespace>_<pod>_<uid>/<container>/<n>.log`. The
pod-name capture must be greedy and the uid capture non-greedy: pod names contain
hyphens, the namespace never contains an underscore, and anchoring on the trailing
uid is what disambiguates the two.

### Labels versus structured metadata

`namespace`, `pod`, `container` and `level` become labels. `trace_id` and
`span_id` become **structured metadata**, not labels — a label with one value per
trace creates a new Loki stream per request, which is the canonical way to make
Loki fall over.

`filename` is dropped after the regex has read it. It is redundant once
namespace/pod/container are labels, and it changes on every log rotation, which
would churn streams.

### One trap worth recording

At `log_level: debug`, promtail tailing `/var/log/pods` reads *its own* debug
output. Each line quotes the previous one with escalating backslash escaping,
and Loki starts returning 429 within a minute. The config runs at `warn`.

## 4. Traces

Applications export OTLP over HTTP to the collector, which forwards OTLP over
gRPC to Tempo. The collector is not strictly necessary — the apps could write to
Tempo directly — but it is where sampling, attribute scrubbing and multi-backend
fan-out would go in Tier P, and putting it in now means the app configuration
does not change when they are added.

### Two things Spring Boot 4 broke

**The tracing autoconfiguration moved out of the actuator.** Boot 4 split it into
`spring-boot-micrometer-tracing` and `spring-boot-micrometer-tracing-opentelemetry`.
Having `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp` on the
classpath gives you the libraries with nothing to wire them. Without the Boot
modules there is no `Tracer` bean, which means no exporter **and** no `traceId` in
the MDC — so both traces and log correlation fail from a single missing
dependency, and nothing logs an error.

**The property was renamed.** `management.otlp.tracing.endpoint` became
`management.opentelemetry.tracing.export.otlp.endpoint`. The old name still binds,
which is worse than if it did not: a configuration that is silently ignored
produces an application that starts, serves traffic, and exports nothing.

### Context propagation

Only the **auto-configured** `RestClient.Builder` carries the Micrometer
observation interceptor that writes the `traceparent` header. Calling
`RestClient.builder()` directly produces a fully working client that silently
severs the trace at that hop — the gateway opens a span for the inbound request,
calls an upstream without propagating, and the trace stops at the edge. The
gateway and timeline-service both inject the builder for this reason.

### The access log

The services otherwise log nothing during normal operation, which is a defensible
default but leaves log-to-trace correlation with nothing to correlate: Loki holds
startup banners and Tempo holds spans, and no line in the first points into the
second. `services/platform-observability` contributes one `AccessLogFilter`
registered at `LOWEST_PRECEDENCE`, so the tracing filter has already populated the
MDC by the time it logs. Probe and scrape endpoints are excluded — at a
ten-second interval across three replicas they would dominate every Loki query.

### Field naming

Boot's ECS encoder emits `traceId`/`spanId` in camelCase; the OTel log conventions
use `trace_id`/`span_id`. Promtail's json stage maps one to the other. Extracting
the wrong name costs nothing at ingest and silently breaks the Loki-to-Tempo
link, which is noticed only when someone needs it.

## 5. Network policy

The namespace is default-deny in both directions, with explicit exceptions per
component.

Two traps are encoded there:

**Port 443 *and* 6443 for the API server.** In-cluster clients dial
`kubernetes.default.svc:443`, and Calico evaluates NetworkPolicy **before** DNAT.
Allowing only the node's 6443 blocks service discovery while leaving direct node
access working, so it looks correct from a shell on the node.

**Scrape ports are enumerated, not wildcarded.** `prometheus.scrapePorts` lists
every port Prometheus is allowed to reach. This is accepted rather than solved:
allowing all TCP to all namespaces would make the policy decorative. The list
will go stale, and when it does the symptom is `TargetDown` — which is the
compensating control, and is documented as such in that runbook.

## 6. Storage and retention

Everything writes to `emptyDir`. Prometheus retains 2 hours, which is enough to
demonstrate the recording rules and the fast-burn alert and not enough for
anything else.

Two consequences are deliberate and recorded in the gap register:

- `ErrorBudgetBurningSlow` has a 6-hour long window and therefore **cannot fire
  locally**. The rules file is the artefact promoted to Tier P; shortening the
  window to make it demonstrable on kind would weaken it in production.
- A rescheduled pod loses all of its history. Debugging an incident is impossible
  if the evidence is deleted by the act of restarting the thing that holds it.

## 7. Grafana

Anonymous admin, no authentication. Tier P is Amazon Managed Grafana behind IAM
Identity Center. The local instance exists to prove the datasources and the
derived-field wiring, and shipping an auth story for a disposable cluster would
be effort spent on the part that gets thrown away.

`GF_PATHS_DATA` is repointed at the mounted `emptyDir` rather than mounting the
volume over `/var/lib/grafana`. With `readOnlyRootFilesystem: true` Grafana exits
non-zero trying to create `grafana.db`; mounting over the default path fixes that
but shadows the dashboards ConfigMap.

## 8. What is not here

- **kube-state-metrics and node-exporter.** No pod-restart, pending-pod,
  container-CPU or node-pressure metrics. Several runbook steps reference
  container CPU throttling and cannot be executed in Tier L.
- **Alertmanager.** Alerts evaluate and appear in the Prometheus UI; nothing
  routes them. Tier P routes to PagerDuty.
- **Exemplars.** Prometheus histograms do not carry trace exemplars, so there is
  no click-through from a latency spike to the trace that caused it.
- **Log-based metrics.** No Loki recording rules.
