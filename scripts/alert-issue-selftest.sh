#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Two-sided self-test for scripts/alert-issue.sh.
#
#   scripts/alert-issue-selftest.sh
#
# WHY THIS EXISTS
#
# The script under test is the notification half of the S76 fix, and a
# notification that does not fire is worse than none: it converts "nobody is
# watching" into "something is watching", which is the belief that let `main`
# sit red for two days. The failure mode is specifically silent. Nothing about
# a scheduled job that raises no alert distinguishes "healthy" from "broken
# alerting", so every branch below is driven in the direction where it MUST
# act, and the cases that must NOT act assert that the mutating call was never
# made -- not merely that the exit status was 0.
#
# THE STUB'S OUTPUT IS TRANSCRIBED FROM REAL `gh` INVOCATIONS.
#
# S75's rule, and the reason it exists: the previous fixture in this repository
# was written from memory, agreed with the code, and both disagreed with the
# tool. So alert-issue.sh was run for real against this repository before this
# file was written -- it opened issue #37, commented on it, closed it, then
# opened #38 and #39 through the same cycle. Every payload and every message
# below is copied from those runs.
#
# That exercise earned its keep immediately. `gh issue list --state open` came
# back holding an issue that had been closed one second earlier, and the same
# query twenty seconds later came back empty: GitHub's list index lags. Written
# from memory this fixture would have had the server-side filter behaving
# perfectly, the tests would have passed, and the first real re-raise after a
# resolve would have commented onto a closed thread nobody was subscribed to.
# Case "a closed issue for the same key does not suppress a new alert" is that
# bug, and it fails if the client-side state filter is removed.
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."

STUB=$(mktemp -d)
trap 'rm -rf "${STUB}"' EXIT

export STUB_CALL_LOG="${STUB}/calls"
export STUB_LIST_JSON="${STUB}/list.json"

# The stub records every subcommand it is asked to run, then answers
# `issue list` from a payload the case under test chooses. Recording the calls
# is the part that matters: the negative cases are all "did NOT create" and
# "did NOT close", and an exit status cannot show that.
install_stub() {
cat > "${STUB}/gh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${STUB_CALL_LOG}"
case "${1:-} ${2:-}" in
  "label create")
    # Real output: none. `gh label create --force` on an existing label prints
    # nothing and exits 0; on a new one it prints nothing and exits 0.
    exit 0
    ;;
  "issue list")
    # Verbatim shape from
    #   gh issue list --state all --label alert:selftest-probe \
    #     --json number,state --limit 20
    # which returned, with one open and two closed issues for the key:
    #   [{"number":39,"state":"OPEN"},{"number":38,"state":"CLOSED"},...]
    cat "${STUB_LIST_JSON}"
    exit 0
    ;;
  "issue create")
    echo "https://github.com/moeezurrehman0/starling/issues/37"
    exit 0
    ;;
  "issue comment")
    echo "https://github.com/moeezurrehman0/starling/issues/39#issuecomment-5911080731"
    exit 0
    ;;
  "issue close")
    echo "✓ Closed issue moeezurrehman0/starling#39 (Alerting self-test, third cycle)"
    exit 0
    ;;
esac
exit 0
STUB
chmod +x "${STUB}/gh"
}
install_stub

BODY="${STUB}/body.md"
printf 'The scheduled scan found a fixable HIGH.\n' > "$BODY"

pass=0
fail=0

# Runs the script with the stub on PATH against a chosen `issue list` payload,
# then asserts exit status, a substring of the output, and -- the part the
# negative cases turn on -- whether a given subcommand was or was not called.
check() {
  local name="$1" list_json="$2" want_rc="$3" want_text="$4" want_call="$5" deny_call="$6"
  shift 6
  local out rc=0
  printf '%s' "$list_json" > "${STUB_LIST_JSON}"
  : > "${STUB_CALL_LOG}"
  out=$(PATH="${STUB}:${PATH}" GH="${STUB}/gh" ./scripts/alert-issue.sh "$@" 2>&1) || rc=$?

  if [ "$rc" -ne "$want_rc" ]; then
    printf 'FAIL  %s: expected exit %s, got %s\n%s\n' \
      "$name" "$want_rc" "$rc" "$(sed 's/^/        /' <<<"$out")"
    fail=$((fail + 1)); return
  fi
  if [ -n "$want_text" ] && ! grep -qF "$want_text" <<<"$out"; then
    printf 'FAIL  %s: exit %s was right but output lacked %q\n%s\n' \
      "$name" "$rc" "$want_text" "$(sed 's/^/        /' <<<"$out")"
    fail=$((fail + 1)); return
  fi
  if [ -n "$want_call" ] && ! grep -q "$want_call" "${STUB_CALL_LOG}"; then
    printf 'FAIL  %s: expected a %q call, calls were:\n%s\n' \
      "$name" "$want_call" "$(sed 's/^/        /' "${STUB_CALL_LOG}")"
    fail=$((fail + 1)); return
  fi
  if [ -n "$deny_call" ] && grep -q "$deny_call" "${STUB_CALL_LOG}"; then
    printf 'FAIL  %s: must not have called %q, calls were:\n%s\n' \
      "$name" "$deny_call" "$(sed 's/^/        /' "${STUB_CALL_LOG}")"
    fail=$((fail + 1)); return
  fi
  printf 'ok    %s\n' "$name"
  pass=$((pass + 1))
}

NONE='[]'
ONE_OPEN='[{"number":39,"state":"OPEN"}]'
ONLY_CLOSED='[{"number":38,"state":"CLOSED"},{"number":37,"state":"CLOSED"}]'
# Verbatim from the real invocation named at the top of this file.
MIXED='[{"number":39,"state":"OPEN"},{"number":38,"state":"CLOSED"},{"number":37,"state":"CLOSED"}]'

echo "-- raising --"

check "a first occurrence opens an issue" \
  "$NONE" 0 "opened alert" "issue create" "issue comment" \
  raise scheduled-scan "Scheduled scan failed" "$BODY"

check "a repeat occurrence comments instead of opening a second issue" \
  "$ONE_OPEN" 0 "commented on existing alert #39" "issue comment" "issue create" \
  raise scheduled-scan "Scheduled scan failed" "$BODY"

# The bug the live run exposed. The list index can still be serving a closed
# issue; if its state is not checked client-side, this comments onto a thread
# that is closed and unwatched, and the alert is silently lost.
check "a closed issue for the same key does not suppress a new alert" \
  "$ONLY_CLOSED" 0 "opened alert" "issue create" "issue comment" \
  raise scheduled-scan "Scheduled scan failed" "$BODY"

check "the open issue is selected out of a mixed list" \
  "$MIXED" 0 "commented on existing alert #39" "issue comment" "issue create" \
  raise scheduled-scan "Scheduled scan failed" "$BODY"

check "the label is ensured before the list is read" \
  "$NONE" 0 "opened alert" "label create" "" \
  raise scheduled-scan "Scheduled scan failed" "$BODY"

echo "-- resolving --"

check "an open alert is closed" \
  "$ONE_OPEN" 0 "resolved alert #39" "issue close" "" \
  resolve scheduled-scan "Back to green."

# Not an error and not silent: this is what every healthy day looks like.
check "resolving with nothing open is a no-op, not a failure" \
  "$NONE" 0 "nothing to resolve" "" "issue close" \
  resolve scheduled-scan

check "resolving when only closed issues remain does not re-close one" \
  "$ONLY_CLOSED" 0 "nothing to resolve" "" "issue close" \
  resolve scheduled-scan

echo "-- refusing --"

check "a missing body file fails rather than alerting with an empty body" \
  "$NONE" 1 "no body file" "" "issue create" \
  raise scheduled-scan "Scheduled scan failed" "${STUB}/absent.md"

check "a key that would not make a valid label is rejected" \
  "$NONE" 1 "key must be lowercase" "" "issue create" \
  raise "Scheduled Scan" "Scheduled scan failed" "$BODY"

check "an unknown mode is rejected rather than treated as resolve" \
  "$NONE" 2 "usage" "" "issue close" \
  wibble scheduled-scan

check "raise without a body file argument is rejected" \
  "$NONE" 2 "usage" "" "issue create" \
  raise scheduled-scan "Scheduled scan failed"

echo
if [ "$fail" -eq 0 ]; then
  echo "${pass} passed, 0 failed"
else
  echo "${pass} passed, ${fail} failed"
  exit 1
fi
