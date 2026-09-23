#!/usr/bin/env bash
# Runs the Playwright suite against the Compose stack.
#
# Playwright runs inside its own container rather than on the host for two reasons: the host
# node here is 18, which both Next 15 and recent Playwright refuse, and the browsers are a
# ~500 MB download that has no business living in a developer's home directory. The official
# image is version-pinned to the resolved @playwright/test, because a mismatch between the
# client library and the bundled browsers is a hard error rather than a warning.
#
# The container joins the Compose network and addresses services by name, rather than going
# back out to a published host port. Those ports are bound to 127.0.0.1 on purpose, so they
# are not reachable from another container at all; and using the network names means the
# suite is indifferent to which host ports happen to be free.
#
# The stack must already be up -- `make up-app`. This script deliberately does not start one:
# starting a stack it does not own means it would also have to decide whether to tear down
# one that somebody else was using.
set -euo pipefail

cd "$(dirname "$0")/.."

NETWORK="${COMPOSE_NETWORK:-twitter-clone_default}"

if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
  echo "network '$NETWORK' does not exist -- is the stack up? try: make up-app" >&2
  exit 1
fi

version="$(python3 -c "
import json
print(json.load(open('web/package-lock.json'))['packages']['node_modules/@playwright/test']['version'])
")"
image="mcr.microsoft.com/playwright:v${version}-noble"

echo "==> playwright ${version} on network ${NETWORK}"

# --ipc=host: Chromium's default 64 MB /dev/shm makes tabs crash under load, and that crash
# surfaces as an unrelated-looking test timeout.
#
# node_modules is a named volume rather than part of the bind mount. The host tree is
# installed by an alpine container (musl) while this image is glibc, and letting the two
# share one directory leaves whichever ran last with binaries the other cannot load.
exec docker run --rm -t \
  --ipc=host \
  --network "$NETWORK" \
  -v "$PWD/web:/work" \
  -v twitterclone-e2e-node-modules:/work/node_modules \
  -w /work \
  -e "WEB_URL=${WEB_URL:-http://web:3000}" \
  -e "GATEWAY_URL=${GATEWAY_URL:-http://gateway:8080}" \
  -e CI="${CI:-}" \
  "$image" \
  sh -lc 'npm ci --no-audit --no-fund >/dev/null 2>&1 && exec npx playwright test "$@"' -- "$@"
