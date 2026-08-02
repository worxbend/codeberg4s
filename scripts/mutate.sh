#!/usr/bin/env bash
#
# scripts/mutate.sh — mutation testing (PLAN.md §6.4, ROADMAP Phase 4 nightly).
#
# Stryker4s over domain + core + codec, break threshold 80.
#
# ---------------------------------------------------------------------------
# WHAT WAS ACTUALLY VERIFIED, AND WHAT WAS NOT
# ---------------------------------------------------------------------------
# Stryker4s 1.1.1 ships two runners that can drive a NON-sbt project. No sbt
# shim is needed. Both were investigated against this repo:
#
#  1. MILL PLUGIN — io.stryker-mutator::mill-stryker4s::1.1.1, published as
#     mill-stryker4s_mill1_3, compiled against mill-libs-scalalib 1.1.7, i.e.
#     exactly the Mill in .mill-version. Mixing `stryker4s.mill.Stryker4sModule`
#     into a ScalaModule adds a `stryker` command plus `strykerThresholdsBreak`
#     and friends, and it derives the scalameta dialect from `scalaVersion`.
#     This is the fast path: it reuses a warm test-runner JVM and does
#     per-mutant coverage analysis. It needs two lines in build.mill (printed
#     below when they are missing). NOT RUN HERE — this lane was not allowed to
#     invoke Mill, so the plugin is verified to exist and to target the right
#     Mill and Scala, not verified to produce a score.
#
#  2. COMMAND RUNNER — io.stryker-mutator::stryker4s-command-runner:1.1.1.
#     VERIFIED END TO END on this repo's sources: pointed at
#     modules/domain/src it generated 444 mutants, and at modules/core/src 216.
#     scalameta 4.17.3 (the version Stryker4s 1.1.1 depends on) parsed all 196
#     production files of domain + core + codec under the Scala3 dialect with
#     zero errors. The instrumented output — mutants coalesced behind
#     `scala.sys.env.get("ACTIVE_MUTATION")` switches — recompiled cleanly under
#     this project's exact flags, including -Werror -Wunused:all -Wvalue-discard
#     -Wnonunit-statement. With a stub test command the run reported 0.0%,
#     failed the break threshold and exited 1, so the gate demonstrably fails
#     when it should.
#     What was NOT verified: the real test command. Stryker4s runs it once per
#     mutant, so `./mill modules.<m>.test` is invoked ~1400 times over the three
#     modules. Expect hours. That is why this path is opt-in.
#
# Neither path was observed producing a real mutation score for this repo. Do
# not record mutation testing as "wired" in docs/CONSTITUTION_MAPPING.md on the
# strength of this script alone — record it when a run prints a score.
# ---------------------------------------------------------------------------
#
# Usage:
#   scripts/mutate.sh                     # Mill plugin if build.mill has it, else explain and fail
#   scripts/mutate.sh --command-runner    # force the verified-but-slow CLI path
#   scripts/mutate.sh --module core       # one module (repeatable)
#
# Environment:
#   STRYKER_VERSION       default below
#   STRYKER_BREAK         break threshold, default 80
#   STRYKER_CONCURRENCY   concurrent test runners, default 4
#   STRYKER_TEST_COMMAND  command-runner test command, default ./mill
#   STRYKER_TEST_ARGS     its arguments, default "modules.<m>.test --exclude-tags=Property"
#                         (both exist so the plumbing can be exercised without a
#                          real, hours-long test run — see the smoke check at the
#                          bottom of this file)

set -euo pipefail

cd "$(dirname "$0")/.."

readonly STRYKER_V="${STRYKER_VERSION:-1.1.1}"
readonly BREAK="${STRYKER_BREAK:-80}"
readonly CONCURRENCY="${STRYKER_CONCURRENCY:-4}"

# PLAN.md §6.4: domain, core, codec. transport and client are adapter shells and
# modules/it is the environmentally-unsuitable boundary — none are mutated.
DEFAULT_MODULES=(domain core codec)

readonly MILL="./mill"
readonly WORK_ROOT="out/tools/stryker4s"

mode=auto
modules=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --command-runner) mode=command-runner; shift ;;
    --mill-plugin) mode=mill-plugin; shift ;;
    --module) modules+=("$2"); shift 2 ;;
    -h|--help) sed -n '3,60p' "$0"; exit 0 ;;
    *) echo "mutate.sh: unknown option '$1'" >&2; exit 2 ;;
  esac
done

[[ ${#modules[@]} -gt 0 ]] || modules=("${DEFAULT_MODULES[@]}")

plugin_wired() {
  grep -q 'mill-stryker4s' build.mill && grep -q 'Stryker4sModule' build.mill
}

explain_plugin_wiring() {
  cat >&2 <<EOF

  build.mill does not declare the Stryker4s Mill plugin, so there is nothing to
  run. Two additions wire it (io.stryker-mutator::mill-stryker4s::$STRYKER_V is
  published for mill1_3 and built against mill-libs-scalalib 1.1.7, which is the
  Mill in .mill-version):

    //| mvnDeps:
    //| - io.stryker-mutator::mill-stryker4s::$STRYKER_V

    import stryker4s.mill.Stryker4sModule

    trait Codeberg4sModule extends ScalaModule with ... with Stryker4sModule:
      def strykerThresholdsBreak = Task { Some($BREAK) }

  then this script runs \`$MILL modules.<m>.stryker\` per module.

  Until then the verified-working alternative is the standalone CLI:

    scripts/mutate.sh --command-runner

  It needs no build change but re-runs the test command once per mutant, so
  budget hours, not minutes. It is nightly-only for that reason.

EOF
}

run_mill_plugin() {
  local failed=0
  for m in "${modules[@]}"; do
    echo
    echo "  stryker4s (Mill plugin) — modules.$m"
    if ! "$MILL" "modules.$m.stryker"; then
      echo "  mutation gate failed for modules.$m" >&2
      failed=1
    fi
  done
  return "$failed"
}

# Stryker4s reads stryker4s.conf from the *current working directory* and honours
# a `base-dir` inside it (verified against 1.1.1), so each module gets its own
# generated config under out/ and nothing is written to the repo root.
write_conf() {
  local module="$1" dir="$2"
  mkdir -p "$dir"
  cat > "$dir/stryker4s.conf" <<EOF
# GENERATED by scripts/mutate.sh — do not edit, it is rewritten on every run.
stryker4s {
  base-dir = "$PWD"
  mutate   = [ "modules/$module/src/**/*.scala" ]

  test-runner {
    command = "${STRYKER_TEST_COMMAND:-$MILL}"
    args    = "${STRYKER_TEST_ARGS:-modules.$module.test --exclude-tags=Property}"
  }

  thresholds { high = 95, low = 85, break = $BREAK }

  reporters    = [ "console", "html" ]
  scala-dialect = "scala3"
  concurrency  = $CONCURRENCY
}
EOF
}

run_command_runner() {
  if ! command -v cs >/dev/null 2>&1 && ! command -v coursier >/dev/null 2>&1; then
    echo "mutate.sh: the command-runner path needs coursier (cs) on PATH" >&2
    return 2
  fi
  local launcher
  launcher=$(command -v cs || command -v coursier)

  local failed=0
  for m in "${modules[@]}"; do
    local dir="$WORK_ROOT/$m"
    write_conf "$m" "$dir"
    echo
    echo "  stryker4s $STRYKER_V (command runner) — modules/$m/src, break=$BREAK"
    echo "  config: $dir/stryker4s.conf"
    echo "  NOTE: the test command runs once per mutant; this is a nightly-length job."
    if ! (cd "$dir" && "$launcher" launch "io.stryker-mutator::stryker4s-command-runner:$STRYKER_V"); then
      echo "  mutation gate failed for modules/$m" >&2
      failed=1
    fi
  done
  return "$failed"
}

case "$mode" in
  mill-plugin)
    plugin_wired || { explain_plugin_wiring; exit 1; }
    run_mill_plugin
    ;;
  command-runner)
    run_command_runner
    ;;
  auto)
    if plugin_wired; then
      run_mill_plugin
    else
      explain_plugin_wiring
      exit 1
    fi
    ;;
esac

# ---------------------------------------------------------------------------
# Smoke check used while writing this script (kept as documentation, not run):
#
#   STRYKER_TEST_COMMAND=true STRYKER_TEST_ARGS="" \
#     scripts/mutate.sh --command-runner --module domain
#
# With a stub test command every mutant survives, so the run must end with
# "Mutation score: 0.0%" and exit 1. If it exits 0, the gate is broken.
# ---------------------------------------------------------------------------
