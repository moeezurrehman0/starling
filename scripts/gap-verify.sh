#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# shellcheck disable=SC2016
#   Single-quoted strings below are grep patterns and Markdown fragments.
#
# Verify the gap register (Phase 13).
#
# Why this exists
# ---------------
# `docs/16-gap-register.md` is the headline deliverable of this project, and its
# own maintenance section states the rule that matters:
#
#     "Any row whose 'how the repo proves it' column names an artefact that
#      does not exist is a bug, not a plan."
#
# That rule was written down and never enforced. Enforcing it turned up three
# real defects in a document that had been reviewed several times:
#
#   1. The register table (1-27) and the silent-failure classes (1-41) were two
#      independent numbering schemes, both cited throughout the repository as
#      "gap row N". The classes are now S1-S41.
#   2. `deployment.yaml` cited row 32 for the setWeight-as-replica-count
#      approximation, which is S38.
#   3. `load/ramp.js` cited row 33 for the emulator load ceiling, also S38.
#
# None of those were catchable by reading, which is the argument for a script.
#
# What it checks
# --------------
#   * every artefact path the register names exists (with one allow-list, for
#     outputs a script declares but has not yet produced)
#   * register row numbers are unique and contiguous
#   * silent-failure classes are unique, contiguous, and S-prefixed
#   * every register row has all five columns, none of them empty
#   * every ADR link resolves to a file
#   * every "gap S<n>" or "gap register row <n>" citation anywhere in the
#     repository resolves to an entry that exists

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}" || exit 1

REG="docs/16-gap-register.md"
PASSED=0
FAILED=0

pass() { printf '  \033[32mok\033[0m    %s\n' "$1"; PASSED=$((PASSED + 1)); }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILED=$((FAILED + 1)); }
head_() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }

# Paths a script declares as its own output. These legitimately do not exist
# until that script has run, and for the probe that requires a playground
# session. The entry is allowed only because `scripts/probe.sh` is asserted
# below to actually write it -- an unproduced output is acceptable, an
# unproducible one is not.
DECLARED="docs/06-probe-report.md"

is_declared() {
  local p="$1" d
  for d in ${DECLARED}; do [ "$p" = "$d" ] && return 0; done
  return 1
}

# ---------------------------------------------------------------------------
head_ "the register exists and is the document we think it is"

if [ -f "${REG}" ]; then
  pass "${REG} is present"
else
  fail "${REG} is missing"
  printf '\n%d passed, %d failed\n' "${PASSED}" "${FAILED}"
  exit 1
fi

# ---------------------------------------------------------------------------
head_ "every artefact the register names exists"

# Backticked tokens that look like repository paths: they contain a slash or a
# known extension. Bare words such as `dev` or `emptyDir` are excluded by
# requiring an extension, and prose such as `t3.medium` by requiring the
# extension to be one we actually use.
# No `mapfile`: this has to run on the bash 3.2 that ships with macOS.
CITED="$(
  grep -o '`[A-Za-z0-9_./-]\+\.\(sh\|py\|md\|tf\|tfvars\|ya\?ml\|js\|ts\|json\|txt\|java\)`' "${REG}" |
    tr -d '`' | sort -u
)"

for c in ${CITED}; do
  # Several citations are bare filenames -- `risk.py`, `kind-deploy.sh` -- used
  # in prose after the full path has been given once. Resolve those by search
  # rather than demanding the register repeat the path every time.
  if [ -e "${c}" ]; then
    pass "exists: ${c}"
  elif is_declared "${c}"; then
    pass "declared output (not yet produced): ${c}"
  elif [ -n "$(find . -name "$(basename "${c}")" -not -path './.git/*' -print -quit)" ]; then
    pass "exists (resolved by name): ${c}"
  else
    fail "the register names an artefact that does not exist: ${c}"
  fi
done

if grep -q 'REPORT="${REPORT:-docs/06-probe-report.md}"' scripts/probe.sh 2>/dev/null; then
  pass "the declared probe report is actually produced by scripts/probe.sh"
else
  fail "docs/06-probe-report.md is allow-listed but probe.sh does not write it"
fi

# ---------------------------------------------------------------------------
head_ "register rows are numbered consistently"

# Only the register table. The "what the probe will change" table further down
# reuses these numbers as references, so a document-wide grep sees each twice
# and reports both a duplicate and a hole in the same breath.
REG_SECTION="$(awk '/^## The register$/{f=1;next} /^## /{f=0} f' "${REG}")"
ROWS="$(printf '%s\n' "${REG_SECTION}" | grep -o '^| [0-9]\+b\? |' | tr -d '| ')"
ROW_COUNT="$(printf '%s\n' "${ROWS}" | grep -c .)"

if [ "${ROW_COUNT}" -gt 0 ]; then
  pass "found ${ROW_COUNT} register rows"
else
  fail "found no register rows -- the table format has changed"
fi

dupes="$(printf '%s\n' "${ROWS}" | sort | uniq -d | tr '\n' ' ')"
if [ -z "${dupes// /}" ]; then
  pass "register row numbers are unique"
else
  fail "duplicate register row numbers: ${dupes}"
fi

# Contiguity, ignoring the 6b suffix row.
expected=1
gaps=""
for r in ${ROWS}; do
  case "${r}" in *b) continue ;; esac
  [ "${r}" = "${expected}" ] || gaps="${gaps} expected ${expected} got ${r};"
  expected=$((r + 1))
done
if [ -z "${gaps}" ]; then
  pass "register row numbers are contiguous from 1 to $((expected - 1))"
else
  fail "register numbering has holes:${gaps}"
fi

# ---------------------------------------------------------------------------
head_ "silent-failure classes use their own namespace"

# The whole point of the S prefix: a bare "**32." here would re-create the
# collision that made two citations in the source tree point at the wrong entry.
if grep -q '^\*\*[0-9]\+\. ' "${REG}"; then
  fail "a silent-failure class is numbered without the S prefix"
else
  pass "no silent-failure class uses a bare number"
fi

CLASSES="$(grep -o '^\*\*S[0-9]\+\.' "${REG}" | tr -d '*S.')"
CLASS_COUNT="$(printf '%s\n' "${CLASSES}" | grep -c .)"

if [ "${CLASS_COUNT}" -gt 0 ]; then
  pass "found ${CLASS_COUNT} silent-failure classes"
else
  fail "found no silent-failure classes"
fi

expected=1
gaps=""
for c in ${CLASSES}; do
  [ "${c}" = "${expected}" ] || gaps="${gaps} expected S${expected} got S${c};"
  expected=$((c + 1))
done
if [ -z "${gaps}" ]; then
  pass "silent-failure classes are contiguous from S1 to S$((expected - 1))"
else
  fail "silent-failure numbering has holes:${gaps}"
fi

# ---------------------------------------------------------------------------
head_ "no register row is left half-written"

# A row with an empty "how the repo proves it" cell is the failure this document
# is most prone to: it reads as complete and asserts nothing.
empty=0
while IFS= read -r line; do
  n="$(printf '%s' "${line}" | awk -F'|' '{print $2}' | tr -d ' ')"
  cols="$(printf '%s' "${line}" | awk -F'|' '{print NF}')"
  if [ "${cols}" -lt 6 ]; then
    fail "row ${n} has ${cols} fields, expected at least 6"
    empty=$((empty + 1))
    continue
  fi
  for i in 2 3 4 5 6; do
    cell="$(printf '%s' "${line}" | awk -F'|' -v i="${i}" '{print $i}' | tr -d ' ')"
    if [ -z "${cell}" ]; then
      fail "row ${n} has an empty column ${i}"
      empty=$((empty + 1))
    fi
  done
done < <(printf '%s\n' "${REG_SECTION}" | grep '^| [0-9]\+b\? |')

[ "${empty}" -eq 0 ] && pass "every register row has five populated columns"

# ---------------------------------------------------------------------------
head_ "every ADR the register links resolves"

ADRS="$(grep -o '(adr/[0-9A-Za-z._-]\+\.md)' "${REG}" | tr -d '()' | sort -u)"
for a in ${ADRS}; do
  if [ -f "docs/${a}" ]; then
    pass "ADR resolves: ${a}"
  else
    fail "ADR link is dead: docs/${a}"
  fi
done

# ---------------------------------------------------------------------------
head_ "every citation in the repository resolves to a real entry"

# This is the check that would have caught the two wrong citations. A comment
# that points a reader at the wrong explanation is worse than no comment: it
# spends their trust and then misdirects them.
bad=0
while IFS= read -r hit; do
  file="${hit%%:*}"
  rest="${hit#*:}"
  line="${rest#*:}"

  for n in $(printf '%s' "${line}" | grep -o 'gap S[0-9]\+' | grep -o '[0-9]\+'); do
    if grep -q "^\*\*S${n}\. " "${REG}"; then
      pass "${file}: gap S${n} resolves"
    else
      fail "${file}: cites gap S${n}, which does not exist"
      bad=$((bad + 1))
    fi
  done

  for n in $(printf '%s' "${line}" | grep -o 'gap register row [0-9]\+' | grep -o '[0-9]\+$'); do
    if grep -q "^| ${n} |" "${REG}"; then
      pass "${file}: gap register row ${n} resolves"
    else
      fail "${file}: cites gap register row ${n}, which does not exist"
      bad=$((bad + 1))
    fi
  done
done < <(
  grep -rn 'gap S[0-9]\|gap register row [0-9]' \
    --include='*.sh' --include='*.py' --include='*.md' --include='*.yaml' \
    --include='*.yml' --include='*.js' --include='*.java' . 2>/dev/null |
    grep -v "^\./${REG}"
)

# The ambiguous form is banned outright. "gap row 32" cannot be resolved without
# knowing which of the two tables the author meant, and for two years of this
# repository's short life nobody did.
if grep -rn 'gap row [0-9]' \
  --include='*.sh' --include='*.py' --include='*.md' --include='*.yaml' \
  --include='*.yml' --include='*.js' --include='*.java' . 2>/dev/null |
  grep -v "^\./${REG}" | grep -v '^\./scripts/gap-verify.sh' | grep -q .; then
  fail 'the ambiguous form "gap row N" is still used; say "gap SN" or "gap register row N"'
else
  pass 'no citation uses the ambiguous "gap row N" form'
fi

# ---------------------------------------------------------------------------
head_ "every relative Markdown link in README and docs/ resolves"

# The register is not the only document that can cite something that is not
# there. `docs/07-aiops.md` was referenced by AGENTS.md and by the footer of
# every generated risk comment for most of Phase 12 before it was written, and
# nothing noticed: the comment rendered perfectly and sent the reader nowhere.
dead=0
for f in README.md AGENTS.md docs/*.md docs/runbooks/*.md docs/adr/*.md; do
  [ -f "${f}" ] || continue
  dir="$(dirname "${f}")"
  links="$(grep -o '](\([A-Za-z0-9_./-]\+\.md\|[A-Za-z0-9_./-]\+/\))' "${f}" 2>/dev/null |
    sed 's/^](//; s/)$//')"
  for l in ${links}; do
    case "${l}" in http*) continue ;; esac
    if [ ! -e "${dir}/${l}" ] && [ ! -e "${l}" ]; then
      fail "${f} links to ${l}, which does not exist"
      dead=$((dead + 1))
    fi
  done
done
[ "${dead}" -eq 0 ] && pass "no dead relative links in README, AGENTS.md, docs/, runbooks or ADRs"

# ---------------------------------------------------------------------------
head_ "every 'make <target>' named in the docs exists in the Makefile"

# Same failure as a dead link, one level more embarrassing: the README's
# getting-started section is the first thing a reader will actually type, and a
# target that was renamed or never written fails with a bare "No rule to make
# target", which reads like the reader's mistake rather than ours.
bad_make=0
targets="$(grep -o '^[a-z][a-z0-9-]*:' Makefile | tr -d ':' | sort -u | tr '\n' ' ')"
# Only backticked (`make x`) or line-leading occurrences. Bare prose matches
# "make it clear" and "make sure", and a check that cries wolf on English is a
# check that gets its whole section skipped -- gap S40, in miniature.
cited="$( { grep -ho '`make [a-z][a-z0-9-]*' README.md AGENTS.md docs/*.md docs/runbooks/*.md 2>/dev/null |
             sed 's/^`make //'
           # Line-leading matches are only trusted inside a fenced code block.
           # Unfenced, prose that wraps onto "make the rename safe" or "make it
           # clear" reads as a target and the check cries wolf on English --
           # which is gap S40, and it fired on this very document.
           awk 'FNR == 1 { fence = 0 }
                /^```/ { fence = !fence; next }
                fence && /^make [a-z][a-z0-9-]*/ { print $2 }' \
             README.md AGENTS.md docs/*.md docs/runbooks/*.md 2>/dev/null; } | sort -u)"
for t in ${cited}; do
  case " ${targets} " in
    *" ${t} "*) ;;
    *) fail "docs reference 'make ${t}', which is not a Makefile target"; bad_make=$((bad_make + 1)) ;;
  esac
done
[ "${bad_make}" -eq 0 ] && pass "every documented make target exists"

# ---------------------------------------------------------------------------
head_ "the pre-rename project name appears nowhere"

# The product is Starling. The former name is banned outright, but the reason
# this is a CI gate rather than a style note is operational: sandbox-down.sh
# sweeps AWS by "Name=tag:Project,Values=${SANDBOX_TAG_VALUE}" and by name
# prefix. If one Terraform tag or one script constant drifts back to the old
# value, the sweep matches nothing and reports a clean account over resources
# that are still running and still billing -- gap S43, reintroduced by a
# find-and-replace. The old name surviving anywhere is the observable symptom.
#
# Built from fragments so this line does not match itself.
banned="$(printf 't%sr' 'witte')"
stale="$(git ls-files -z |
  xargs -0 grep -lI -i -e "${banned}" 2>/dev/null |
  grep -v '^scripts/gap-verify\.sh$' || true)"
# Paths, not just contents. Gradle encodes a convention plugin's id in its
# filename, so build-logic/<id>.gradle.kts kept the old name while every file
# referencing it had moved on -- the content check passed and the build did not
# compile. A gate that reports clean over a broken tree is the whole subject of
# this register, so it is worth the second line.
stale_paths="$(git ls-files | grep -i -e "${banned}" || true)"
if [ -n "${stale}" ] || [ -n "${stale_paths}" ]; then
  for f in ${stale}; do
    fail "pre-rename project name still present in ${f}"
  done
  for f in ${stale_paths}; do
    fail "pre-rename project name still in the path ${f}"
  done
else
  pass "no occurrence of the pre-rename project name in any tracked file or path"
fi

printf '\n%d passed, %d failed\n' "${PASSED}" "${FAILED}"
[ "${FAILED}" -eq 0 ]
