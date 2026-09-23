#!/usr/bin/env bash
#
# terraform test across every module and root that has tests.
#
# These run with mock_provider: no credentials, no network, nothing created. That
# is the point -- the production root is never applied, so a test that needed an
# account could never run against it, and the tier that most needs checking would
# be the one tier with no checks at all.
#
# This is the third of three layers and the narrowest:
#
#   terraform validate   type-checks. Has no opinion about correctness: a prod
#                        root with deletion protection off and a public bucket
#                        validates cleanly (gap register #15).
#   tf-validate.sh       greps source for design invariants. Can see the absence
#                        of a resource, which terraform test cannot.
#   tf-test.sh (this)    evaluates the configuration. Sees what only exists after
#                        evaluation -- jsondecode, for_each expansion, variable
#                        validation, lifecycle preconditions, and whether the
#                        modules compose.
#
# Each layer catches things the other two structurally cannot. Dropping any one
# of them leaves a shaped hole rather than a smaller version of the same net.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TF_DIR="$ROOT/infra/terraform"

command -v terraform >/dev/null || { echo "terraform is required"; exit 1; }

total_pass=0
total_fail=0
suites=0

run_suite() {
  local dir="$1" label="$2"

  [ -d "$dir/tests" ] || return 0
  suites=$((suites + 1))

  printf '\n\033[1;34m==>\033[0m %s\n' "$label"

  # -backend=false so a root with a declared S3 backend does not try to reach it.
  # Roots are init'd here rather than assumed: the modules referenced by a root
  # must be installed before `terraform test` can evaluate them, and the error
  # when they are not names the module, not the missing init.
  if ! terraform -chdir="$dir" init -backend=false -input=false -no-color >/tmp/tf-test-init.txt 2>&1; then
    printf '  \033[1;31mFAIL\033[0m  init\n'
    sed 's/^/        /' /tmp/tf-test-init.txt
    total_fail=$((total_fail + 1))
    return 0
  fi

  if terraform -chdir="$dir" test -no-color >/tmp/tf-test-out.txt 2>&1; then
    sed -n 's/^  run "\(.*\)"\.\.\. pass$/  \1/p' /tmp/tf-test-out.txt \
      | sed 's/^/  \x1b[1;32mPASS\x1b[0m  /'
    total_pass=$((total_pass + $(grep -c '\.\.\. pass$' /tmp/tf-test-out.txt || true)))
  else
    sed 's/^/        /' /tmp/tf-test-out.txt
    total_fail=$((total_fail + 1))
  fi
}

for m in "$TF_DIR"/modules/*/; do
  run_suite "$m" "modules/$(basename "$m")"
done

for r in "$TF_DIR"/envs/*/; do
  run_suite "$r" "envs/$(basename "$r")"
done

printf '\n\033[1m%d run blocks passed across %d suites, %d suites failed\033[0m\n' \
  "$total_pass" "$suites" "$total_fail"

[ "$total_fail" -eq 0 ]
