#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Print the distinct container images a tier is currently pinned to.
#
#   scripts/deployed-images.sh [env]        # default: dev
#   scripts/deployed-images.sh dev --json   # as a GitHub Actions matrix
#
# Reads `image.repository` and `image.tag` out of deploy/envs/<env>/*.yaml and
# emits `repository:tag`, deduplicated -- two services can share an image, as
# tweet-indexer and tweet-service do, and scanning it twice is just slower.
#
# WHY THIS READS THE ENVIRONMENT AND NOT THE BUILD
#
# S76 is about a gate whose answer changes with no commit. The thing at risk is
# not the image a rebuild of `main` would produce; it is the image that is
# actually running, which was built weeks ago and has been quietly acquiring
# CVEs ever since. So the list comes from what the tier is pinned to, which is
# also the artefact a `docker pull` of that tag resolves to today.
#
# The floor check at the bottom is the S60 shape: an empty list would make a
# matrix job skip, and a skipped job reports success. A scan that silently
# scanned nothing is the exact failure this whole change exists to prevent, so
# finding no images is an error rather than an empty answer.
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."

ENVIRONMENT="${1:-dev}"
FORMAT="${2:-text}"
DIR="deploy/envs/${ENVIRONMENT}"
# A tier with fewer images than this has almost certainly been misparsed rather
# than shrunk. Deliberately a floor and not an exact count: adding a service
# should not fail the scan, removing every service should.
MIN_IMAGES="${MIN_IMAGES:-4}"

[ -d "$DIR" ] || { echo "deployed-images: no such environment: ${DIR}" >&2; exit 2; }

images=()
for f in "${DIR}"/*.yaml; do
  [ -e "$f" ] || continue
  # values.yaml holds chart-wide defaults and pins no image of its own.
  repo=$(awk '/^image:/{ in_img=1; next } in_img && /^[^[:space:]]/{ in_img=0 }
              in_img && $1 == "repository:" { print $2; exit }' "$f")
  tag=$(awk '/^image:/{ in_img=1; next } in_img && /^[^[:space:]]/{ in_img=0 }
             in_img && $1 == "tag:" { print $2; exit }' "$f")
  if [ -z "$repo" ] || [ -z "$tag" ]; then continue; fi
  images+=("${repo}:${tag}")
done

# A `while read` loop rather than `mapfile`, which is bash 4 and therefore
# absent on macOS's bash 3.2. CI runs bash 5 and would never have shown this;
# the point of these scripts is that they run identically on a laptop (S49).
unique=()
while IFS= read -r line; do
  [ -n "$line" ] && unique+=("$line")
done < <(printf '%s\n' "${images[@]+"${images[@]}"}" | sort -u)

if [ "${#unique[@]}" -lt "$MIN_IMAGES" ]; then
  echo "deployed-images: found ${#unique[@]} images under ${DIR}, expected at least ${MIN_IMAGES}" >&2
  printf '  %s\n' "${unique[@]+"${unique[@]}"}" >&2
  exit 1
fi

if [ "$FORMAT" = "--json" ]; then
  printf '%s\n' "${unique[@]}" | jq -R . | jq -sc .
else
  printf '%s\n' "${unique[@]}"
fi
