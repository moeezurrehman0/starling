#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# Waits until the Tier L application stack answers.
#
# This exists because the services run on a distroless image: no shell, no curl, no wget, and
# the JDK ships no HTTP client binary, so a Compose `healthcheck` for them cannot be written
# honestly. Polling from the host is the one definition of "ready" that works identically for
# `make up-app`, the Playwright suite and CI, so there is only one.
#
# It probes the *edge*, not each service, for the first four: a gateway that answers a routed
# request has necessarily resolved DNS, opened a connection and got a response from the
# upstream, which is a stronger statement than any upstream's own /actuator/health. The two
# it cannot reach that way -- fanout-worker has no route, and web is not behind the gateway --
# are probed directly.
#
#   tools/wait-for-stack.sh            # default timeout
#   TIMEOUT=300 tools/wait-for-stack.sh
set -euo pipefail

TIMEOUT="${TIMEOUT:-240}"
GATEWAY="${GATEWAY:-http://localhost:8080}"
WEB="${WEB:-http://localhost:3000}"
FANOUT="${FANOUT:-http://localhost:8084}"

# name|url|acceptable status codes
#
# 401 counts as up for a protected route. The check is "the edge routed this and an upstream
# answered", and a 401 proves that more completely than a 200 would -- it means the JWKS
# fetch from user-service also succeeded. Insisting on 200 here would mean minting a token
# just to decide whether the stack had started.
PROBES=(
  "user-service|${GATEWAY}/v1/jwks|200"
  "tweet-service|${GATEWAY}/v1/search/tweets?q=readiness|200"
  "timeline-service|${GATEWAY}/v1/timelines/home|401"
  "fanout-worker|${FANOUT}/actuator/health|200"
  "web|${WEB}/api/health|200"
)

status_of() {
  # --max-time bounds a hung connection; without it a service that accepts the TCP connection
  # and then never responds -- which is exactly what a JVM mid-startup does -- makes this
  # script hang past its own timeout.
  #
  # The output is captured rather than fronting an `|| echo 000`: on a refused connection
  # curl both writes "000" and exits non-zero, so the obvious spelling concatenates the two
  # and reports "000000".
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$1" 2>/dev/null)" || true
  echo "${code:-000}"
}

deadline=$(( $(date +%s) + TIMEOUT ))
pending=("${PROBES[@]}")

while [ ${#pending[@]} -gt 0 ]; do
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "not ready after ${TIMEOUT}s:" >&2
    for probe in "${pending[@]}"; do
      IFS='|' read -r name url want <<<"$probe"
      echo "  ${name}: ${url} returned $(status_of "$url"), wanted one of ${want}" >&2
    done
    echo >&2
    echo "  docker compose --profile app logs --tail 50" >&2
    exit 1
  fi

  still=()
  for probe in "${pending[@]}"; do
    IFS='|' read -r name url want <<<"$probe"
    code="$(status_of "$url")"
    if [[ "|${want}|" == *"|${code}|"* ]]; then
      echo "ready: ${name}"
    else
      still+=("$probe")
    fi
  done
  pending=("${still[@]:-}")
  # The :- above is load-bearing under `set -u`: expanding an empty array is an unbound
  # variable error on the bash 3.2 that ships with macOS, so the success path would exit 1.
  [ -z "${pending[0]:-}" ] && pending=()

  [ ${#pending[@]} -gt 0 ] && sleep 3
done

echo "stack ready"
