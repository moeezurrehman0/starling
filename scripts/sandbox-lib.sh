#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# ---------------------------------------------------------------------------
# Shared state and helpers for the three sandbox lifecycle scripts.
#
# Sourced, never executed.
#
# WHY A SHARED FILE AND NOT THREE SELF-CONTAINED SCRIPTS
#
# `sandbox-status.sh` has to answer "how much of the 180 minutes is left", and
# `sandbox-down.sh` has to answer "did the session actually leave nothing".
# Both questions need facts that only `sandbox-up.sh` knows: when the session
# started, which region it ran in, and what the resource name prefix was. In a
# 180-minute disposable account those facts cannot live in a human's memory or
# in a terminal scrollback, because the common failure is opening a second
# terminal and getting a different answer.
#
# So they live in a gitignored state file. Every script reads it, `up` writes
# it, and `down` deletes it only after the verification sweep has passed --
# deliberately, so that a failed teardown leaves the evidence of which account
# and which prefix to go and clean up by hand.
# ---------------------------------------------------------------------------

SANDBOX_STATE="${SANDBOX_STATE:-.sandbox-session}"
SANDBOX_BUDGET_MIN="${SANDBOX_BUDGET_MIN:-180}"
# shellcheck disable=SC2034  # consumed by the scripts that source this file
SANDBOX_ROOT="infra/terraform/envs/sandbox"

# Every resource this project creates carries these two tags, and the teardown
# sweep searches on them. A resource that is not tagged is a resource teardown
# cannot find, which is why the tags are asserted in tf-validate.sh rather than
# merely encouraged in a style guide.
# shellcheck disable=SC2034
SANDBOX_TAG_KEY="Project"
# shellcheck disable=SC2034
SANDBOX_TAG_VALUE="twitter-clone"

c_red=$'\033[31m'; c_grn=$'\033[32m'; c_yel=$'\033[33m'; c_dim=$'\033[2m'; c_off=$'\033[0m'

say()  { printf '%s\n' "$*"; }
step() { printf '\n%s==>%s %s\n' "${c_grn}" "${c_off}" "$*"; }
warn() { printf '%s[warn]%s %s\n' "${c_yel}" "${c_off}" "$*" >&2; }
die()  { printf '%s[fail]%s %s\n' "${c_red}" "${c_off}" "$*" >&2; exit 1; }
dim()  { printf '%s%s%s\n' "${c_dim}" "$*" "${c_off}"; }

# Wall-clock seconds since the session was declared started.
session_elapsed_s() {
  local started
  started="$(state_get started_at)"
  [ -n "${started}" ] || { echo 0; return; }
  echo $(( $(date +%s) - started ))
}

fmt_duration() {
  local s="$1"
  printf '%dm%02ds' $(( s / 60 )) $(( s % 60 ))
}

state_put() {
  # state_put key value -- last write wins, file is rewritten atomically so a
  # ^C midway cannot leave a half-parsed line that every later read misreads.
  local key="$1" val="$2" tmp
  tmp="$(mktemp)"
  [ -f "${SANDBOX_STATE}" ] && grep -v "^${key}=" "${SANDBOX_STATE}" > "${tmp}"
  printf '%s=%s\n' "${key}" "${val}" >> "${tmp}"
  mv "${tmp}" "${SANDBOX_STATE}"
}

state_get() {
  [ -f "${SANDBOX_STATE}" ] || return 0
  sed -n "s/^$1=//p" "${SANDBOX_STATE}" | tail -1
}

require_session() {
  [ -f "${SANDBOX_STATE}" ] ||
    die "no session state at ${SANDBOX_STATE} — run 'make sandbox-up' first"
}

# Fail early and loudly rather than halfway through a 12-minute EKS create.
# Every one of these has been a real wasted session for someone.
preflight() {
  local missing=0 t
  for t in terraform aws kubectl helm; do
    command -v "${t}" >/dev/null 2>&1 || { warn "missing required tool: ${t}"; missing=1; }
  done
  [ "${missing}" -eq 0 ] || die "install the tools above and re-run"

  local ident
  if ! ident="$(aws sts get-caller-identity --output json 2>&1)"; then
    # A dry run is a rehearsal, so it must not *require* credentials. But if
    # credentials are present it still classifies them, because the guard below
    # is the one thing in this script that is unrecoverable if it is wrong, and
    # a guard only exercised on the real run is an untested guard (gap S41).
    [ "${DRY_RUN}" = "1" ] && { dim "dry run — no credentials present, guard not exercised"; return 0; }
    die "no usable AWS credentials — export the playground session keys first:
    $(printf '%s' "${ident}" | head -2)"
  fi

  SANDBOX_ACCOUNT="$(printf '%s' "${ident}" | sed -n 's/.*"Account": *"\([0-9]*\)".*/\1/p')"
  [ -n "${SANDBOX_ACCOUNT}" ] || die "could not parse an account id out of sts get-caller-identity"

  # A KodeKloud session issues temporary credentials. If someone has long-lived
  # keys for their own account in the environment, this script will happily and
  # irreversibly apply a demo topology to it. Refuse unless told otherwise.
  case "$(printf '%s' "${ident}" | sed -n 's/.*"Arn": *"\([^"]*\)".*/\1/p')" in
    *:assumed-role/*|*:federated-user/*) : ;;
    *)
      if [ "${SANDBOX_ALLOW_STATIC_CREDS:-0}" != "1" ]; then
        die "these look like long-lived IAM user credentials, not a playground session.
    Refusing, because applying this to a real account is not recoverable.
    Set SANDBOX_ALLOW_STATIC_CREDS=1 if you are certain."
      fi
      warn "proceeding with long-lived credentials because SANDBOX_ALLOW_STATIC_CREDS=1"
      ;;
  esac
}

# run <label> <command...> -- echo in dry-run, execute otherwise.
run() {
  local label="$1"; shift
  if [ "${DRY_RUN}" = "1" ]; then
    printf '  %s[dry-run]%s %s\n' "${c_dim}" "${c_off}" "$*"
    return 0
  fi
  dim "  ${label}"
  "$@"
}
