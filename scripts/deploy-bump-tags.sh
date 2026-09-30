#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Bump the image tags in deploy/envs/dev/ to a SHA that has actually been
# published, and assert that none are left on a placeholder.
#
#   SERVICES='["gateway"]' WEB_PUBLISHED=true SHA=<sha> scripts/deploy-bump-tags.sh
#   scripts/deploy-bump-tags.sh --check      # assert only, change nothing
#
# WHY THIS EXISTS
#
# publish.yml carried this note:
#
#   "The remaining handoff step from docs/02-workflow.md -- a bot commit bumping
#    the image tag in deploy/envs/dev/ -- lands in Phase 7 with the manifests it
#    would edit. Adding it now would mean granting contents:write for a
#    directory that does not exist."
#
# Phase 7 landed the manifests. The handoff step did not land with them, and the
# deferral note stayed true-looking for long enough to stop being true. Every
# file in deploy/envs/dev/ kept the chart's placeholder `sha-0000000` (S19) and
# pointed at `ghcr.io/starling/...`, a GitHub namespace that does not exist.
#
# Nothing caught it, because every consumer overrides the image reference before
# using it: kind-deploy.sh passes --set image.repository and --set image.tag,
# and helm-validate.sh lints with --set image.repository=r. The only consumer
# that reads what is committed is ArgoCD -- which is to say, the GitOps path, the
# one Tier S depends on and the one no gate had ever exercised. On the local kind
# cluster every application pod sat in ImagePullBackOff for fifteen hours while
# every check was green.
#
# `--check` is the gate that keeps it fixed. It is a separate mode rather than a
# separate script so that the rule and the writer cannot drift apart.
#
# One image is genuinely unpublishable today: `web` has never been built by CI,
# so there is no SHA to pin it to. That is recorded in $ENV_DIR/.unpublished
# rather than waived in code, and the bump path deletes the entry the first time
# it writes a real tag. A waiver that cannot expire is just a disabled test.
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."

PLACEHOLDER_TAG="sha-0000000"
UNPUBLISHED_FILE_NAME=".unpublished"
CHECK_ONLY=0
LIST_ONLY=0
case "${1:-}" in
  --check) CHECK_ONLY=1 ;;
  --list-dirs) LIST_ONLY=1 ;;
esac

die() { echo "::error::$*" >&2; exit 1; }

# Which overlays this script owns: discovered, not listed.
#
# It used to be one directory, defaulting to deploy/envs/dev, and that was
# correct for exactly as long as there were two overlays. Adding
# deploy/envs/sandbox in S78 made it wrong silently: the sandbox manifests kept
# whatever SHA they were created with, no bump PR ever touched them, and every
# tag stayed valid and pullable -- so nothing failed and the 180-minute session
# would have demonstrated code from before the overlay existed.
#
# Deriving the list means the next tier cannot be forgotten the same way. prod
# is excluded because it is promoted by a deliberate pull request, not bumped
# by automation, and this script must never write to it.
if [ -n "${ENV_DIR:-}" ]; then
  ENV_DIRS=("$ENV_DIR")
else
  ENV_DIRS=()
  for d in deploy/envs/*/; do
    d="${d%/}"
    [ "$(basename "$d")" = "prod" ] && continue
    ENV_DIRS+=("$d")
  done
fi

[ "${#ENV_DIRS[@]}" -gt 0 ] || die "no environment overlays found under deploy/envs"

for d in "${ENV_DIRS[@]}"; do
  [ -d "$d" ] || die "no such directory: ${d}"
  [ "$(basename "$d")" = "prod" ] &&
    die "refusing to rewrite deploy/envs/prod: Tier P is promoted by pull request, never by automation"
done

if [ "$LIST_ONLY" -eq 1 ]; then
  printf '%s\n' "${ENV_DIRS[@]}"
  exit 0
fi

# The image a values file refers to is its own `repository:` basename, not its
# filename. They differ on purpose: tweet-indexer.yaml runs the tweet-service
# image in a different role, so bumping tweet-service has to bump both files.
# Reading the repository line keeps that true without a hardcoded table that
# would silently rot the next time a service is added.
image_of() {
  local f="$1" repo
  repo=$(awk '/^[[:space:]]*repository:[[:space:]]*/ {print $2; exit}' "$f")
  [ -n "$repo" ] || return 1
  printf '%s\n' "${repo##*/}"
}

tag_of() {
  awk '/^[[:space:]]*tag:[[:space:]]*/ {print $2; exit}' "$1"
}

# Set per overlay as the loops below move between them, because a waiver is a
# statement about one environment's manifests and must not leak into another's.
UNPUBLISHED=""

is_waived() {
  [ -f "$UNPUBLISHED" ] || return 1
  grep -vE '^[[:space:]]*(#|$)' "$UNPUBLISHED" | grep -qxF "$1"
}

if [ "$CHECK_ONLY" -eq 1 ]; then
  bad=0
  for ENV_DIR in "${ENV_DIRS[@]}"; do
  UNPUBLISHED="${ENV_DIR}/${UNPUBLISHED_FILE_NAME}"
  for f in "$ENV_DIR"/*.yaml; do
    [ "$(basename "$f")" = "values.yaml" ] && continue
    tag=$(tag_of "$f")
    [ -n "$tag" ] || continue
    [ "$tag" = "$PLACEHOLDER_TAG" ] || continue
    img=$(image_of "$f") || img=""
    if [ -n "$img" ] && is_waived "$img"; then
      echo "  WAIVED        ${f} pins ${PLACEHOLDER_TAG}; ${img} is listed in ${UNPUBLISHED}"
      continue
    fi
    echo "  PLACEHOLDER   ${f} still pins ${PLACEHOLDER_TAG}"
    bad=1
  done

  # The registry namespace is checked too, because a correct tag under a wrong
  # namespace fails in exactly the same way and reads as if it were fine.
  while IFS= read -r line; do
    echo "  BAD NAMESPACE ${line}"
    bad=1
  done < <(grep -rn "ghcr\.io/starling/" "$ENV_DIR" 2>/dev/null || true)
  done

  [ "$bad" -eq 0 ] ||
    die "deploy manifests in ${ENV_DIRS[*]} would make ArgoCD pull an image that" \
        "does not exist. This is the state that left every pod in" \
        "ImagePullBackOff while every check was green."

  echo "All image references in ${ENV_DIRS[*]} are real: no placeholder tags, no dead namespace."
  exit 0
fi

SERVICES="${SERVICES:?SERVICES (a JSON array) is required}"
SHA="${SHA:?SHA is required}"
WEB_PUBLISHED="${WEB_PUBLISHED:-false}"

echo "$SERVICES" | jq -e 'type == "array"' >/dev/null 2>&1 ||
  die "SERVICES is not a JSON array: ${SERVICES}"

published=$(jq -r '.[]' <<<"$SERVICES")
[ "$WEB_PUBLISHED" = "true" ] && published=$(printf '%s\nweb\n' "$published")

changed=0
for ENV_DIR in "${ENV_DIRS[@]}"; do
UNPUBLISHED="${ENV_DIR}/${UNPUBLISHED_FILE_NAME}"
for f in "$ENV_DIR"/*.yaml; do
  [ "$(basename "$f")" = "values.yaml" ] && continue
  img=$(image_of "$f") || continue
  grep -qxF "$img" <<<"$published" || continue

  current=$(tag_of "$f")
  want="sha-${SHA}"
  if [ "$current" = "$want" ]; then
    echo "  unchanged     $(basename "$f") (already ${want})"
    continue
  fi

  # Anchored on the tag key rather than on the old value: a sed that matched the
  # placeholder would stop working the moment the first real bump landed, which
  # is the kind of one-shot fix that looks permanent.
  tmp="${f}.tmp.$$"
  awk -v want="$want" '
    !done && /^[[:space:]]*tag:[[:space:]]*/ {
      match($0, /^[[:space:]]*/)
      printf "%stag: %s\n", substr($0, 1, RLENGTH), want
      done = 1
      next
    }
    { print }
  ' "$f" > "$tmp"
  mv "$tmp" "$f"
  echo "  bumped        $(basename "$f") ${current} -> ${want}"
  changed=$((changed + 1))

  # The image now exists, so the waiver has served its purpose. Removing it here
  # rather than by hand is what stops it becoming permanent.
  if is_waived "$img"; then
    # grep -v exits 1 when it emits nothing, which is exactly the case where the
    # last waiver is being cleared. That is success here, not failure.
    grep -vxF "$img" "$UNPUBLISHED" > "${UNPUBLISHED}.tmp.$$" || true
    mv "${UNPUBLISHED}.tmp.$$" "$UNPUBLISHED"
    echo "  un-waived     ${img} removed from ${UNPUBLISHED}"
  fi
done
done

echo "${changed} file(s) updated in ${ENV_DIRS[*]}."
