# ADR-0010 — jlink runtime on a distroless base image

- **Status:** Accepted, amended after implementation
- **Date:** 2026-09-23 (amended 2026-09-23, same day, after building all five services)

> **Amendment note.** Everything below the *Decision* heading was originally written
> before the image was built. Implementation contradicted it in four places: the base
> image tag, the compression level, the expected size, and the shape of the CI gate. The
> original claims are kept visible rather than quietly overwritten, because the gap
> between them and the measurements is the most useful thing this ADR records. See
> *Measured outcome* for what is actually true.

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
   *Amended:* `--compress=zip-6`, and `--include-locales=en`, and
   `--generate-cds-archive`. zip-9 was under 2% smaller for roughly three times the link
   time. The other two are explained under *Measured outcome*.
3. **CDS training run** — start the application with `-XX:ArchiveClassesAtExit`, let it
   reach readiness, and capture the class-data-sharing archive. Spring Boot 4 supports
   this natively via its AOT and CDS integration.
4. **Runtime stage** — `gcr.io/distroless/base-nossl-debian12:nonroot`, with the jlink
   runtime, the CDS archive and the application layers copied in.
   *Amended:* `gcr.io/distroless/java-base-debian12:nonroot`. This tag was checked by
   pulling its manifest, not by assumption. It is purpose-built to host a jlink runtime
   and carries the native libraries a JVM links against — including the font and
   `libharfbuzz` libraries that `java.desktop` needs and that `base-nossl` lacks.

Target under 120 MB, expected around 90 MB. CI **fails the build** if an image exceeds
120 MB, so the property is enforced rather than aspirational.
*Amended: both numbers were wrong and the single flat gate was the wrong shape. See
*Measured outcome*.*

Layering follows Spring Boot's layered-jar order — dependencies, snapshot dependencies,
loader, application — so that an application-only change invalidates only the last and
smallest layer.

## Measured outcome

All five services were built and verified on arm64 with Temurin 25.0.4.1. Sizes are the
sum of the gzipped layer blobs, which is what a registry stores and a kubelet fetches.

| Service | cold pull | on disk | dependency layer | CDS archive |
|---|---|---|---|---|
| gateway | 104.8 MB | 214.2 MB | 30.8 MB | 11.6 MB |
| user-service | 117.3 MB | 227.8 MB | — | — |
| timeline-service | 127.0 MB | 251.0 MB | — | — |
| fanout-worker | 127.1 MB | 251.0 MB | — | — |
| tweet-service | 151.8 MB | 285.8 MB | 71.5 MB | 18.0 MB |

**The original "expected around 90 MB" was wrong, and the flat 120 MB gate would have
failed every service.** Two costs were missing from the estimate: the base CDS archive
that `jlink --generate-cds-archive` adds to the runtime (~28 MB uncompressed), and
`java.desktop`, which is not optional (14 MB — see below). The application's own CDS
archive was also not counted.

**The uncomfortable comparison.** At 214–286 MB on disk this image is the same size as,
or larger than, the `eclipse-temurin:25-jre-alpine` fallback it was meant to beat. The
size argument for jlink, as originally stated, does not survive measurement. What
survives is different and still worth having:

- no shell, no package manager, no libc utilities in the runtime image (verified by
  scanning all 2 375–2 455 filesystem entries, not asserted);
- startup 1 204 ms → 730 ms, a 39% reduction, which is the number the canary and HPA
  story actually depends on;
- glibc rather than musl, so allocator behaviour matches every other environment.

**The base is shared, and that changes the arithmetic.** tweet-service and gateway differ
in only **3 of 39 layers**. The jlink runtime (48.0 MB gzipped) and the distroless base
(~14 MB) are byte-identical across all five images, so a node pulls that ~62 MB once and
each additional service costs only its own dependency and CDS layers. Summing the table
above overstates a five-service node by roughly a factor of two. This property exists
only because all five services share one Dockerfile, one base and one module allow-list.

### jdeps alone is not sufficient, and this was measured

For the gateway, `jdeps --print-module-deps` reported **13** modules; an
`-Xlog:class+load` trace of a real startup observed **20**; only **8** appeared in both.
jdeps missed `java.logging`, `java.xml`, `jdk.localedata` and nine others. The trace in
turn missed five modules that are statically referenced but not exercised by a bare
startup. **Neither source is safe alone, so `scripts/derive-jlink-modules.sh` unions
them** and fails if the committed allow-list is missing anything.

Even the union is only as good as the code paths exercised. `jdk.net` appears in neither
source for the gateway, because the gateway has no Redis. timeline-service and
fanout-worker do, and both failed at context refresh with `NoClassDefFoundError:
jdk/net/ExtendedSocketOptions` from Lettuce's keep-alive configuration. Building only one
service would have shipped two broken images that passed every static check.

### Findings that cost real time

- **`java.desktop` is mandatory for a headless Spring service.** Spring's PropertyEditor
  machinery uses `java.beans.PropertyEditorSupport`, which lives there. Removing it fails
  context refresh. It costs 14 MB of runtime plus the font and `libharfbuzz` layers in
  the base.
- **A jlink runtime ships no base CDS archive.** Without `--generate-cds-archive`,
  `-XX:ArchiveClassesAtExit` emits only a *warning* and produces nothing, so the build
  succeeds and silently ships no CDS. The Dockerfile's `test -s` turns that into a build
  failure.
- **CDS records absolute classpath paths.** Training at `/app` and running at `/opt/app`
  yields "shared class paths mismatch" and the archive is ignored — a silent 474 ms
  regression. Both stages must use the identical path.
- **App CDS rejects a directory classpath**, so Boot's `extract --layers --launcher`
  exploded layout cannot be used. Plain `extract` (jar plus `lib/`) is used instead and
  layering is recovered with separate `COPY` instructions.
- **`--enable-native-access=ALL-UNNAMED` is deliberately not set**, despite Netty warning
  that such calls will be blocked in a future release. It becomes the
  `jdk.module.enable.native.access` property, which the jlink-generated base archive does
  not carry, so it disables optimized module handling and drops the full module graph.
  When a JDK enforces this, the fix is the `Enable-Native-Access` JAR manifest attribute
  (JEP 472), which sets no system property.
- **Leyden AOT (`-XX:AOTMode=record`) produced a 0-byte configuration** and verification
  warnings for Spring Security classes. Classic CDS is used instead.
- **`docker exec` cannot test a distroless image.** `docker exec <c> test -e /bin/sh`
  fails with "executable file not found", which an exit-code check reads as a pass. The
  first version of the verifier had exactly this false positive. `docker export | tar -t`
  is used instead.

### The gate, reshaped

A single total-size gate was replaced, because total size is not what anything pays.

- **Cold pull**, gated per service from `docker/image-budgets.txt`. This is what a node
  with an empty cache downloads. In Tier S it is paid on *every* run, because the cluster
  is destroyed after 180 minutes and no node is ever warm. One global limit would have to
  be set by tweet-service at 167 MB, under which the gateway could triple and still pass.
- **Warm pull**, gated at 20 MB: only the layers whose digest differs from the previous
  image. This is what a canary step and an HPA scale-out on a warm node pay. Measured at
  **11.6 MB for a gateway code change, all of it the CDS archive** — the application jar
  layer is 3 kB. The archive is not reproducible and is re-pulled on every deploy; that
  is the standing price of the 39% startup gain, and it is now visible rather than
  assumed.
- **On-disk size** is reported but not gated. It governs node disk, which is not scarce
  in any tier.

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

- ~~Roughly a third the size of the Temurin JRE baseline~~ **Not borne out.** The image
  is comparable to `temurin:25-jre-alpine` on disk. What is real is that the ~62 MB
  runtime and base layers are shared byte-for-byte across all five services, so a node
  pulls them once.
- CDS cuts cold start by around 40% — **measured at 39%, 1 204 ms → 730 ms**, which
  directly shortens HPA reaction and canary analysis windows. The original estimate was
  the one prediction in this ADR that held.
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
  `NoClassDefFoundError` at runtime, potentially only on an uncommon code path. This is
  not hypothetical: it happened twice during implementation, and the measurements under
  *Measured outcome* show jdeps agreeing with the runtime on only 8 of 20 modules.
  Mitigated by a committed allow-list derived from the *union* of jdeps and a class-load
  trace (`scripts/derive-jlink-modules.sh`), and by `scripts/image-verify.sh` running
  against the built image — including a live TLS handshake to a real AWS endpoint, which
  is the check that would catch a missing crypto provider. The allow-list must be
  re-derived whenever a service gains a dependency; `jdk.net` is the standing reminder
  that a list derived from one service does not generalise to the others.
- Three extra build stages, plus a training run that must reach readiness, so the
  Dockerfile is meaningfully more complex and slower than `FROM temurin`.
- The CDS archive is invalidated by any classpath change, so it must be regenerated on
  every build rather than cached across builds.

**Neutral**

- The per-service cold-pull budgets are a deliberate ratchet: each will eventually fail
  on a legitimate dependency addition, and that failure is the intended prompt to
  re-examine the dependency. They are set roughly 10% above measurement.
- The same image runs unchanged in all three tiers, and this is now verified by
  `make image-verify` rather than asserted.
- `fanout-worker` gained `spring-boot-starter-webmvc` as a direct result of this work. It
  is not a request-serving service, but its `application.yaml` exposes health and
  Prometheus endpoints over HTTP, and with no web server on the classpath nothing served
  them — meaning no Kubernetes readiness probe and no metrics scrape. The verifier
  surfaced a latent deployment bug that had nothing to do with images.
