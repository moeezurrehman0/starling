#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Assert that release-please's bookkeeping is consistent AND that it is moving.
#
#   RELEASES_CREATED=<true|false> RELEASE_TAG=<vX.Y.Z|""> REPO=<owner/name> \
#   scripts/release-verify.sh
#
# WHY THIS EXISTS
#
# S59: release-please had never tagged anything, in either incarnation of this
# repository, and every run was green. It aborts rather than guessing when its
# two halves of state disagree -- the tracked manifest and the untracked GitHub
# releases -- and that abort is a *warning*, so the job succeeds while nothing
# is released.
#
# The consistency assertions written in response to that are necessary and not
# sufficient, and it is worth being precise about why. They prove the manifest
# and the newest release agree. A repository that has never released anything
# satisfies them forever: the manifest is compared only when a release exists,
# and there is no release to disagree with. The exact failure S59 was -- a tool
# that quietly never does its job -- passes them.
#
# So there are two kinds of check here, and only the second one could have
# caught S59 unaided:
#
#   CONSISTENCY  the two halves of the state agree with each other.
#   PROGRESS     when a release was supposed to happen, it happened.
#
# Progress is anchored on the manifest file rather than on a commit message,
# because the message is a configurable string and the manifest bump is what a
# release *is*. If the pushed commit changed .github/.release-please-manifest.json,
# then a release was cut, and a tag for that exact version must now exist. There
# is no way to satisfy that by doing nothing.
# ---------------------------------------------------------------------------
set -euo pipefail

REPO="${REPO:?REPO (owner/name) is required}"
MANIFEST_FILE="${MANIFEST_FILE:-.github/.release-please-manifest.json}"
RELEASES_CREATED="${RELEASES_CREATED:-false}"
RELEASE_TAG="${RELEASE_TAG:-}"

die() { echo "::error::$*" >&2; exit 1; }

# --- consistency 1: no release cut but never tagged -------------------------
#
# A merged release PR still labelled pending means a version was cut and never
# tagged. This is the state that makes release-please abort on every subsequent
# run, and it is true whether or not any release exists yet.
#
# The label is filtered locally on purpose. Passing --label to gh routes the
# query through the search index, which lags behind the label being applied and
# returned 0 in a live test while the label was demonstrably attached. A check
# that cannot fail is worse than no check.
merged=$(gh pr list --repo "$REPO" --state merged -L 100 --json number,title,labels)
stranded=$(jq '[.[] | select(.labels[]?.name == "autorelease: pending")]' <<<"$merged")
if [ "$(jq 'length' <<<"$stranded")" -ne 0 ]; then
  jq -r '.[] | "  #\(.number) \(.title)"' <<<"$stranded" >&2
  die "A merged release PR is still labelled 'autorelease: pending'. A release" \
      "was cut but never tagged; release-please will abort every subsequent" \
      "run. Create the missing release at the merge commit and relabel to" \
      "'autorelease: tagged'."
fi

manifest=$(jq -r '."."' "$MANIFEST_FILE")
if [ -z "$manifest" ] || [ "$manifest" = "null" ]; then
  die "could not read a version from ${MANIFEST_FILE}"
fi

# --- progress: a release commit must have produced a release ----------------
#
# `git diff HEAD~1 HEAD` needs fetch-depth >= 2. On the very first commit of a
# repository there is no HEAD~1 and nothing could have been released yet.
if git rev-parse --verify --quiet HEAD~1 >/dev/null; then
  changed=$(git diff --name-only HEAD~1 HEAD)
else
  changed=""
fi

if grep -qxF "$MANIFEST_FILE" <<<"$changed"; then
  echo "This commit bumped ${MANIFEST_FILE} to ${manifest}: it is a release commit."

  [ "$RELEASES_CREATED" = "true" ] ||
    die "the manifest was bumped to ${manifest} but release-please reported" \
        "releases_created=${RELEASES_CREATED}. This is S59 exactly: the version" \
        "was cut and no release was produced, on a green run."

  [ "$RELEASE_TAG" = "v${manifest}" ] ||
    die "the manifest was bumped to ${manifest} but the release created was" \
        "tagged '${RELEASE_TAG}', not 'v${manifest}'."

  # Ask GitHub rather than trusting the action's own output, for the same reason
  # the Publish check re-asks the registry: the step that claims to have done the
  # work is not a witness to it.
  gh release view "$RELEASE_TAG" --repo "$REPO" >/dev/null 2>&1 ||
    die "release-please reported creating ${RELEASE_TAG} but GitHub has no" \
        "such release."

  echo "Release ${RELEASE_TAG} exists and matches the manifest. Progress confirmed."
  exit 0
fi

# --- consistency 2: the manifest agrees with the newest release -------------
latest=$(gh release list --repo "$REPO" -L 1 --json tagName --jq '.[0].tagName // ""')
if [ -z "$latest" ]; then
  # Stated rather than passed silently. An unreleased repository is a legitimate
  # state and an indistinguishable one from a permanently broken release-please,
  # which is how S59 survived. The progress check above is what tells them apart,
  # and it only speaks when a release was actually attempted.
  echo "No release exists yet; nothing to compare against (manifest ${manifest})."
  echo "Note: this repository has never released. The progress assertion will" \
       "fire the first time a release commit lands without producing a tag."
  exit 0
fi

[ "$latest" = "v$manifest" ] ||
  die "Manifest says ${manifest} but the newest release is ${latest}. The" \
      "tracked and untracked halves of the release state have diverged."

echo "Manifest ${manifest} agrees with release ${latest}."
