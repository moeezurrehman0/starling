#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Raise, update or resolve a GitHub issue that stands for one ongoing problem.
#
#   scripts/alert-issue.sh raise   <key> <title> <body-file>
#   scripts/alert-issue.sh resolve <key> [note]
#
# `key` is a stable identifier for the *condition*, not the occurrence -- for
# example `scheduled-scan` or `main-red`. It becomes a label, and the label is
# what makes this idempotent: raising twice comments on the existing issue
# instead of opening a second one, and a daily failing scan produces one thread
# rather than thirty issues nobody reads.
#
# WHY THIS EXISTS
#
# S76: `main` sat red for two days and the way anyone found out was an
# unrelated pull request. Two separate holes produced that. The image scan's
# verdict changes without a commit, because the vulnerability database moves
# under it -- noted in S50's gap paragraph, then not acted on -- and nothing
# watches `main` for a red tick, because a merged commit's run has no reviewer
# waiting on it. A gate whose answer can change on its own needs something that
# asks it on a timer, and a timer is worth nothing if its answer goes nowhere.
#
# WHY AN ISSUE AND NOT A WEBHOOK
#
# An issue needs no secret, so it works identically in a fork and on day one of
# a clone; it is visible to anyone who can read the repository rather than to
# whoever is in a channel; and it has a close event, which is the part that
# matters. An alert that cannot say "this stopped happening" trains people to
# ignore it, and the first thing anyone learns to ignore is the alert that is
# always on. `resolve` closes the issue and says why, so an open alert issue
# means a currently-true problem.
#
# WHAT IT DELIBERATELY DOES NOT DO
#
# It does not assign, escalate, or decide severity, and it never touches code
# or infrastructure -- the same line drawn for the two agents in AGENTS.md. It
# turns a signal that already exists into something a human will see. Every
# judgement about what to do next is theirs.
# ---------------------------------------------------------------------------
set -euo pipefail

GH="${GH:-gh}"

die() { echo "alert-issue: $*" >&2; exit 1; }

usage() {
  cat >&2 <<'EOF'
usage:
  scripts/alert-issue.sh raise   <key> <title> <body-file>
  scripts/alert-issue.sh resolve <key> [note]
EOF
  exit 2
}

MODE="${1:-}"
KEY="${2:-}"
[ -n "$MODE" ] || usage
[ -n "$KEY" ] || usage

# The label namespace is explicit so that these never collide with a human's
# labels, and so `is:open label:alert` lists every currently-true condition.
LABEL="alert:${KEY}"

case "$KEY" in
  *[!a-z0-9-]* | "") die "key must be lowercase letters, digits and hyphens: ${KEY}" ;;
esac

# --force makes this idempotent: the label may or may not exist, and on a fresh
# clone it will not. Failing the alert because the label was missing would mean
# the first occurrence of every condition is the one that goes unreported.
ensure_label() {
  "$GH" label create "$LABEL" \
    --color "b60205" \
    --description "Automated alert: ${KEY}" \
    --force >/dev/null 2>&1 || true
}

# Returns the number of the open alert issue for this key, or nothing.
#
# Filtering by label rather than by title: a title carries a run number or a
# date and therefore changes between occurrences, and matching on it would open
# a new issue every time. The label is the identity of the condition.
#
# `--state all` plus a client-side `select`, rather than `--state open`, because
# the server-side state filter lags. Observed directly while building this: a
# `resolve` immediately followed by a list with `--state open` returned the
# issue that had just been closed, and the same query twenty seconds later
# returned nothing. Harmless for `resolve` -- closing a closed issue is a no-op
# -- but on `raise` it would comment onto a closed thread that nobody is
# watching, which is an alert that silently does not alert. The `state` field
# in the payload was correct even when the filter was not, so that is what is
# trusted here.
#
# The state filter is applied by `jq` here rather than by `gh --jq` so that the
# self-test can feed a recorded API payload through the same expression that
# runs in CI. A filter evaluated inside the tool being stubbed is a filter no
# fixture can reach -- S75, where the fixture and the code agreed with each
# other and both disagreed with reality.
open_issue() {
  "$GH" issue list --state all --label "$LABEL" \
    --json number,state --limit 20 |
    jq -r 'map(select(.state == "OPEN")) | .[0].number // empty'
}

case "$MODE" in
  raise)
    TITLE="${3:-}"
    BODY_FILE="${4:-}"
    [ -n "$TITLE" ] || usage
    [ -n "$BODY_FILE" ] || usage
    [ -f "$BODY_FILE" ] || die "no body file at ${BODY_FILE}"

    ensure_label
    existing="$(open_issue)"

    if [ -n "$existing" ]; then
      # Still broken. Comment rather than reopen-and-retitle: the thread is the
      # history of how long this has been true, which is the single most useful
      # thing about it and the thing a new issue per occurrence destroys.
      "$GH" issue comment "$existing" --body-file "$BODY_FILE" >/dev/null
      echo "commented on existing alert #${existing} (${LABEL})"
      echo "issue=${existing}" >> "${GITHUB_OUTPUT:-/dev/null}"
    else
      url="$("$GH" issue create --title "$TITLE" --label "$LABEL" --body-file "$BODY_FILE")"
      echo "opened alert ${url} (${LABEL})"
      echo "issue=${url##*/}" >> "${GITHUB_OUTPUT:-/dev/null}"
    fi
    ;;

  resolve)
    NOTE="${3:-The condition that raised this alert no longer reproduces.}"

    existing="$(open_issue)"
    if [ -z "$existing" ]; then
      # Not an error, and deliberately not silent. The overwhelmingly common
      # case is "it was never broken", which is what a green scheduled run
      # looks like every single day; saying so makes the log readable.
      echo "no open alert for ${LABEL}; nothing to resolve"
      exit 0
    fi

    "$GH" issue close "$existing" --comment "$NOTE" --reason completed >/dev/null
    echo "resolved alert #${existing} (${LABEL})"
    ;;

  *) usage ;;
esac
