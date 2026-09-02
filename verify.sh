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
#   ./verify.sh --properties runs ONLY the ScalaCheck property suites
#
# --with-slow gates duplication against a recorded baseline (CPD_BASELINE_GROUPS
# below) and fails only when duplication rises. --nightly is expected to fail at
# the mutation step until Stryker4s is declared in build.mill; scripts/mutate.sh
# refuses to report a score it did not produce.
#
# Never included in any mode: modules/it, which needs Docker or the live
# network.
#
# The ScalaCheck property suites (the `Property` munit tag) are separated rather
# than excluded outright: no mode above runs them, and `--properties` runs
# nothing else. Separation is what the constitution asks for — property tests
# stay out of the routine gate, out of coverage and out of mutation runs — but
# separation only means something if something still executes them, so
# `--properties` is a mode of its own and the nightly workflow gives it a job of
# its own. See docs/CONSTITUTION_MAPPING.md.

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

# Coverage-measured modules. modules.it is absent on purpose: it needs Docker
# or the live network, so it is never part of any verify.sh mode.
#
# All five library modules are measured. domain, core and codec are gated at
# the PLAN.md §6.1 floor of 90% statement / 85% branch; transport and client
# are gated at 80/80 because neither can reach every path without a live
# server — transport's real socket errors and client's download rail are only
# exercised against stubs. The floors themselves live in
# scripts/coverage-gate.sc.
#
# MEASURED, NOT RECALLED: `./verify.sh --with-slow` on 2026-09-02 read
# transport at 153/174 statements (87.93% statement, 93.94% branch) and client
# at 4196/4766 (88.04% statement, 100.00% branch). Both clear 80/80 as they
# stand, so no floor had to be lowered to admit them, and the worst CRAP over
# the two new modules was 11.0 against a limit of 30.
readonly COVERED_MODULES=(
  modules.domain
  modules.core
  modules.codec
  modules.transport
  modules.client
)

# Recorded duplication, not a tolerated threshold.
#
# `scripts/cpd.sh` finds real duplication in real code: element-wise DTO
# conversion, the page/limit query pair, path-segment validation — the helpers
# docs/LEDGER.md § "Helpers awaiting promotion" already names and owns. The
# honest options were to leave --with-slow permanently red, which trains
# everyone to ignore it, or to record what the debt is today and fail on any
# increase. This is the second.
#
# It is NOT a raised threshold. CPD_MIN_TOKENS stays at 40, every group is
# still reported, and the number below is the exact count that exists — so a
# change that adds one group fails, and a change that removes ten is told to
# bank the win by lowering this number.
#
# MEASURED, NOT RECALLED: `scripts/cpd.sh --report` on 2026-08-31 against
# modules/{domain,core,codec,transport,client}/src with PMD 7.26.0 at 40
# tokens — 156 groups, after collapsing the duplicated BlockedUser model. The
# 161 it read a few commits earlier was the same tree with that duplicate still
# in it.
#
# THIS NUMBER IS NOT COMPARABLE TO ANY MEASUREMENT BELOW, and the drop from
# 363 was not earned by deleting code alone. `.scalafix.conf` used to set
# `groupedImports = Explode`, one import per line. Promoting the shared codec
# helpers gave 108 files in `codec` the same seven-line preamble, and PMD has
# no import filter for Scala, so it counted every pair of those preambles as
# duplication. The raw count reached 408 while duplication that contains real
# code fell from 222 groups to 183 — measured at both commits with the same
# tool. 324 of the 408 held nothing but a package clause and imports.
#
# Switching to `groupedImports = Merge` collapses each preamble to one line
# per package, and what is left is duplication of code rather than of file
# headers. So the count below is specific to `Merge` as well as to PMD 7.26.0
# at 40 tokens: re-exploding imports would push it back over 300 without a
# line of logic being copied anywhere.
#
# Earlier measurements, kept as history — read them as a series only among
# themselves, not against the number above:
#
# MEASURED, NOT RECALLED: `scripts/cpd.sh --report` on 2026-08-09 against
# modules/{domain,core,codec,transport,client}/src with PMD 7.26.0 at 40
# tokens — 363 groups over 1342 locations (762 in codec, 535 in client, 39 in
# domain, 6 in core, none in transport).
#
# The ten groups between 373 and 363 came off with the credential-redaction
# change: the three copies of the Actions runner registration decoders no
# longer read as one repeated shape, and UserTokenApi lost the bespoke
# error-rewriting helper that the pipeline now makes unnecessary. Banked here
# rather than left as headroom, per the paragraph above.
#
# The baseline recorded before that was 323 groups over 1195 locations,
# measured on 2026-08-02. Everything between the two numbers is codec: the
# upickle-to-jsoniter rewrite (commit 48f64fe) replaced hand-written readers
# and writers with per-DTO codec definitions that repeat the same shape once
# per field, so codec's share of the reported locations went from 599 to 764
# while every other module stayed where it was. Recording the higher number
# registered that debt; it did not forgive it, and it was not a licence to
# add more.
#
# The five groups between 378 and 373 were then paid off rather than
# tolerated: four copies of the same element-decoding fold became one shared
# helper, and the four inline copies of the path-segment security rule became
# one call to PathSegment. Both are why this number is a recorded measurement
# and not a threshold — a threshold would have absorbed the win silently.
#
# The number is specific to PMD 7.26.0 at 40 tokens. Change either and remeasure
# rather than guessing which way the count moved.
#
# Deliberately not overridable from the environment: moving the baseline has to
# appear in a diff, with a commit message saying why.
readonly CPD_BASELINE_GROUPS=156

with_slow=false
nightly=false
properties=false
for arg in "$@"; do
  case "$arg" in
    --with-slow) with_slow=true ;;
    --nightly) with_slow=true; nightly=true ;;
    --properties) properties=true ;;
    # Print the header block above, whatever length it has grown to — a fixed
    # line range goes stale the first time someone documents a new flag.
    -h|--help) awk 'NR > 2 && /^#/ { sub(/^# ?/, ""); print; next } NR > 2 { exit }' "$0"; exit 0 ;;
    *) echo "verify.sh: unknown option '$arg'" >&2; exit 2 ;;
  esac
done

step=0
start_time=$(date +%s)

# One scratch directory for every step's log, with the trap installed before
# any step can run. A per-step `mktemp` plus a per-step `trap` means the last
# trap wins and the earlier files leak.
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT

announce() {
  step=$((step + 1))
  printf '\n\033[1m── %d. %s\033[0m\n' "$step" "$1"
}

fail() {
  printf '\n\033[31mverify.sh FAILED at step %d: %s\033[0m\n' "$step" "$1" >&2
  exit 1
}

# ---------------------------------------------------------------------------
if $properties; then
  # The mirror image of the routine gate: every unit module, `Property` and
  # nothing but `Property`. It runs on its own rather than as an extra step of
  # the default gate because the constitution asks for property tests to be kept
  # out of ordinary verification, coverage, mutation and complexity runs — and
  # because these suites are slow enough that folding them in would change what
  # `./verify.sh` costs on every commit.
  announce "Property suites (the Property tag only)"

  # --include-tags is repeated per module for the same reason --exclude-tags is
  # below: Mill scopes the arguments after a target to THAT target only, so a
  # single trailing flag reaches the last module in the chain and no other. Got
  # wrong once already, in the opposite direction.
  property_targets=()
  for target in "${UNIT_MODULES[@]}"; do
    [[ ${#property_targets[@]} -eq 0 ]] || property_targets+=("+")
    property_targets+=("$target" --include-tags=Property)
  done

  property_log="$work_dir/properties.log"
  set -o pipefail
  "$MILL" "${property_targets[@]}" 2>&1 |
    sed 's/\x1b\[[0-9;]*m//g' | tee "$property_log"
  property_status=${PIPESTATUS[0]}
  set +o pipefail
  [[ "$property_status" -eq 0 ]] || fail "property suites"

  property_failed=$(grep -oE 'finished: [0-9]+ failed' "$property_log" |
    awk '{ sum += $2 } END { print sum + 0 }')
  [[ "$property_failed" -eq 0 ]] || fail "$property_failed properties failed"

  # A run that executed nothing exits 0 and prints only "ignored" lines, so the
  # count is asserted rather than assumed. This is the `leaked` check of the
  # routine gate read backwards: there a *Props suite with a non-zero total
  # meant the exclusion had failed, here a *Props suite with a non-zero total is
  # the only evidence that the inclusion worked.
  property_executed=$(grep -oE 'finished: [0-9]+ failed, [0-9]+ ignored, [0-9]+ total' "$property_log" |
    awk '{ sum += $6 } END { print sum + 0 }')
  props_ran=$(grep -oE 'Test run [A-Za-z0-9_.]*Props finished: [0-9]+ failed, [0-9]+ ignored, [0-9]+ total' "$property_log" |
    awk '$(NF - 1) != 0 { print $3 }' | sort -u)
  if [[ "$property_executed" -eq 0 || -z "$props_ran" ]]; then
    printf '\033[31m  the property run executed %s test(s) and no *Props suite reported one.\033[0m\n' \
      "$property_executed" >&2
    printf '  Each module in the chain needs its own --include-tags=Property; Mill\n' >&2
    printf '  applies a trailing one to the last target only.\n' >&2
    fail "no property suite ran"
  fi
  printf '  %s properties executed across %s suite(s)\n' \
    "$property_executed" "$(wc -l <<<"$props_ran")"

  printf '\n\033[32m✓ verify.sh --properties passed in %ds\033[0m\n' "$(($(date +%s) - start_time))"
  exit 0
fi

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
announce "Every source tree is inside the gate"
# `modules.__.compile` is a wildcard, so a module declared under `modules` in
# build.mill is compiled, formatted and linted without this script being
# touched. What a wildcard cannot notice is the opposite mistake: a source tree
# that exists on disk and was never wired into the build. Nothing compiles it,
# nothing lints it, and every step below still reports green.
#
# modules/examples is why this check exists. Its whole purpose is that an
# example which stops compiling breaks the gate on the commit that invalidated
# it — a promise worth exactly as much as the guarantee that the build can see
# the module at all. As of this writing build.mill declares
# `object examples extends Codeberg4sModule` inside `object modules`, so the
# wildcard does reach it; this step is what keeps that true.
gate_targets=$("$MILL" resolve 'modules.__.compile') || fail "could not resolve the module list"
outside_gate=()
for dir in modules/*/; do
  name=$(basename "$dir")
  [[ -d "$dir/src" ]] || continue
  grep -qE "(^|[[:space:]])modules\.$name\.compile([[:space:]]|$)" <<<"$gate_targets" ||
    outside_gate+=("$name")
done
if [[ ${#outside_gate[@]} -gt 0 ]]; then
  printf '\033[31m  source trees that no build.mill module compiles:\033[0m\n'
  printf '    modules/%s/src\n' "${outside_gate[@]}"
  fail "a source tree outside the gate — declare it under \`object modules\` in build.mill"
fi
echo "  every modules/*/src is reached by modules.__.compile"

# ---------------------------------------------------------------------------
announce "Compile — warnings are errors"
"$MILL" modules.__.compile || fail "compilation"

# ---------------------------------------------------------------------------
announce "Unit tests (excluding the Property tag and modules/it)"
# Mill needs `+` between targets. Writing them space-separated instead makes
# Mill read the later ones as test-NAME FILTERS for the first module, so every
# suite reports "1 ignored, 0 total" and the run still exits 0. That is a
# false-green gate, so the separator is load-bearing and the assertion below
# exists to make sure a future edit cannot reintroduce it.
#
# --exclude-tags is repeated per module for the same reason it is not written
# once at the end: Mill scopes the arguments after a target to THAT target only,
# so a single trailing `--exclude-tags=Property` reached the last module in the
# chain and no other. Every earlier module then ran its ScalaCheck suites inside
# the routine gate — slow, and precisely the separation docs/CONSTITUTION_MAPPING.md
# requires. The check after the run asserts the exclusion actually took effect.
test_targets=()
for target in "${UNIT_MODULES[@]}"; do
  [[ ${#test_targets[@]} -eq 0 ]] || test_targets+=("+")
  test_targets+=("$target" --exclude-tags=Property)
done

test_log="$work_dir/tests.log"
# Two things here are load-bearing and were both got wrong once.
#
# PIPESTATUS, not $?: with `cmd | tee`, $? is tee's status, which is always 0.
# The pipeline would have reported success for a failing suite.
#
# The ANSI strip: Mill colours its output even when piped, so a failing suite
# prints "finished: \e[91m3 failed\e[39m". A count that matched a bare digit
# skipped exactly the suites that failed — undercounting the total AND reading
# zero failures. Strip first, then count.
set -o pipefail
"$MILL" "${test_targets[@]}" 2>&1 |
  sed 's/\x1b\[[0-9;]*m//g' | tee "$test_log"
test_status=${PIPESTATUS[0]}
set +o pipefail
[[ "$test_status" -eq 0 ]] || fail "unit tests"

failed=$(grep -oE 'finished: [0-9]+ failed' "$test_log" | awk '{ sum += $2 } END { print sum + 0 }')
[[ "$failed" -eq 0 ]] || fail "$failed unit tests failed"

executed=$(grep -oE 'finished: [0-9]+ failed, [0-9]+ ignored, [0-9]+ total' "$test_log" |
  awk '{ sum += $6 } END { print sum + 0 }')
if [[ "$executed" -lt 100 ]]; then
  fail "only $executed tests executed — the suite is not actually running"
fi
echo "  $executed tests executed"

# An excluded suite still reports a line, with everything ignored and a total of
# zero. So a `*Props` suite with a non-zero total is a property suite that ran
# inside the routine gate — which is what a mis-scoped --exclude-tags looks like
# from the outside, and it is silent otherwise because the properties pass.
leaked=$(grep -oE 'Test run [A-Za-z0-9_.]*Props finished: [0-9]+ failed, [0-9]+ ignored, [0-9]+ total' "$test_log" |
  awk '$(NF - 1) != 0 { print $3 }' | sort -u)
if [[ -n "$leaked" ]]; then
  printf '\033[31m  property suites ran inside the unit gate:\033[0m\n' >&2
  printf '    %s\n' "$leaked" >&2
  printf '  Each module in the chain needs its own --exclude-tags=Property; Mill\n' >&2
  printf '  applies a trailing one to the last target only.\n' >&2
  fail "the Property exclusion did not take effect"
fi
echo "  no property suite ran inside the gate"

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

# scala.concurrent.duration is fine everywhere — FiniteDuration is how timeouts
# and backoff are typed. It is Future and ExecutionContext that must not appear
# below the client module.
#
# The JSON alternatives are the other half of PLAN.md §3.1: domain and core know
# neither the transport nor a JSON library. Until this commit the list read
# `upickle|ujson`, which stopped being a boundary the moment commit 48f64fe
# removed upickle — the library actually on the classpath,
# `com.github.plokhotnyuk.jsoniter_scala`, could have been imported straight
# into domain or core and this step would still have printed "boundaries clean".
# The vendor prefix is matched rather than the full package so a future
# `jsoniter_scala.macros` import is caught by the same alternative.
#
# upickle, ujson, circe, play-json, zio-json, Jackson, json4s and Gson are named
# even though none of them is a dependency. A pattern for a library that is not
# there costs one alternation and catches the day somebody adds it, which is the
# only day this check has anything to say.
#
# Matching imports rather than the build graph is deliberate: the graph already
# gives domain and core no `mvnDeps` at all, so a violation needs a `mvnDeps`
# edit as well as an import. This grep is what makes the import half fail
# loudly, and it is also what covers the case where the offending symbol arrives
# through a module that is on the graph.
readonly FORBIDDEN_JSON='com\.github\.plokhotnyuk|upickle|ujson|io\.circe|play\.api\.libs\.json|zio\.json|com\.fasterxml\.jackson|org\.json4s|com\.google\.gson'
readonly FORBIDDEN_BELOW_CLIENT="^import (sttp|$FORBIDDEN_JSON|scala\\.concurrent\\.(Future|ExecutionContext|Await|Promise|blocking))"

boundaries_ok=true
boundary_violation modules/domain/src "$FORBIDDEN_BELOW_CLIENT" \
  'domain must depend on nothing but the standard library' || boundaries_ok=false
boundary_violation modules/core/src "$FORBIDDEN_BELOW_CLIENT" \
  'core must not know about sttp, a JSON library or Future' || boundaries_ok=false
boundary_violation modules/codec/src '^import sttp' \
  'codec must not know about the transport' || boundaries_ok=false
# The mirror image of the line above, and stated in docs/adr/0003 as the reason
# the unused sttp-upickle integration was dropped: the transport reads every
# body as a String and hands it to codec, so the JSON library stays out of it.
boundary_violation modules/transport/src "^import ($FORBIDDEN_JSON)" \
  'transport must not know about a JSON library — it reads bodies as String' || boundaries_ok=false
boundary_violation 'modules/domain/src modules/core/src modules/codec/src modules/transport/src modules/client/src' \
  'Await\.(result|ready)' 'Await is banned in production code' || boundaries_ok=false
boundary_violation 'modules/domain/src modules/core/src modules/codec/src modules/transport/src modules/client/src' \
  'new (Exception|RuntimeException|IllegalStateException)\(' \
  'bare exceptions are banned — every failure carries a CallContext' || boundaries_ok=false
# The security contract on `CodebergRequest` says no call site can set an
# `Authorization` header, and the reason it holds is that no endpoint spells the
# constructor out: the builders in `object CodebergRequest` take no headers
# argument at all, so a caller has nowhere to put one. That is only true while
# the constructor stays unapplied outside core, which is what this checks.
#
# Core itself is not listed, because the builders are the constructor's one
# production call site. Test sources are not listed either: SttpHttpPortSuite
# builds a request bearing an Authorization header on purpose, to prove the
# transport strips it, and that test is the evidence for the other half of the
# contract.
boundary_violation 'modules/codec/src modules/transport/src modules/client/src' \
  '[^.[:alnum:]]CodebergRequest\(' \
  'build requests with the CodebergRequest builders, not the constructor' || boundaries_ok=false
$boundaries_ok || fail "architecture boundaries"
echo "  boundaries clean"

# Every `Attempt` mirror must delegate to the rail method of the SAME NAME.
#
# AttemptParitySuite already checks the two rails structurally — same names,
# same erased parameters, the return type wrapped in Either. What it cannot
# check is the one line inside each mirror, because nothing it does invokes
# one. A mirror that delegates to the wrong rail method passes every check it
# makes, as long as the target shares the parameter list and element type.
#
# That is not hypothetical. `IssueSubscriptionApi.Attempt.subscribe` and
# `.unsubscribe` both read `(Owner, RepoName, IssueNumber, Owner)` returning
# `Future[Either[CodebergError, Unit]]`, and the same shape recurs across the
# follow/unfollow and star/unstar families. Editing a copied neighbour is how
# a new operation gets written here, so swapping one word is the likeliest
# mistake in the likeliest change.
#
# MEASURED: with `unsubscribe` delegating to `subscribe`, AttemptParitySuite
# passed all six of its tests and the whole 3604-test suite stayed green. This
# check failed, naming the file and line. It is a grep because the invariant is
# a source-level one — the mirrors are hand-written by design — and because
# invoking 437 mirrors reflectively would need a synthesised argument for every
# parameter type in the library, which is a larger and more brittle thing than
# the bug it would catch.
mirrors_checked=0
mirror_violations=""
while IFS= read -r line; do
  file=${line%%:*}
  rest=${line#*:}
  lineno=${rest%%:*}
  target=$(sed -n "${lineno}p" "$file" | sed -E 's/.*exec\.attempt\(rail\.([A-Za-z0-9_]+).*/\1/')
  # The nearest `def` at or above this line is the mirror this call belongs to.
  owner=$(sed -n "1,${lineno}p" "$file" | grep -oE '^[[:space:]]*(override )?def [A-Za-z0-9_]+' | tail -1 |
    sed -E 's/.*def //')
  mirrors_checked=$((mirrors_checked + 1))
  if [[ "$owner" != "$target" ]]; then
    mirror_violations+="    $file:$lineno: Attempt.$owner delegates to rail.$target"$'\n'
  fi
done < <(grep -rInE 'exec\.attempt\(rail\.' modules/client/src --include='*.scala')

if [[ -n "$mirror_violations" ]]; then
  printf '\033[31m  %s\033[0m\n' 'an Attempt mirror delegates to a differently named rail operation'
  printf '%s' "$mirror_violations"
  fail "Attempt rail delegation"
fi
(( mirrors_checked > 0 )) || fail "Attempt delegation check found no mirrors — the pattern is stale"
echo "  $mirrors_checked Attempt mirrors delegate to their own rail operation"

# ---------------------------------------------------------------------------
announce "Coverage"
for module in "${COVERED_MODULES[@]}"; do
  "$MILL" "${module}.scoverage.xmlReport" || fail "coverage report for $module"
done
if [[ -f scripts/coverage-gate.sc ]]; then
  scala-cli run scripts/coverage-gate.sc --server=false -- "${COVERED_MODULES[@]}" ||
    fail "coverage thresholds"
else
  echo "  (scripts/coverage-gate.sc absent — thresholds not enforced yet)"
fi

# ---------------------------------------------------------------------------
if $with_slow; then
  # PMD 7.26.0 was confirmed to ship a working Scala tokenizer and to find real
  # duplication in modules/*/src, so this step is a genuine gate.
  #
  # cpd.sh is invoked in --report mode, which never fails on duplication, and
  # the baseline comparison below decides instead. Its other exit codes still
  # matter and are still fatal: 1 means PMD could not tokenise a file (a file it
  # cannot parse is a file it cannot check), and anything else means the tool
  # could not run at all — offline on a first run, say. Neither may be reported
  # as clean code, which is why they are separated from "found duplication".
  announce "Duplication (PMD CPD)"
  if [[ -x scripts/cpd.sh ]]; then
    cpd_log="$work_dir/cpd.log"
    set +e
    scripts/cpd.sh --report >"$cpd_log" 2>&1
    cpd_status=$?
    set -e
    case "$cpd_status" in
      0) ;; # report mode: duplication does not fail here, the baseline below decides
      1) cat "$cpd_log"; fail "PMD CPD could not tokenise some sources — a file it cannot parse is a file it cannot check" ;;
      *) cat "$cpd_log"; fail "PMD CPD could not run (see above) — this is not a clean result" ;;
    esac

    cpd_groups=$(grep -c '^Found a ' "$cpd_log" || true)
    printf '  %s duplication group(s) at %s+ tokens · baseline %s\n' \
      "$cpd_groups" "${CPD_MIN_TOKENS:-40}" "$CPD_BASELINE_GROUPS"

    if [[ "$cpd_groups" -gt "$CPD_BASELINE_GROUPS" ]]; then
      cat "$cpd_log"
      printf '\n\033[31m  duplication rose from %s group(s) to %s.\033[0m\n' \
        "$CPD_BASELINE_GROUPS" "$cpd_groups" >&2
      printf '  Deduplicate what this change added, or — if the increase is genuinely\n' >&2
      printf '  the cost of something better — raise CPD_BASELINE_GROUPS in this script\n' >&2
      printf '  in the same commit and say why in the message. Do not raise\n' >&2
      printf '  CPD_MIN_TOKENS; that hides the finding rather than recording it.\n' >&2
      fail "duplication above the recorded baseline"
    fi

    if [[ "$cpd_groups" -lt "$CPD_BASELINE_GROUPS" ]]; then
      printf '\033[32m  duplication fell below the baseline (%s < %s).\033[0m\n' \
        "$cpd_groups" "$CPD_BASELINE_GROUPS"
      printf '  Lower CPD_BASELINE_GROUPS in verify.sh to %s so the ground gained is held.\n' \
        "$cpd_groups"
    elif [[ "$cpd_groups" -gt 0 ]]; then
      printf '\033[33m  %s known duplication group(s) — tracked debt, not a clean result.\033[0m\n' \
        "$cpd_groups"
      printf '  docs/LEDGER.md § "Helpers awaiting promotion" names the ones with owners.\n'
      printf '  Run scripts/cpd.sh --report to see them all.\n'
    fi
  else
    echo "  (scripts/cpd.sh absent — skipped)"
  fi

  # crap.sc reads the same scoverage XML the coverage step produced, so it is
  # given the same module list rather than its own default.
  announce "CRAP (coverage-weighted complexity)"
  if [[ -f scripts/crap.sc ]]; then
    set +e
    scala-cli run scripts/crap.sc --server=false -- "${COVERED_MODULES[@]}"
    crap_status=$?
    set -e
    case "$crap_status" in
      0) ;;
      1) fail "CRAP above 30 for at least one method" ;;
      *) fail "CRAP could not run — a scoverage report was missing or unreadable" ;;
    esac
  else
    echo "  (scripts/crap.sc absent — skipped)"
  fi
fi

# ---------------------------------------------------------------------------
if $nightly; then
  # scripts/mutate.sh deliberately refuses to exit 0 without a mutation score.
  # If build.mill does not yet declare the Stryker4s Mill plugin it prints the
  # wiring and fails, rather than reporting a green nightly that mutated
  # nothing. See the header of that script for what has and has not been
  # verified about Stryker4s on this build.
  announce "Mutation testing (Stryker4s)"
  if [[ -x scripts/mutate.sh ]]; then
    scripts/mutate.sh || fail "mutation testing did not produce a passing score (see above)"
  else
    echo "  (scripts/mutate.sh absent — skipped)"
  fi
fi

printf '\n\033[32m✓ verify.sh passed in %ds\033[0m\n' "$(($(date +%s) - start_time))"
