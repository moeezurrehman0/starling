# ---------------------------------------------------------------------------
# No `# syntax=` directive, deliberately -- and its removal was a bug fix, not a
# simplification. The directive makes BuildKit resolve and pull a dockerfile frontend image
# from Docker Hub before it evaluates a single instruction, which on a restricted network
# hangs for minutes with no output whatsoever and on an offline one fails outright. Nothing
# below uses a feature newer than the built-in frontend: no --mount, no heredoc, no
# COPY --link. docker/Dockerfile.web omits it for the same reason.
# ---------------------------------------------------------------------------
# jlink runtime on a distroless base — ADR-0010.
#
# Build context is the repository root. The Spring Boot jar must already exist;
# CI builds it once and this file only packages it, so the artefact that was
# tested is the artefact that ships.
#
#   docker build -f docker/Dockerfile \
#     --build-arg JAR_FILE=services/gateway/build/libs/gateway.jar \
#     --build-arg SERVICE=gateway -t starling/gateway:dev .
#
# Use `make image SERVICE=gateway`, which computes those arguments.
# ---------------------------------------------------------------------------

ARG JDK_IMAGE=eclipse-temurin:25-jdk
# distroless/java-base ships glibc, zlib, ca-certificates and tzdata but no JDK,
# which is exactly what a jlink runtime needs. Note this is NOT the base named
# in the first draft of ADR-0010 (base-nossl): java-base is purpose-built for
# custom runtimes and carries the native libraries the JVM dlopen()s.
# There is still no distroless/java25 image — verified, not assumed.
ARG RUNTIME_BASE=gcr.io/distroless/java-base-debian12:nonroot


# --- Stage 1: explode the Boot jar -----------------------------------------
# `extract` (without --layers) produces the launcher jar plus lib/*.jar. The
# --layers form is NOT usable here: application CDS refuses a directory on the
# classpath with "Error: non-empty directory". Two COPY instructions below
# recover the layering that matters anyway.
FROM ${JDK_IMAGE} AS exploded
WORKDIR /src
ARG JAR_FILE
COPY ${JAR_FILE} application.jar
# `extract` names the launcher jar after its source, so COPY above fixes the
# name to application.jar and everything downstream can depend on it.
RUN java -Djarmode=tools -jar application.jar extract --destination /exploded \
 && test -f /exploded/application.jar


# --- Stage 2: the jlink runtime --------------------------------------------
FROM ${JDK_IMAGE} AS runtime
COPY docker/jlink-modules.txt /tmp/jlink-modules.txt
RUN set -eux; \
    MODULES="$(sed -e 's/#.*//' -e '/^[[:space:]]*$/d' /tmp/jlink-modules.txt \
               | awk '{print $1}' | sort -u | paste -sd, -)"; \
    echo "jlink modules: ${MODULES}"; \
    jlink \
      --add-modules "${MODULES}" \
      --include-locales=en \
      --strip-debug \
      --no-man-pages \
      --no-header-files \
      --compress=zip-6 \
      --generate-cds-archive \
      --output /javaruntime; \
    /javaruntime/bin/java --version
# --compress=zip-6, not zip-9: zip-9 saves under 2% for roughly three times the
# link time, and this runs on every build.
# --generate-cds-archive is mandatory, not an optimisation. A jlink runtime has
# no base CDS archive, and without one -XX:ArchiveClassesAtExit fails with
# "unsupported when base CDS archive is not loaded" — which the JVM reports as
# a *warning*, so the build would otherwise succeed and silently ship no CDS.


# --- Stage 3: CDS training run ---------------------------------------------
# Runs against the jlink runtime, not the full JDK, so the archive matches the
# runtime that will load it. `spring.context.exit=onRefresh` starts the whole
# context and exits immediately after refresh, so no external dependency is
# contacted and the run is hermetic.
# The application MUST be laid out at exactly the path it will occupy at
# runtime. CDS records absolute classpath entries, so training at /app and
# running at /opt/app produces "shared class paths mismatch" and the archive
# is silently ignored — a 450 ms regression that no build step would catch.
#
# The settings below exist solely to let the context refresh with no network.
# They are deliberately here rather than in an application-cds-training.yaml,
# for two reasons: they are a property of the build, not of the application,
# so shipping them inside the jar would invite someone to run with them; and
# keeping them in one file avoids five copies drifting apart.
#
# Every one of them is a *lie that must not change behaviour*. The datasource
# URL is syntactically valid and resolves to nothing; Hikari is lazy, so no
# connection is attempted. allow_jdbc_metadata_access=false is the load-bearing
# one — without it Hibernate opens a connection at boot purely to sniff the
# dialect, which is why an explicit dialect must be supplied alongside it.
# The AWS values satisfy eager credential and region resolution in the SDK's
# client builders; no SDK call is made, so they are never authenticated.
#
# The OTEL_ exports inside the RUN below are there for a different reason, and
# it is the interesting one. buildx's docker-container driver injects its own
# tracing configuration into every build step:
#
#   OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=unix:///dev/otel-grpc.sock
#   OTEL_EXPORTER_OTLP_TRACES_PROTOCOL=grpc
#   OTEL_TRACES_EXPORTER=otlp
#
# The OTLP gRPC exporter rejects a unix scheme outright, so the context failed
# to refresh and all five image builds failed -- from variables no file in this
# repository sets and no build argument passes.
#
# Two details cost real time and are worth writing down. They are exports in
# the RUN rather than ENV, because ENV does not win: BuildKit injects these over
# the top of it. And they must be the signal-specific TRACES_ names, because
# overriding only the generic OTEL_EXPORTER_OTLP_ENDPOINT changes nothing --
# the signal-specific variable takes precedence and keeps the unix socket. Both
# wrong versions look correct and neither has any effect, which is why this was
# settled by printing the environment inside a build rather than by reasoning
# about it.
#
# The host is deliberately .invalid. Disabling the SDK would be simpler and
# worse: the exporter would never be constructed, its classes would be missing
# from the archive, and the first traced request in production would pay the
# class-loading cost this stage exists to remove.
FROM ${JDK_IMAGE} AS cds
COPY --from=runtime /javaruntime /opt/java
COPY --from=exploded /exploded /opt/app
WORKDIR /opt/app
ENV AWS_REGION=us-east-1 \
    AWS_ACCESS_KEY_ID=cds-training \
    AWS_SECRET_ACCESS_KEY=cds-training \
    AWS_EC2_METADATA_DISABLED=true
RUN set -eux; \
    export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://cds-training.invalid:4318/v1/traces; \
    export OTEL_EXPORTER_OTLP_TRACES_PROTOCOL=http/protobuf; \
    export OTEL_EXPORTER_OTLP_ENDPOINT=http://cds-training.invalid:4318; \
    export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf; \
    /opt/java/bin/java \
      -XX:ArchiveClassesAtExit=/opt/app/application.jsa \
      -Dspring.context.exit=onRefresh \
      -Dspring.main.banner-mode=off \
      -Dspring.datasource.url=jdbc:postgresql://cds-training.invalid:5432/cds \
      -Dspring.datasource.username=cds \
      -Dspring.datasource.password=cds \
      -Dspring.datasource.hikari.initialization-fail-timeout=-1 \
      -Dspring.sql.init.mode=never \
      -Dspring.jpa.hibernate.ddl-auto=none \
      -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
      -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
      -Dspring.data.redis.host=cds-training.invalid \
      -Dspring.flyway.enabled=false \
      -jar /opt/app/application.jar; \
    test -s /opt/app/application.jsa
# `test -s` is the gate that turns a silent CDS failure into a failed build.


# --- Stage 4: runtime image -------------------------------------------------
FROM ${RUNTIME_BASE}

ARG SERVICE
ARG GIT_SHA=unknown
ARG BUILD_TIME=1970-01-01T00:00:00Z

LABEL org.opencontainers.image.title="starling-${SERVICE}" \
      org.opencontainers.image.source="https://github.com/starling/platform" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.created="${BUILD_TIME}" \
      org.opencontainers.image.base.name="${RUNTIME_BASE}"

# Layers are ordered least to most volatile, so an application-only change
# invalidates only the last and smallest of them.
COPY --from=runtime  /javaruntime            /opt/java
COPY --from=exploded /exploded/lib           /opt/app/lib
COPY --from=cds      /opt/app/application.jsa /opt/app/application.jsa
COPY --from=exploded /exploded/application.jar /opt/app/application.jar

ENV JAVA_HOME=/opt/java \
    LANG=C.UTF-8 \
    SERVICE_NAME=${SERVICE}

WORKDIR /opt/app
# Numeric, not `USER nonroot`. The name only exists on distroless, so a named USER silently
# welds this stage to one base and makes the RUNTIME_BASE argument a lie -- any substitute
# fails the build with "unable to find user nonroot". 65532 is the uid distroless gives that
# name, so the resulting image is identical there and still non-root everywhere else. This is
# what makes the documented fallback below a one-argument change rather than a second stage.
USER 65532:65532
EXPOSE 8080

# -XX:SharedArchiveFile is deliberately NOT -Xshare:on. A mismatched archive
# should degrade to a slower start, never refuse to boot: a failed rollout is
# far more expensive than 450 ms of startup.
# MaxRAMPercentage rather than -Xmx so one image honours whatever limit the
# pod spec sets, across all three tiers.
ENTRYPOINT ["/opt/java/bin/java", \
            "-XX:SharedArchiveFile=/opt/app/application.jsa", \
            "-XX:MaxRAMPercentage=75.0", \
            "-XX:+ExitOnOutOfMemoryError", \
            "-XX:+UseContainerSupport", \
            # --enable-native-access=ALL-UNNAMED is deliberately NOT set here,
            # despite Netty warning that restricted native calls "will be
            # blocked in a future release". Setting it costs more than it buys:
            # it becomes the jdk.module.enable.native.access property, which the
            # base CDS archive that `jlink --generate-cds-archive` produced does
            # not carry, so every start logs
            #   Mismatched values for property jdk.module.enable.native.access
            #   Disabling optimized module handling
            # and the full module graph is dropped from the archive. Verified:
            # image-verify's CDS check fails with the flag and passes without.
            # When a JDK actually enforces this, the fix is the JAR manifest
            # attribute Enable-Native-Access (JEP 472), which sets no system
            # property and so does not perturb CDS.
            "-Djava.security.egd=file:/dev/urandom", \
            "-jar", "/opt/app/application.jar"]


# ---------------------------------------------------------------------------
# Documented fallback (ADR-0010).
#
# The runtime base is an argument, so swapping it is the whole fallback:
#
#   docker build -f docker/Dockerfile \
#     --build-arg RUNTIME_BASE=eclipse-temurin:25-jre-alpine ...
#
# or, for Compose, `RUNTIME_BASE=eclipse-temurin:25-jre-alpine make up-app`.
#
# Two things make it work rather than merely look like it should. The USER above is numeric,
# so it does not depend on the base declaring a `nonroot` account; and the jlink runtime is
# copied to /opt/java and invoked by absolute path, so it is used in preference to whatever
# JDK the substitute base happens to ship. The image is larger and carries a shell and a
# package manager -- that is the cost, and it is why it is not the default.
#
# It exists for a reason that is concrete rather than hypothetical: gcr.io is unreachable
# from some corporate Docker daemons, and there the default base hangs on
# `load metadata for gcr.io/...` indefinitely with no error, because a blocked registry is
# indistinguishable from a slow one. An earlier version of this file kept the fallback as a
# commented recipe while asserting that an untested fallback is not a fallback; it was both.
# `make image-verify` runs against whatever RUNTIME_BASE produced, so the substitute is
# checked by the same gate as the default.
# ---------------------------------------------------------------------------
