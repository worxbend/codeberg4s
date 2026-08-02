#!/usr/bin/env bash
#
# scripts/cpd.sh — duplicate-code gate (PLAN.md §6.4).
#
# PMD's Copy/Paste Detector over production Scala sources only. Test sources are
# exempt: fixture tables and golden-JSON setups repeat by design, and gating them
# would push people to obfuscate fixtures rather than deduplicate logic.
#
# VERIFIED, NOT ASSUMED: PMD 7.26.0 still ships a Scala module
# (lib/pmd-scala_2.13-7.26.0.jar, scalameta-based) and `scala` is listed by
# `pmd cpd -l`. It was run against modules/*/src on this repo and tokenised
# Scala 3 indentation syntax, `given`/`extension`, and end markers correctly —
# the duplications it reported are genuine ones in hand-written code, with no
# lexical errors on stderr. So this is a real gate, not a stub.
#
# Usage:
#   scripts/cpd.sh              # gate: non-zero if any duplication is found
#   scripts/cpd.sh --report     # print the findings, always exit 0
#
# Environment:
#   CPD_MIN_TOKENS   duplication length that counts (default below)
#   CPD_FORMAT       PMD renderer: text, csv, markdown, xml (default text)
#   PMD_VERSION      PMD release to use (default below)
#   PMD_HOME         a pre-installed PMD directory; skips the download entirely

set -euo pipefail

cd "$(dirname "$0")/.."

# --- knobs -----------------------------------------------------------------

# PLAN.md §6.4: "fails on duplicated blocks > 40 tokens across production
# sources". Lowering this is a threshold change and needs an ADR, per the
# reviewer veto list in PLAN.md §8.
readonly MIN_TOKENS="${CPD_MIN_TOKENS:-40}"

readonly PMD_V="${PMD_VERSION:-7.26.0}"
readonly PMD_SHA256="9f55cb7ff0e9f9a66dd2f005eaa370e84c8a4cd971b134aa14a930c4a283ebc9"
readonly FORMAT="${CPD_FORMAT:-text}"

# Production sources. modules/*/test/src is deliberately absent, and modules/it
# is the environmentally-unsuitable boundary — excluded from every quality run.
readonly SOURCE_DIRS=(
  modules/domain/src
  modules/core/src
  modules/codec/src
  modules/transport/src
  modules/client/src
)

# Project-local tool cache. out/ is already gitignored, so nothing lands outside
# the worktree and nothing shows up in `git status`.
readonly TOOL_DIR="out/tools"
readonly PMD_DIR="${PMD_HOME:-$TOOL_DIR/pmd-bin-$PMD_V}"
readonly PMD_URL="https://github.com/pmd/pmd/releases/download/pmd_releases%2F$PMD_V/pmd-dist-$PMD_V-bin.zip"

report_only=false
case "${1:-}" in
  --report) report_only=true ;;
  "") ;;
  *) echo "cpd.sh: unknown option '$1' (expected --report)" >&2; exit 2 ;;
esac

# --- install ---------------------------------------------------------------

if [[ ! -x "$PMD_DIR/bin/pmd" ]]; then
  if [[ -n "${PMD_HOME:-}" ]]; then
    echo "cpd.sh: PMD_HOME=$PMD_HOME has no bin/pmd" >&2
    exit 2
  fi
  echo "  fetching PMD $PMD_V into $TOOL_DIR (once) ..."
  mkdir -p "$TOOL_DIR"
  zip="$TOOL_DIR/pmd-dist-$PMD_V-bin.zip"
  if ! curl -fsSL -o "$zip" "$PMD_URL"; then
    echo "cpd.sh: could not download PMD $PMD_V from $PMD_URL" >&2
    echo "         (offline? install PMD yourself and re-run with PMD_HOME=/path/to/pmd-bin-$PMD_V)" >&2
    exit 2
  fi
  if [[ "$PMD_V" == "7.26.0" ]]; then
    actual=$(sha256sum "$zip" | cut -d' ' -f1)
    if [[ "$actual" != "$PMD_SHA256" ]]; then
      echo "cpd.sh: checksum mismatch for $zip" >&2
      echo "         expected $PMD_SHA256" >&2
      echo "         actual   $actual" >&2
      rm -f "$zip"
      exit 2
    fi
  fi
  unzip -q -o "$zip" -d "$TOOL_DIR"
  rm -f "$zip"
fi

# Confirm the Scala module is present in this PMD build rather than discovering
# it is missing through an empty, green report.
cpd_help=$("$PMD_DIR/bin/pmd" cpd --help 2>&1 || true)
if ! grep -qE '(^|[ ,])scala([ ,]|$)' <<<"$cpd_help"; then
  echo "cpd.sh: PMD $PMD_V does not list 'scala' as a CPD language — refusing to report a" >&2
  echo "        vacuous pass. Pin a PMD version that ships pmd-scala, or record CPD as" >&2
  echo "        unwired in docs/CONSTITUTION_MAPPING.md." >&2
  exit 2
fi

# --- run -------------------------------------------------------------------

present=()
for dir in "${SOURCE_DIRS[@]}"; do
  [[ -d "$dir" ]] && present+=("$dir")
done
if [[ ${#present[@]} -eq 0 ]]; then
  echo "cpd.sh: none of the configured source directories exist" >&2
  exit 2
fi

joined=$(IFS=,; echo "${present[*]}")

echo "  PMD $PMD_V CPD — min-tokens=$MIN_TOKENS over ${#present[@]} production source dirs"

set +e
output=$("$PMD_DIR/bin/pmd" cpd \
  --language scala \
  --minimum-tokens "$MIN_TOKENS" \
  --format "$FORMAT" \
  --dir "$joined" 2>&1)
status=$?
set -e

# PMD exit codes: 0 clean, 4 duplications found, 5 recoverable errors, other = fatal.
case "$status" in
  0)
    echo "  no duplication of $MIN_TOKENS+ tokens"
    exit 0
    ;;
  4)
    printf '%s\n' "$output"
    count=$(grep -c '^Found a ' <<<"$output" || true)
    if $report_only; then
      echo
      echo "  $count duplication group(s) at $MIN_TOKENS+ tokens (report mode — not failing)"
      exit 0
    fi
    echo
    echo "  cpd: $count duplication group(s) of $MIN_TOKENS+ tokens" >&2
    exit 1
    ;;
  5)
    printf '%s\n' "$output"
    echo "  cpd: PMD reported recoverable errors (unparsed files) — treating as a failure" >&2
    echo "       a file CPD cannot tokenise is a file it cannot check for duplication" >&2
    exit 1
    ;;
  *)
    printf '%s\n' "$output"
    echo "  cpd: PMD exited $status" >&2
    exit 2
    ;;
esac
