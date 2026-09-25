#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Two-sided self-test for scripts/deploy-bump-tags.sh.
#
# The bug this script exists to prevent was invisible for the length of a phase
# because every gate that touched these files overrode the fields that were
# wrong. So the tests below operate on real throwaway directories and assert the
# file contents afterwards, rather than trusting the script's own report.
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."
SCRIPT="$PWD/scripts/deploy-bump-tags.sh"

WORK=$(mktemp -d)
trap 'rm -rf "${WORK}"' EXIT

pass=0
fail=0

ok()   { printf 'ok    %s\n' "$1"; pass=$((pass + 1)); }
bad()  { printf 'FAIL  %s\n%s\n' "$1" "$(sed 's/^/        /' <<<"${2:-}")"; fail=$((fail + 1)); }

# A miniature deploy/envs/dev. tweet-indexer deliberately carries the
# tweet-service image, which is the case a filename-based mapping gets wrong.
make_env() {
  local d="$1"
  rm -rf "$d"; mkdir -p "$d"
  cat > "$d/values.yaml" <<'Y'
image:
  repository: ghcr.io/owner/starling
Y
  cat > "$d/gateway.yaml" <<'Y'
name: gateway
image:
  repository: ghcr.io/owner/starling/gateway
  tag: sha-0000000
Y
  cat > "$d/tweet-service.yaml" <<'Y'
name: tweet-service
image:
  repository: ghcr.io/owner/starling/tweet-service
  tag: sha-0000000
Y
  cat > "$d/tweet-indexer.yaml" <<'Y'
name: tweet-indexer
image:
  repository: ghcr.io/owner/starling/tweet-service
  tag: sha-0000000
Y
  cat > "$d/web.yaml" <<'Y'
name: web
image:
  repository: ghcr.io/owner/starling/web
  tag: sha-0000000
Y
}

tag_in() { awk '/^[[:space:]]*tag:/ {print $2; exit}' "$1"; }

echo "-- bumping --"

D="$WORK/dev"; make_env "$D"
out=$(ENV_DIR="$D" SERVICES='["gateway"]' SHA=abc123 "$SCRIPT" 2>&1) || bad "bump gateway exited non-zero" "$out"
if [ "$(tag_in "$D/gateway.yaml")" = "sha-abc123" ]; then
  ok "the selected service is bumped"
else
  bad "gateway was not bumped" "$(cat "$D/gateway.yaml")"
fi
if [ "$(tag_in "$D/web.yaml")" = "sha-0000000" ]; then
  ok "an unpublished service is left alone"
else
  bad "web was bumped without being published" "$(cat "$D/web.yaml")"
fi

# The case a filename-based mapping gets wrong.
D="$WORK/dev2"; make_env "$D"
ENV_DIR="$D" SERVICES='["tweet-service"]' SHA=def456 "$SCRIPT" >/dev/null 2>&1
if [ "$(tag_in "$D/tweet-service.yaml")" = "sha-def456" ] &&
   [ "$(tag_in "$D/tweet-indexer.yaml")" = "sha-def456" ]; then
  ok "tweet-indexer follows the tweet-service image, not its own filename"
else
  bad "tweet-indexer was not bumped with tweet-service" \
      "$(grep -H tag: "$D"/tweet-*.yaml)"
fi

D="$WORK/dev3"; make_env "$D"
ENV_DIR="$D" SERVICES='[]' WEB_PUBLISHED=true SHA=aaa111 "$SCRIPT" >/dev/null 2>&1
if [ "$(tag_in "$D/web.yaml")" = "sha-aaa111" ]; then
  ok "web is bumped when WEB_PUBLISHED=true"
else
  bad "web was not bumped" "$(cat "$D/web.yaml")"
fi

# Anchored on the key, not the old value -- so it still works after the first
# real bump, which a placeholder-matching sed would not.
D="$WORK/dev4"; make_env "$D"
ENV_DIR="$D" SERVICES='["gateway"]' SHA=one "$SCRIPT" >/dev/null 2>&1
ENV_DIR="$D" SERVICES='["gateway"]' SHA=two "$SCRIPT" >/dev/null 2>&1
if [ "$(tag_in "$D/gateway.yaml")" = "sha-two" ]; then
  ok "a second bump replaces a real tag, not just the placeholder"
else
  bad "second bump did not apply" "$(cat "$D/gateway.yaml")"
fi

D="$WORK/dev5"; make_env "$D"
ENV_DIR="$D" SERVICES='["gateway"]' SHA=same "$SCRIPT" >/dev/null 2>&1
out=$(ENV_DIR="$D" SERVICES='["gateway"]' SHA=same "$SCRIPT" 2>&1)
if grep -q "unchanged" <<<"$out"; then
  ok "re-running with the same SHA is a no-op"
else
  bad "idempotent re-run was not reported as unchanged" "$out"
fi

echo "-- failing direction (the point of this file) --"

D="$WORK/chk1"; make_env "$D"
rc=0; out=$(ENV_DIR="$D" "$SCRIPT" --check 2>&1) || rc=$?
if [ "$rc" -ne 0 ]; then
  ok "--check rejects a placeholder tag"
else
  bad "--check passed a directory full of sha-0000000" "$out"
fi
if grep -q "PLACEHOLDER" <<<"$out"; then
  ok "--check names the offending file"
else
  bad "--check did not say which file" "$out"
fi

# A correct tag under a namespace that does not exist fails identically at
# runtime and reads as though it were fine.
D="$WORK/chk2"; make_env "$D"
ENV_DIR="$D" SERVICES='["gateway","tweet-service"]' WEB_PUBLISHED=true SHA=real "$SCRIPT" >/dev/null 2>&1
sed -i.bak 's|ghcr.io/owner/starling/gateway|ghcr.io/starling/gateway|' "$D/gateway.yaml"; rm -f "$D"/*.bak
rc=0; out=$(ENV_DIR="$D" "$SCRIPT" --check 2>&1) || rc=$?
if [ "$rc" -ne 0 ]; then
  ok "--check rejects the dead ghcr.io/starling namespace even with a real tag"
else
  bad "--check passed a nonexistent registry namespace" "$out"
fi

D="$WORK/chk3"; make_env "$D"
ENV_DIR="$D" SERVICES='["gateway","tweet-service"]' WEB_PUBLISHED=true SHA=real "$SCRIPT" >/dev/null 2>&1
rc=0; out=$(ENV_DIR="$D" "$SCRIPT" --check 2>&1) || rc=$?
if [ "$rc" -eq 0 ]; then
  ok "--check passes once every tag is real"
else
  bad "--check failed a correct directory" "$out"
fi

echo "-- the waiver, and its expiry --"

D="$WORK/wv1"; make_env "$D"
ENV_DIR="$D" SERVICES='["gateway","tweet-service"]' SHA=real "$SCRIPT" >/dev/null 2>&1
printf '# never built yet\nweb\n' > "$D/.unpublished"
rc=0; out=$(ENV_DIR="$D" "$SCRIPT" --check 2>&1) || rc=$?
if [ "$rc" -eq 0 ]; then
  ok "--check permits a placeholder for a waived image"
else
  bad "a waived image still failed --check" "$out"
fi
if grep -q "WAIVED" <<<"$out"; then
  ok "--check says out loud that it waived something"
else
  bad "the waiver was silent" "$out"
fi

# The waiver must cover one name, not the concept of placeholders.
D="$WORK/wv2"; make_env "$D"
printf 'web\n' > "$D/.unpublished"
rc=0; out=$(ENV_DIR="$D" "$SCRIPT" --check 2>&1) || rc=$?
if [ "$rc" -ne 0 ]; then
  ok "waiving web does not waive gateway"
else
  bad "one waiver excused every placeholder" "$out"
fi

D="$WORK/wv3"; make_env "$D"
printf 'web\n' > "$D/.unpublished"
ENV_DIR="$D" SERVICES='[]' WEB_PUBLISHED=true SHA=first "$SCRIPT" >/dev/null 2>&1 || true
if grep -qxF web "$D/.unpublished"; then
  bad "the waiver survived its own expiry condition" "$(cat "$D/.unpublished")"
else
  ok "bumping a waived image removes it from .unpublished"
fi

rc=0; out=$(ENV_DIR="$WORK/nope" "$SCRIPT" --check 2>&1) || rc=$?
if [ "$rc" -ne 0 ]; then
  ok "a missing env directory is an error, not a silent pass"
else
  bad "missing directory passed" "$out"
fi

rc=0; out=$(ENV_DIR="$WORK/dev" SERVICES='gateway' SHA=x "$SCRIPT" 2>&1) || rc=$?
if [ "$rc" -ne 0 ]; then
  ok "SERVICES that is not a JSON array is rejected"
else
  bad "a bare string was accepted as SERVICES" "$out"
fi

echo "-- the real repository --"

rc=0; out=$("$SCRIPT" --check 2>&1) || rc=$?
if [ "$rc" -eq 0 ]; then
  ok "deploy/envs/dev passes --check as committed"
else
  bad "the committed dev manifests would not pull" "$out"
fi

echo
printf '%s passed, %s failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
