# ADR-0001 — Four services, one worker, one frontend

- **Status:** Accepted
- **Date:** 2026-09-23
- **Supersedes:** —

## Context

This repository has two audiences at once: it is a working product, and it is a
demonstration of a software delivery system. The service decomposition therefore has to
satisfy two different pressures.

The delivery pipeline needs something worth orchestrating — path-filtered CI, independent
image builds, per-service Helm charts, independent rollouts, a canary on one service
while another is untouched. A single deployable unit makes all of that vacuous.

The runtime, however, is a three-node `t3.medium` sandbox with roughly 5.4 vCPU and
9.6 GiB allocatable, shared with ArgoCD, Prometheus, Grafana and Tempo. Every additional
service costs a JVM baseline, a chart, a set of probes, and a share of that budget.

## Decision

Four HTTP services, one asynchronous worker, one frontend:

| Component | Justification for existing separately |
|-----------|---------------------------------------|
| `gateway` | Cross-cutting concerns — JWT validation, rate limiting, versioning — that would otherwise be duplicated five times. Also the only ingress target, which makes NetworkPolicy design simple. |
| `user-service` | Owns identity and the follow graph. Different scaling profile (read-heavy, small payloads) and different security posture (holds password hashes) from everything else. |
| `tweet-service` | Owns the write path and the outbox. Highest write volume, and the only service touching S3. |
| `timeline-service` | Owns the hardest read path. Scales independently of writes at a 100:1 ratio, and is the only service that merges two data sources. |
| `fanout-worker` | The asynchronous boundary. No HTTP surface, scales on queue depth rather than request rate, and can be down without the product rejecting writes. |
| `web` | Different language, different build toolchain, different deploy cadence. |

## Alternatives considered

**Modular monolith.** Cheaper to run, simpler to reason about, and genuinely the correct
choice for a real product at this scale. Rejected because it would leave the delivery
system — the actual subject of this repository — with nothing to demonstrate. No
path-filtered builds, no per-service canary, no independent rollback, no service-to-
service tracing. The single most valuable artefact in the observability phase is a trace
crossing four process boundaries, and a monolith cannot produce one.

**Eight to twelve services** (separate auth, media, notification, search, follow,
profile, and so on). Rejected because the fifth service teaches nothing the fourth did
not, while each one adds a chart, a pipeline, a set of probes, an IRSA role, and roughly
300 MiB of the sandbox's 9.6 GiB. The marginal educational return is near zero and the
marginal operational cost is real.

**Serverless (Lambda + API Gateway).** Rejected because the stated goal is Kubernetes on
AWS. It would also make the playground's Lambda caps (256 MB, 10 s timeout, deleted after
300 invocations per hour) a constant obstacle.

## Consequences

**Positive**

- Path-filtered CI is meaningful: a change to `tweet-service` rebuilds one image.
- The distributed trace crosses four services and one queue — the best single artefact
  the observability phase produces.
- `fanout-worker` can fail independently, which makes the "tweets are still accepted
  while timelines go stale" failure mode real and demonstrable.
- Fits the sandbox: six pods at 100m/256Mi requested is ~0.6 vCPU and 1.5 GiB.

**Negative**

- Distributed transactions are impossible, which is precisely why ADR-0003 needs a
  transactional outbox rather than a plain publish.
- Latency on `GET /timeline` includes a hop through `gateway` and a hydration call to
  `tweet-service`. Budgeted for in the 300 ms p95 SLO.
- Six services means six sets of probes, resource limits, NetworkPolicies and charts to
  maintain. Mitigated by a shared Helm library chart.
- Local development requires `docker compose`, not a single `main()`.

**Neutral**

- `user-service` exposes `/internal/*` endpoints consumed only by `fanout-worker`. These
  are excluded from the public API surface by NetworkPolicy and by gateway routing, not
  by convention alone.
