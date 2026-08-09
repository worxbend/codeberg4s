#!/usr/bin/env bash
#
# Allocation and wall-clock harness for the JSON decode path — the entry point
# for scripts/alloc-bench.sc, which holds the measurement methodology and its
# limitations. Read that header before quoting any number this prints.
#
#   scripts/alloc-bench.sh                       # every operation
#   scripts/alloc-bench.sh timestamps repo       # only operations whose name contains one of these
#   scripts/alloc-bench.sh --rounds=11 --scale=4 # more rounds, four times the iterations
#   scripts/alloc-bench.sh --help                # the harness's own header, in full
#
# This is NOT part of verify.sh and must not become part of it: it is a
# measurement tool, not a gate, and a timing assertion in CI is a flaky test.
# Run it by hand before and after a change and quote both numbers.
#
# Why a wrapper exists at all: the harness names types from `modules.codec`, so
# it needs that module's classes and its jsoniter dependency on the classpath at
# COMPILE time, and a Scala script cannot put them there for itself. This script
# asks Mill where they are (`./mill show modules.codec.runClasspath`, which
# compiles the module first if it is stale) and hands the answer to scala-cli.
#
# Exit codes are the harness's own: 0 measured, 2 could not measure.

set -euo pipefail

cd "$(dirname "$0")/.."

readonly MILL="./mill"
readonly HARNESS="scripts/alloc-bench.sc"

for arg in "$@"; do
  case "$arg" in
    # Print the harness's header block, whatever length it has grown to. A fixed
    # line range goes stale the first time somebody documents a new operation.
    -h|--help)
      awk 'NR > 1 && /^\/\//{ sub(/^\/\/ ?/, ""); print; next } NR > 1 && NF { exit }' "$HARNESS"
      exit 0
      ;;
  esac
done

command -v scala-cli >/dev/null 2>&1 || {
  echo "alloc-bench: scala-cli is not on PATH — the same tool scripts/crap.sc needs" >&2
  exit 2
}

# `show` prints a JSON array whose entries are Mill path references, e.g.
#   "qref:v1:3cca3705:/home/…/jsoniter-scala-core_3-2.39.1.jar"
# so the path is everything from the first slash. Mill's progress output goes to
# stderr and is kept: if the build fails, its message is what explains why.
classpath=$(
  "$MILL" show modules.codec.runClasspath |
    grep -oE '/[^"]+' |
    grep -vE '/(scala-library|scala3-library_3)-[0-9.]+\.jar$' |
    paste -sd:
) || {
  echo "alloc-bench: could not resolve modules.codec.runClasspath (see above)" >&2
  exit 2
}

[[ -n "$classpath" ]] || {
  echo "alloc-bench: modules.codec.runClasspath came back empty" >&2
  exit 2
}

# The two standard-library jars are dropped above because scala-cli puts its own
# on the classpath, and two copies make the compiler warn that several versions
# of the standard library are present — noise on every single run.

exec scala-cli run "$HARNESS" --server=false --extra-jars "$classpath" -- "$@"
