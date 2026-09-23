# ADR-0010 — jlink runtime on a distroless base image

- **Status:** Accepted
- **Date:** 2026-09-23

## Context

Six services are built on every merge, pushed to ECR, pulled by up to three `t3.medium`
nodes, and scaled by an HPA and an Argo Rollouts canary. Image size affects pull time on
scale-out; startup time affects how quickly a canary step or an HPA reaction completes.
Both matter more here than they would on a large, warm cluster.

The natural first choice — a distroless Java 25 base — turned out not to exist.
`gcr.io/distroless/java25-debian12` resolves as a repository path but **publishes zero
tags**; only Java 21 and earlier are available. This was verified rather than assumed.

Baseline for comparison: `eclipse-temurin:25-jre` is roughly 270 MB and ships a full JRE,
most of which no service uses.

## Decision

A multi-stage build that constructs a custom runtime and copies it onto a minimal base:

1. **Build stage** — Temurin 25 JDK. Gradle build, then `jdeps --print-module-deps` on
   the application and its dependencies to compute the required module set.
2. **jlink stage** — `jlink --add-modules <computed> --strip-debug --no-man-pages
   --no-header-files --compress=zip-9` produces a runtime containing only those modules.
3. **CDS training run** — start the application with `-XX:ArchiveClassesAtExit`, let it
   reach readiness, and capture the class-data-sharing archive. Spring Boot 4 supports
   this natively via its AOT and CDS integration.
4. **Runtime stage** — `gcr.io/distroless/base-nossl-debian12:nonroot`, with the jlink
   runtime, the CDS archive and the application layers copied in.

Target under 120 MB, expected around 90 MB. CI **fails the build** if an image exceeds
120 MB, so the property is enforced rather than aspirational.

Layering follows Spring Boot's layered-jar order — dependencies, snapshot dependencies,
loader, application — so that an application-only change invalidates only the last and
smallest layer.

## Alternatives considered

**`gcr.io/distroless/java25-debian12`.** The intended choice. Does not exist.

**`gcr.io/distroless/java21-debian12`.** Exists, but runs Java 21, forfeiting the Java 25
language and runtime features that are a stated goal of the project.

**`eclipse-temurin:25-jre-alpine`.** Around 180 MB, one line of Dockerfile, no jlink
machinery. **Retained as the documented fallback** if the jlink build proves fragile —
the Dockerfile keeps it as a commented, working stage. Rejected as the default because it
is twice the size, includes a package manager and shell in the runtime image, and uses
musl, which changes JVM allocator behaviour in ways that complicate performance work.

**GraalVM native image.** Startup in tens of milliseconds and a far smaller footprint —
genuinely attractive for the canary and HPA story. Rejected: build times of 5–10 minutes
per service across six services would dominate CI, reflection configuration is an ongoing
tax, and peak throughput is lower than the JIT under sustained load, which is exactly the
regime Phase 11's k6 test exercises.

**Plain `eclipse-temurin:25-jre`.** 270 MB, a full JRE, a shell and a package manager in
the runtime image. Rejected on both size and attack surface.

## Consequences

**Positive**

- Roughly a third the size of the Temurin JRE baseline, so node scale-out and canary
  steps pull faster.
- CDS cuts cold start by around 40%, which directly shortens HPA reaction and canary
  analysis windows.
- The distroless base contains no shell, no package manager and no libc utilities, so a
  great many container-escape and living-off-the-land techniques simply have nothing to
  use. `nonroot` means the container never runs as UID 0.
- A smaller module set means fewer CVEs reported by Trivy, and those reported are more
  likely to be genuinely reachable.

**Negative**

- `kubectl exec` into a running pod is impossible — there is no shell. Debugging requires
  `kubectl debug` with an ephemeral container, which must be documented in a runbook
  before it is needed at 3 a.m.
- `jdeps` misses reflective and JNI module usage. A missing module manifests as a
  `NoClassDefFoundError` at runtime, potentially only on an uncommon code path. Mitigated
  by an explicit `--add-modules` allow-list for known reflective users and by the
  integration test suite running against the built image, not against the Gradle
  classpath.
- Three extra build stages, plus a training run that must reach readiness, so the
  Dockerfile is meaningfully more complex and slower than `FROM temurin`.
- The CDS archive is invalidated by any classpath change, so it must be regenerated on
  every build rather than cached across builds.

**Neutral**

- The 120 MB CI gate is a deliberate ratchet: it will eventually fail on a legitimate
  dependency addition, and that failure is the intended prompt to re-examine the
  dependency.
- The same image runs unchanged in all three tiers.
