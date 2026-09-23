# ADR-0002 — REST/JSON for synchronous inter-service calls

- **Status:** Accepted
- **Date:** 2026-09-23

## Context

Four services call each other synchronously: `gateway` fans out to the three domain
services, `timeline-service` hydrates tweets from `tweet-service`, and `fanout-worker`
asks `user-service` for follower lists. A transport must be chosen.

The call volume is modest — the heaviest path is timeline hydration, one call per
timeline read. The dominant cost on that path is the Redis and PostgreSQL work, not
serialisation.

## Decision

REST over HTTP/1.1 with JSON bodies, described by OpenAPI, consumed through Spring Boot
4's `@HttpExchange` declarative clients with auto-registration.

Endpoints intended only for service-to-service use are namespaced `/internal/*` and are
unreachable from outside the cluster — enforced by NetworkPolicy and by `gateway` routing
rules, not by naming convention alone.

## Alternatives considered

**gRPC.** Faster, smaller on the wire, and gives generated clients and a schema that
cannot drift. Rejected for three reasons. First, debugging: a `curl` against a running
pod is a primary diagnostic tool in this repository, and protobuf over HTTP/2 removes it.
Second, the load balancing story on Kubernetes is worse — gRPC's long-lived HTTP/2
connections pin to pods and require either a mesh or client-side balancing, and this
project has deliberately rejected a service mesh (see ADR-0001 consequences). Third, the
performance advantage is irrelevant at the measured call volume.

**GraphQL between services.** Solves a problem this system does not have. One client,
well-known access patterns.

**Shared library with direct method calls.** That is a monolith, rejected in ADR-0001.

## Consequences

**Positive**

- `curl` and `kubectl port-forward` remain sufficient for debugging any hop.
- OpenAPI specs are generated from controllers and diffed in CI, so a breaking change to
  an internal contract fails the build.
- `@HttpExchange` gives interface-based clients without hand-written boilerplate, and
  Spring Boot 4 auto-registers them.
- HTTP semantics compose naturally with the OpenTelemetry instrumentation, rate limiting
  and retry logic already present.

**Negative**

- JSON serialisation costs more CPU and bytes than protobuf. Accepted: measured volumes
  make this immaterial, and the SLO has headroom.
- No compile-time contract enforcement across services. Mitigated by the OpenAPI diff
  check in CI, which is weaker than generated stubs.
- Chatty hydration: `timeline-service` makes a batch call to `tweet-service` per read.
  Mitigated by batching IDs into one request rather than N.

**Neutral**

- Should a future requirement demand streaming — live timeline updates, for example —
  that path would use Server-Sent Events rather than introducing gRPC for one case.
