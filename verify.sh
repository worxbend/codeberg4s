#!/usr/bin/env bash
#
# Pre-handoff verification gate — PLAN.md §6.6, in order.
#
# A handoff is invalid unless this script exits 0. Steps run in the order below
# because each one is cheaper than the next: formatting fails in a second,
# mutation testing takes minutes.
#
#   ./verify.sh              fast gate: format, lint, compile, unit tests, coverage
#   ./verify.sh --with-slow  also runs duplication and CRAP analysis
#   ./verify.sh --nightly    also runs mutation testing
#
# Never included in any mode: modules/it (needs Docker or the live network) and
# ScalaCheck property suites (the `Property` munit tag). Both are
# environmentally unsuitable or deliberately separated per the constitution —
# see docs/CONSTITUTION_MAPPING.md.

set -euo pipefail

cd "$(dirname "$0")"

readonly MILL="./mill"

# Unit-test modules. modules.it is absent on purpose.
readonly UNIT_MODULES=(
  modules.domain.test
  modules.core.test
  modules.codec.test
  modules.transport.test
  modules.client.test
)

# Coverage-measured modules. transport has a lower floor because only
# stub-reachable paths are exercised without a live server (PLAN.md §6.1).
readonly COVERED_MODULES=(modules.domain modules.core modules.codec)

with_slow=false
nightly=false
for arg in "$@"; do
  case "$arg" in
    --with-slow) with_slow=true ;;
    --nightly) with_slow=true; nightly=true ;;
    -h|--help) sed -n '3,20p' "$0"; exit 0 ;;
    *) echo "verify.sh: unknown option '$arg'" >&2; exit 2 ;;
  esac
done

step=0
start_time=$(date +%s)

announce() {
  step=$((step + 1))
  printf '\n\033[1m── %d. %s\033[0m\n' "$step" "$1"
}

fail() {
  printf '\n\033[31mverify.sh FAILED at step %d: %s\033[0m\n' "$step" "$1" >&2
  exit 1
}

# ---------------------------------------------------------------------------
announce "Format check (scalafmt)"
# NOTE: .scalafmt.conf sets project.git = true, so scalafmt only sees files Git
# tracks. A newly written, unstaged file is silently skipped here and then
# reformatted later, producing a noisy diff. Warn rather than pass quietly.
if git status --porcelain | grep -q '^??.*\.\(scala\|mill\|sc\)$'; then
  printf '\033[33m  warning: untracked Scala sources are invisible to scalafmt — git add them first:\033[0m\n'
  git status --porcelain | grep '^??.*\.\(scala\|mill\|sc\)$' | sed 's/^/    /'
fi
"$MILL" mill.scalalib.scalafmt/checkFormatAll || fail "formatting (run: $MILL mill.scalalib.scalafmt/)"

# ---------------------------------------------------------------------------
announce "Lint check (scalafix)"
"$MILL" modules.__.fix --check || fail "scalafix (run: $MILL modules.__.fix)"

# ---------------------------------------------------------------------------
announce "Compile — warnings are errors"
"$MILL" modules.__.compile || fail "compilation"

# ---------------------------------------------------------------------------
announce "Unit tests (excluding the Property tag and modules/it)"
"$MILL" "${UNIT_MODULES[@]}" --exclude-tags=Property || fail "unit tests"

# ---------------------------------------------------------------------------
announce "Architecture boundary check"
# These are the reviewer's veto list from PLAN.md §8, cheap enough to automate.
boundary_violation() {
  local where="$1" pattern="$2" why="$3"
  if grep -rInE "$pattern" "$where" --include='*.scala' >/dev/null 2>&1; then
    printf '\033[31m  %s\033[0m\n' "$why"
    grep -rInE "$pattern" "$where" --include='*.scala' | sed 's/^/    /'
    return 1
  fi
  return 0
}

boundaries_ok=true
boundary_violation modules/domain/src '^import (sttp|upickle|ujson|scala\.concurrent)' \
  'domain must depend on nothing but the standard library' || boundaries_ok=false
boundary_violation modules/core/src '^import (sttp|upickle|ujson|scala\.concurrent)' \
  'core must not know about sttp, upickle or Future' || boundaries_ok=false
boundary_violation modules/domain/src '^import sttp' \
  'domain must not import sttp' || boundaries_ok=false
boundary_violation 'modules/domain/src modules/core/src modules/codec/src modules/transport/src modules/client/src' \
  'Await\.(result|ready)' 'Await is banned in production code' || boundaries_ok=false
boundary_violation 'modules/domain/src modules/core/src modules/codec/src modules/transport/src modules/client/src' \
  'new (Exception|RuntimeException|IllegalStateException)\(' \
  'bare exceptions are banned — every failure carries a CallContext' || boundaries_ok=false
$boundaries_ok || fail "architecture boundaries"
echo "  boundaries clean"

# ---------------------------------------------------------------------------
announce "Coverage"
for module in "${COVERED_MODULES[@]}"; do
  "$MILL" "${module}.scoverage.xmlReport" || fail "coverage report for $module"
done
if [[ -f scripts/coverage-gate.sc ]]; then
  scala-cli run scripts/coverage-gate.sc -- "${COVERED_MODULES[@]}" || fail "coverage thresholds"
else
  echo "  (scripts/coverage-gate.sc absent — thresholds not enforced yet)"
fi

# ---------------------------------------------------------------------------
if $with_slow; then
  announce "Duplication (PMD CPD)"
  if [[ -x scripts/cpd.sh ]]; then
    scripts/cpd.sh || fail "duplicate code above threshold"
  else
    echo "  (scripts/cpd.sh absent — skipped)"
  fi

  announce "CRAP (coverage-weighted complexity)"
  if [[ -f scripts/crap.sc ]]; then
    scala-cli run scripts/crap.sc || fail "CRAP above 30 for at least one method"
  else
    echo "  (scripts/crap.sc absent — skipped)"
  fi
fi

# ---------------------------------------------------------------------------
if $nightly; then
  announce "Mutation testing (Stryker4s)"
  if [[ -x scripts/mutate.sh ]]; then
    scripts/mutate.sh || fail "mutation score below threshold"
  else
    echo "  (scripts/mutate.sh absent — skipped)"
  fi
fi

printf '\n\033[32m✓ verify.sh passed in %ds\033[0m\n' "$(($(date +%s) - start_time))"
