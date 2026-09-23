#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Derive the jlink module set for a service — ADR-0010.
#
#   scripts/derive-jlink-modules.sh <service>
#
# Unions two sources and diffs the result against docker/jlink-modules.txt:
#
#   1. jdeps --print-module-deps   static references only
#   2. -Xlog:class+load on a real  what the JVM actually loaded
#      context refresh
#
# Neither is sufficient. Measured against gateway on 2026-09-23, jdeps found
# 13 modules and the runtime loaded 20, overlapping on only 8. Shipping the
# jdeps set alone would have produced a runtime with no java.logging.
#
# This script never edits the allow-list. It prints a diff and exits non-zero
# if the committed list is missing something, so that adding a module is a
# reviewed change with a written justification rather than a silent one.
# ---------------------------------------------------------------------------
set -euo pipefail

SERVICE="${1:?usage: derive-jlink-modules.sh <service>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ALLOW_LIST="${ROOT}/docker/jlink-modules.txt"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

JAR="${ROOT}/services/${SERVICE}/build/libs/${SERVICE}.jar"
[[ -f "${JAR}" ]] || { echo "missing ${JAR} — run: ./gradlew :services:${SERVICE}:bootJar"; exit 1; }

# Use the Gradle toolchain JDK, never whatever `java` happens to be on PATH.
# Getting this wrong yields UnsupportedClassVersionError, which looks like a
# module problem and is not one.
JAVA_HOME_DIR="$(ls -d "${HOME}"/.gradle/jdks/*25*/*/Contents/Home 2>/dev/null | head -1 || true)"
[[ -z "${JAVA_HOME_DIR}" ]] && JAVA_HOME_DIR="$(ls -d "${HOME}"/.gradle/jdks/*25*/* 2>/dev/null | head -1 || true)"
[[ -z "${JAVA_HOME_DIR}" ]] && JAVA_HOME_DIR="${JAVA_HOME:-}"
[[ -x "${JAVA_HOME_DIR}/bin/java" ]] || { echo "no Java 25 toolchain found"; exit 1; }
echo "toolchain: ${JAVA_HOME_DIR}"

# Copy to a fixed name first: `extract` names the launcher after its source.
cp "${JAR}" "${WORK}/application.jar"
"${JAVA_HOME_DIR}/bin/java" -Djarmode=tools -jar "${WORK}/application.jar" \
  extract --destination "${WORK}/x" >/dev/null

# --- source 1: static analysis ---------------------------------------------
# --ignore-missing-deps is required (optional dependencies are absent by
# design) but it is also what makes jdeps quiet about things it cannot see,
# which is precisely why source 2 exists.
"${JAVA_HOME_DIR}/bin/jdeps" \
  --multi-release 25 \
  --ignore-missing-deps \
  --print-module-deps \
  --recursive \
  -cp "${WORK}/x/lib/*" "${WORK}/x/application.jar" 2>/dev/null \
  | tr ',' '\n' | sed '/^$/d' | sort -u > "${WORK}/jdeps.txt" || true

# --- source 2: observed at runtime -----------------------------------------
"${JAVA_HOME_DIR}/bin/java" \
  -Xlog:class+load=info:file="${WORK}/load.log" \
  -Dspring.context.exit=onRefresh \
  -Dspring.main.banner-mode=off \
  -jar "${WORK}/x/application.jar" >/dev/null 2>&1 \
  || { echo "training run failed — the service cannot start standalone"; exit 1; }

grep -o 'source: jrt:/[a-z0-9._]*' "${WORK}/load.log" \
  | sed 's|.*jrt:/||' | sort -u > "${WORK}/observed.txt"

sort -u "${WORK}/jdeps.txt" "${WORK}/observed.txt" > "${WORK}/union.txt"

sed -e 's/#.*//' -e '/^[[:space:]]*$/d' "${ALLOW_LIST}" \
  | awk '{print $1}' | sort -u > "${WORK}/allowed.txt"

printf '\n%-28s %s\n' "jdeps (static)"      "$(wc -l < "${WORK}/jdeps.txt" | tr -d ' ')"
printf '%-28s %s\n'   "class+load (runtime)" "$(wc -l < "${WORK}/observed.txt" | tr -d ' ')"
printf '%-28s %s\n'   "union"                "$(wc -l < "${WORK}/union.txt" | tr -d ' ')"
printf '%-28s %s\n\n' "committed allow-list" "$(wc -l < "${WORK}/allowed.txt" | tr -d ' ')"

echo "missed by jdeps, seen at runtime:"
comm -13 "${WORK}/jdeps.txt" "${WORK}/observed.txt" | sed 's/^/  + /'

MISSING="$(comm -23 "${WORK}/union.txt" "${WORK}/allowed.txt" || true)"
EXTRA="$(comm -13 "${WORK}/union.txt" "${WORK}/allowed.txt" || true)"

if [[ -n "${EXTRA}" ]]; then
  echo
  echo "in the allow-list but not detected (fine if justified there — e.g. jdk.crypto.ec,"
  echo "which no training run exercises but whose absence breaks TLS in production):"
  echo "${EXTRA}" | sed 's/^/  ? /'
fi

if [[ -n "${MISSING}" ]]; then
  echo
  echo "NOT in docker/jlink-modules.txt — add them, each with a justification:"
  echo "${MISSING}" | sed 's/^/  ! /'
  exit 1
fi

echo
echo "allow-list covers every detected module."
