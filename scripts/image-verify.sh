#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Smoke-verify a built image — ADR-0010.
#
#   scripts/image-verify.sh <image[:tag]>
#
# This is the control that makes the whole jlink approach safe. `jdeps` misses
# reflective module use, so a missing module is invisible until a code path
# runs. Testing the Gradle classpath proves nothing about the image, because
# the Gradle classpath runs on a full JDK with every module present.
#
# So: exercise the actual container, on the paths most likely to trip a
# missing module.
#
#   1. the context refreshes at all         java.desktop, java.beans
#   2. actuator health responds             Tomcat, Jackson, Micrometer
#   3. a real TLS handshake to AWS          jdk.crypto.ec — the classic omission,
#                                           which fails ONLY against ECDHE peers
#   4. the CDS archive was actually mapped  a silent CDS failure is only a warning
#   5. no shell, no package manager         the distroless promise, asserted
#   6. not running as root
#
# NOTE ON TECHNIQUE: `docker exec` is useless against a distroless image —
# there is no shell and no coreutils, so `docker exec test -e /bin/sh` returns
# a runtime error that is very easy to mistake for a passing check. An earlier
# version of this script did exactly that and reported a false PASS. It now
# uses fresh `docker run --entrypoint` containers and `docker export`, neither
# of which needs anything to exist inside the image.
# ---------------------------------------------------------------------------
set -uo pipefail

IMAGE="${1:?usage: image-verify.sh <image[:tag]>}"
NAME="verify-$$"
PORT="$(awk 'BEGIN { srand(); print 18000 + int(rand() * 900) }')"
WORK="$(mktemp -d)"

cleanup() { docker rm -f "${NAME}" >/dev/null 2>&1; rm -rf "${WORK}"; }
trap cleanup EXIT

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILED=1; }
FAILED=0

echo
echo "verifying ${IMAGE}"

# --- 1 + 2: the service actually starts and serves -------------------------
# -Xlog:cds on the real container: the only invocation whose classpath matches
# the one the archive was recorded against, so the only one whose verdict means
# anything. A separate `java -version` probe reports a spurious mismatch.
#
# SERVER_PORT is pinned rather than read from each service's application.yaml
# (8080, 8081, 8082, …), which keeps this script service-agnostic and, more
# usefully, exercises the env-var override path that Kubernetes itself uses.
#
# The datasource and AWS settings mirror the CDS training stage: they let the
# context refresh with no network. See docker/Dockerfile for why each is
# needed. Without them this check would only ever pass for services that have
# no persistence, which is exactly the subset that cannot go wrong.
docker run -d --name "${NAME}" -p "${PORT}:8080" \
  -e SERVER_PORT=8080 \
  -e SPRING_PROFILES_ACTIVE=smoke \
  -e JAVA_TOOL_OPTIONS="-Xlog:cds=info" \
  -e AWS_REGION=us-east-1 \
  -e AWS_ACCESS_KEY_ID=verify \
  -e AWS_SECRET_ACCESS_KEY=verify \
  -e AWS_EC2_METADATA_DISABLED=true \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://verify.invalid:5432/verify \
  -e SPRING_DATASOURCE_USERNAME=verify \
  -e SPRING_DATASOURCE_PASSWORD=verify \
  -e SPRING_SQL_INIT_MODE=never \
  -e SPRING_JPA_HIBERNATE_DDL_AUTO=none \
  -e SPRING_JPA_DATABASE_PLATFORM=org.hibernate.dialect.PostgreSQLDialect \
  -e SPRING_JPA_PROPERTIES_HIBERNATE_BOOT_ALLOW_JDBC_METADATA_ACCESS=false \
  -e SPRING_DATA_REDIS_HOST=verify.invalid \
  -e SPRING_FLYWAY_ENABLED=false \
  -e MANAGEMENT_HEALTH_DB_ENABLED=false \
  -e MANAGEMENT_HEALTH_REDIS_ENABLED=false \
  "${IMAGE}" >/dev/null

for _ in $(seq 1 60); do
  curl -fsS "http://localhost:${PORT}/actuator/health" >/dev/null 2>&1 && break
  sleep 1
done

if curl -fsS "http://localhost:${PORT}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
  pass "context refreshed and /actuator/health is UP"
else
  fail "service did not become healthy"
  echo "--- container log ---"
  docker logs "${NAME}" 2>&1 | tail -40
  exit 1
fi

# --- 3: a real TLS handshake, using the image's own runtime ----------------
# Compiled on the host with the Gradle toolchain, mounted in, executed by the
# jlink runtime inside the image. A genuine handshake against a live AWS
# endpoint, not an assertion that a module name is present: a runtime missing
# jdk.crypto.ec lists its modules perfectly happily and then fails on ECDHE.
JDK="$(ls -d "${HOME}"/.gradle/jdks/*25*/*/Contents/Home 2>/dev/null | head -1)"
[[ -z "${JDK}" ]] && JDK="$(ls -d "${HOME}"/.gradle/jdks/*25*/* 2>/dev/null | head -1)"

if [[ -x "${JDK}/bin/javac" ]]; then
  cat > "${WORK}/TlsProbe.java" <<'JAVA'
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class TlsProbe {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15)).build();
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(args[0])).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        // Any HTTP status proves the handshake completed. AWS answers an
        // unsigned GET with 400 or 404, which is a pass for this purpose.
        System.out.println("handshake ok, status " + response.statusCode()
                + ", tls " + response.sslSession().map(javax.net.ssl.SSLSession::getProtocol).orElse("?"));
    }
}
JAVA
  "${JDK}/bin/javac" -d "${WORK}/probe" "${WORK}/TlsProbe.java" 2>/dev/null
fi

if [[ -d "${WORK}/probe" ]] && \
   docker run --rm -v "${WORK}/probe:/probe:ro" --entrypoint /opt/java/bin/java \
     "${IMAGE}" -cp /probe TlsProbe "https://dynamodb.us-east-1.amazonaws.com" 2>/dev/null \
     | grep -q "handshake ok"; then
  pass "live TLS handshake to an AWS endpoint from inside the image"
elif docker run --rm --entrypoint /opt/java/bin/java "${IMAGE}" --list-modules 2>/dev/null \
     | grep -q '^jdk\.crypto\.ec@'; then
  # Degraded check for offline CI. Weaker, and labelled as such rather than
  # reported as if it were equivalent.
  pass "jdk.crypto.ec is linked (static check only — no live handshake attempted)"
else
  fail "jdk.crypto.ec is MISSING — TLS to AWS will fail at handshake"
fi

# --- 4: CDS actually mapped -------------------------------------------------
# -XX:SharedArchiveFile degrades silently by design: correct at runtime, wrong
# at build time. Assert the mapping here so a lost archive fails the pipeline
# rather than quietly costing 450 ms on every pod start.
CDS_PROBE="$(docker logs "${NAME}" 2>&1 | grep -i '\[cds' | head -60)"

if grep -qiE 'unable to (use|map|open)|disabling|archive failed|mismatch|shared archive file has' <<<"${CDS_PROBE}"; then
  fail "CDS archive present but NOT usable — the startup gain is silently lost"
  grep -iE 'unable|disabling|mismatch|has been' <<<"${CDS_PROBE}" | head -3 | sed 's/^/        /'
elif grep -qiE 'mapped|opened archive|shared file|using archive' <<<"${CDS_PROBE}"; then
  pass "CDS archive mapped by the image runtime"
else
  fail "could not confirm the CDS archive was mapped"
  echo "${CDS_PROBE}" | head -5 | sed 's/^/        /'
fi

# --- 5: the distroless promise ---------------------------------------------
docker export "${NAME}" 2>/dev/null | tar -t 2>/dev/null > "${WORK}/fs.txt"
BANNED="$(grep -E '^(bin/sh|bin/bash|bin/busybox|bin/dash|usr/bin/apt|usr/bin/dpkg|usr/bin/perl|usr/bin/wget|usr/bin/curl)$' \
  "${WORK}/fs.txt" || true)"
ENTRIES="$(wc -l < "${WORK}/fs.txt" | tr -d ' ')"
if [[ "${ENTRIES}" -lt 10 ]]; then
  fail "could not export the container filesystem — check cannot be trusted"
elif [[ -z "${BANNED}" ]]; then
  pass "no shell and no package manager (${ENTRIES} filesystem entries scanned)"
else
  fail "runtime image contains: $(tr '\n' ' ' <<<"${BANNED}")"
fi

# --- 6: never root ----------------------------------------------------------
USER_CFG="$(docker image inspect "${IMAGE}" --format '{{.Config.User}}')"
# USER may be a bare name, a bare uid, or uid:gid -- the Dockerfile uses the last
# of those on purpose, because the name `nonroot` exists only on distroless and a
# named USER silently welds the image to one base. Only the user half decides
# whether this is root, so split on the colon before judging. An empty value is
# root by omission, which is the case most worth catching.
USER_ID="${USER_CFG%%:*}"
if [[ -z "${USER_CFG}" || "${USER_ID}" == "root" || "${USER_ID}" == "0" ]]; then
  fail "runs as root (USER '${USER_CFG}')"
elif [[ "${USER_ID}" =~ ^[0-9]+$ || "${USER_ID}" =~ ^[a-z_][a-z0-9_-]*$ ]]; then
  pass "runs as ${USER_CFG}, not root"
else
  fail "cannot tell who this runs as (USER '${USER_CFG}')"
fi

echo
if [[ "${FAILED}" -eq 0 ]]; then echo "  image verification passed."; else echo "  image verification FAILED."; fi
exit "${FAILED}"
