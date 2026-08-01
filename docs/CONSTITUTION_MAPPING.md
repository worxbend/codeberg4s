# Constitution mapping — swarm-forge `engineering.prompt` → Scala / Mill

`CLAUDE.md` adapts unclebob's swarm-forge engineering constitution to this repo.
The constitution's startup tool table covers Go, Clojure and Java only; Scala is
unlisted. This document records the equivalents chosen, and — just as important
— which of them are **actually wired into the build today** versus documented but
deliberately not scaffolded.

`CLAUDE.md` is explicit that a tool the build does not use must not be
scaffolded. The "Status" column is therefore normative, not aspirational.

| Constitution requirement            | Scala / Mill equivalent                                                        | Status |
| ----------------------------------- | ------------------------------------------------------------------------------ | ------ |
| Compile with warnings fatal         | `scalacOptions` with `-Werror -Wunused:all -Wvalue-discard -Wnonunit-statement` | Wired  |
| Formatter                           | Scalafmt 3.11.4, `mill mill.scalalib.scalafmt/`                                 | Wired  |
| Linter / semantic rules             | Scalafix, `mill __.fix`, rules in `.scalafix.conf`                              | Wired  |
| Coverage                            | scoverage via `mill-contrib-scoverage`, `mill __.scoverage.htmlReport`          | Wired  |
| Mutation tool (`mutate4*`)          | **Stryker4s** 1.1.1, CLI runner over `domain` + `core` + `codec`                | Wired  |
| DRY tool (`dry4*`)                  | **PMD CPD** with the Scala tokenizer, threshold committed                       | Wired  |
| CRAP tool (`crap4*`)                | `scripts/crap.sc` — CRAP = `comp² × (1 − cov)³ + comp`, from the scoverage XML  | Wired  |
| Integration / environmentally unsuitable boundary | `modules/it`, Testcontainers-Forgejo, excluded from the default run | Wired  |
| Property tests separated            | ScalaCheck in `*Props.scala` under the `Property` munit tag, excluded by default | Wired  |
| Acceptance Pipeline (APS, Gherkin)  | `gherkin-parser` / `gherkin-mutator` from unclebob/Acceptance-Pipeline-Specification | **Not scaffolded** |
| Speclj / Clojure defaults           | n/a — Clojure-only rules                                                        | n/a    |
| "Avoid Maven for Java tests"        | Analogue: acceptance work would get a dedicated runner module, not `mill __.test` | n/a while APS is dormant |

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
- **Property tests kept out of routine verification.** They run on request, not
  in the default gate, so mutation and coverage numbers stay comparable
  build-to-build.
- **Project-local caches.** `out/` and `.cache/` live inside the worktree and are
  gitignored; nothing the build does writes outside the project.
- **Inspect `--help` or the docs before relying on an unfamiliar command.**
- **Run `./verify.sh` before every handoff.** The ordered gate is §6.6 of
  `PLAN.md`; the script is the executable copy of it.

## Deviations from `PLAN.md`, with reasons

| `PLAN.md` says                   | Reality                                                             | Recorded in |
| -------------------------------- | -------------------------------------------------------------------- | ----------- |
| Models generated or curated?     | Curated, fixtures over spec                                          | ADR-0001    |
| `Exec[F]` vs cats-effect/ZIO     | Hand-rolled `Exec[F]`                                                | ADR-0002    |
| upickle vs circe/jsoniter        | upickle                                                              | ADR-0003    |
| `softwaremill/retry` first       | Disqualified at resolution; retry implemented in `core`              | ADR-0004    |
| `Future` API vs style guide's Ox | `Future`, per the owner's decision                                   | ADR-0005    |
| Mill 0.12.x                      | Mill 1.1.7                                                           | ADR-0006    |
| Gherkin acceptance pipeline      | Dormant, not scaffolded                                              | this file   |
