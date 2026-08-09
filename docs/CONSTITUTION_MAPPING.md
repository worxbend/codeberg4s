# Constitution mapping — swarm-forge `engineering.prompt` → Scala / Mill

`CLAUDE.md` adapts unclebob's swarm-forge engineering constitution to this repo.
The constitution's startup tool table covers Go, Clojure and Java only; Scala is
unlisted. This document records the equivalents chosen, and — just as important
— which of them are **actually wired into the build today** versus documented but
deliberately not scaffolded.

`CLAUDE.md` is explicit that a tool the build does not use must not be
scaffolded. The "Status" column is therefore normative, not aspirational, and it
distinguishes four things:

- **Wired** — in the build or in `verify.sh`, exercised by an ordinary run, and
  observed to fail when it should.
- **Wired, gate red** — the same, and the repository does not currently pass it.
- **Runner proven, result not** — the tool has been shown to parse and process
  this codebase, but the gate it is supposed to enforce has never produced a
  number here.
- **Not scaffolded** — deliberately absent.

| Constitution requirement            | Scala / Mill equivalent                                                        | Status |
| ----------------------------------- | ------------------------------------------------------------------------------ | ------ |
| Compile with warnings fatal         | `scalacOptions` with `-Werror -Wunused:all -Wvalue-discard -Wnonunit-statement` | Wired  |
| Formatter                           | Scalafmt 3.11.4, `mill mill.scalalib.scalafmt/`                                 | Wired  |
| Linter / semantic rules             | Scalafix, `mill modules.__.fix`, rules in `.scalafix.conf`                       | Wired  |
| Coverage                            | scoverage via `mill-contrib-scoverage`; thresholds in `scripts/coverage-gate.sc`, called by `verify.sh` | Wired (report + gate script); thresholds never yet asserted on a real report |
| Mutation tool (`mutate4*`)          | **Stryker4s 1.1.1** command runner over `domain` + `core` + `codec`, wrapped by `scripts/mutate.sh` | **Runner proven, result not** |
| DRY tool (`dry4*`)                  | **PMD 7.26.0 CPD**, scalameta-based Scala module, wrapped by `scripts/cpd.sh`    | **Wired, gate red** |
| CRAP tool (`crap4*`)                | `scripts/crap.sc` — CRAP = `comp² × (1 − cov)³ + comp`, from the scoverage XML  | Wired; **complexity is a documented proxy, not a CFG analysis** |
| Integration / environmentally unsuitable boundary | `modules/it`, Testcontainers-Forgejo plus an opt-in live smoke suite, excluded from the default run | Wired  |
| Property tests separated            | `Property` munit tag, excluded by `verify.sh --exclude-tags=Property`            | Wired; suites exist in `domain` only |
| Acceptance Pipeline (APS, Gherkin)  | `gherkin-parser` / `gherkin-mutator` from unclebob/Acceptance-Pipeline-Specification | **Not scaffolded** |
| Speclj / Clojure defaults           | n/a — Clojure-only rules                                                        | n/a    |
| "Avoid Maven for Java tests"        | Analogue: acceptance work would get a dedicated runner module, not `mill modules.__.test` | n/a while APS is dormant |

## The three quality tools, in detail

`verify.sh` calls all of them behind an existence test — `scripts/cpd.sh` and
`scripts/crap.sc` under `--with-slow`, `scripts/mutate.sh` under `--nightly`,
`scripts/coverage-gate.sc` in the default run — and prints a
"(absent — skipped)" line when a file is missing. All four scripts now exist,
but the guard remains, so **read the step output rather than the exit code.**

### PMD CPD — wired, and currently failing

This one is settled. PMD 7.26.0 ships `pmd-scala_2.13`, a scalameta-based
tokenizer, and `pmd cpd -l` lists `scala`. Run against `modules/*/src` on this
repository it tokenises Scala 3 indentation syntax, `given`/`using`, `enum`,
`extension` and end markers without a single lexical error, and the duplications
it reports are genuine. It is a real gate, not a stub.

It is also **red**. Measured on 2026-08-09, `scripts/cpd.sh --report` finds
**378 duplication groups at the 40-token threshold**, spread over 1360 source
locations — 764 in `codec`, 541 in `client`, 45 in `domain`, 8 in `core`, 2 in
`transport`. `scripts/cpd.sh` in gate mode therefore exits 1 today.
`./verify.sh --with-slow` does not fail on that count alone: it compares
against `CPD_BASELINE_GROUPS` in `verify.sh`, which records the same 378, and
fails only when the count rises above it. The recorded number is debt written
down, not debt forgiven. The findings corroborate
`docs/LEDGER.md` §"Helpers awaiting promotion" (`FilterToken.from` against
`PathSegment.from`, the repeated `Wire.required`/`Wire.validated` blocks in the
DTOs, the `page`/`limit` pair). Some groups are import blocks, which is CPD
noise; most are not. Fixing them is the work `docs/LEDGER.md` already names, and
nobody should lower `CPD_MIN_TOKENS` instead — that is a threshold change and
needs an ADR per `PLAN.md` §8.

### Stryker4s — the runner works, the score does not exist

Two non-sbt paths exist and neither needs a shim:

- The **Mill plugin** `io.stryker-mutator::mill-stryker4s::1.1.1` is published
  as `mill-stryker4s_mill1_3`, compiled against `mill-libs-scalalib 1.1.7` —
  exactly the Mill in `.mill-version` — and derives its scalameta dialect from
  `scalaVersion`. It needs two lines in `build.mill`, which have not been added,
  and it has not been run.
- The **command runner** `io.stryker-mutator::stryker4s-command-runner:1.1.1`
  has been exercised end to end on these sources: 444 mutants generated from
  `modules/domain/src`, 216 from `modules/core/src`; scalameta 4.17.3 parsed all
  196 production files of `domain` + `core` + `codec` under the Scala 3 dialect
  with zero errors; the instrumented output recompiled cleanly under this
  project's exact flags including `-Werror`; and with a stub test command the run
  reported 0.0 %, failed the break threshold and exited 1 — so the gate
  demonstrably fails when it should.

What has **not** happened is a run with the real test command. Stryker4s invokes
it once per mutant, so `./mill modules.<m>.test` would run roughly 1400 times
across the three modules. Expect hours. Until that run exists there is **no
mutation score for this repository**, and the ≥ 80 % figure in
`docs/ROADMAP.md` is a target, not a measurement.

### `scripts/crap.sc` — works, on an approximate input

The script reads scoverage's XML and applies `comp² × (1 − cov)³ + comp`. The
arithmetic is exact; the complexity is not. scoverage reports statement coverage
and carries no cyclomatic-complexity metric, and there is no complexity tool for
Scala 3 in this build, so the script derives

    comp(m) = max(1, statements in m marked branch="true")

which is exact for `if`, `match`, `try`/`catch` — the shapes that dominate this
codebase — and wrong in three known directions the script's own header spells
out: it under-counts short-circuit booleans, attributes nested lambdas and local
defs to the enclosing method, and treats an uninstrumented method as complexity
1. Read its output as a ranking of risk, not as a certified metric, and look at
a method near the limit before arguing with the threshold.

Anyone who moves one of these rows should do it in the same commit that changes
the script, and update `docs/ROADMAP.md` §"Phase 4" to match. Do not promote a
row on the strength of a run that exited 0 — the tool has to be shown to fail on
a defect it is supposed to catch.

## Why the acceptance pipeline is dormant

`CLAUDE.md` §"Acceptance tests" states plainly: *"No Gherkin/APS pipeline exists
in this repo. Those rules from the original are dormant; do not scaffold one
unless asked."* `PLAN.md` §6.2 describes one in detail, but the repo owner's
instruction file is the later and more specific authority, and the owner
confirmed the exclusion when scoping this build.

If APS is adopted later, the constitution's constraints apply as written:
Babashka variants preferred with Go as fallback, installed fresh from upstream
rather than vendored; acceptance generation and execution run **sequentially**
and never concurrently with a whole-suite `mill __.test`; `gherkin-mutator` runs
must emit heartbeat progress (that is what `scripts/gherkin-mutate.sh` would
wrap); and manifests are tool-managed — never hand-edited.

## Constitution rules adopted verbatim

- **Small, reviewable increments.** One logical change per commit, Conventional
  Commits, committed as work completes rather than in one lump. Enforced by
  `CLAUDE.md` §"Commit granularity".
- **Testable vs environmentally-unsuitable module separation.** `modules/it` is
  the only module allowed to touch a real network or a container, and it is
  excluded from coverage, mutation, CRAP and CPD runs.
- **Property tests kept out of routine verification.** `verify.sh` passes
  `--exclude-tags=Property`, so the suites run on request rather than in the
  default gate and mutation and coverage numbers stay comparable
  build-to-build. `modules/domain` has `PropertyBase` — which also pins the
  ScalaCheck seed, so a counterexample is reproducible rather than a flake —
  plus `IdentifierProps` and `SecretProps`. Nothing enforces the tag
  mechanically: a property written without `.tag(Property)` silently rejoins the
  default gate, which is a review item, not a build one. Codec, pagination and
  retry properties are still missing; see `docs/ROADMAP.md` §"Phase 2".
- **Project-local caches.** `out/` and `.cache/` live inside the worktree and are
  gitignored; nothing the build does writes outside the project.
- **Inspect `--help` or the docs before relying on an unfamiliar command.**
- **Run `./verify.sh` before every handoff.** The ordered gate is §6.6 of
  `PLAN.md`; the script is the executable copy of it. Steps 5 and 6 of §6.6 —
  acceptance, then CPD and CRAP — are respectively dormant and unproven, so a
  green `./verify.sh` today certifies formatting, lint, a zero-warning compile,
  the unit suite, the architecture boundaries and a coverage report, and nothing
  beyond that.

## Deviations from `PLAN.md`, with reasons

| `PLAN.md` says                   | Reality                                                             | Recorded in |
| -------------------------------- | -------------------------------------------------------------------- | ----------- |
| Models generated or curated?     | Curated, fixtures over spec                                          | ADR-0001    |
| `Exec[F]` vs cats-effect/ZIO     | Hand-rolled `Exec[F]`                                                | ADR-0002    |
| jsoniter-scala vs circe/jsoniter        | jsoniter-scala                                                              | ADR-0003    |
| `softwaremill/retry` first       | Disqualified at resolution; retry implemented in `core`              | ADR-0004    |
| `Future` API vs style guide's Ox | `Future`, per the owner's decision                                   | ADR-0005    |
| Mill 0.12.x                      | Mill 1.1.7                                                           | ADR-0006    |
| Gherkin acceptance pipeline      | Dormant, not scaffolded                                              | this file   |
| Stryker4s / CPD / CRAP gate the build | Runners exist; none proven against Mill + Scala 3 yet           | this file, §"Script only" |
