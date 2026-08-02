# codeberg4s roadmap

The single source of truth for what is done and what is next. Every commit that
completes a line ticks its box here. Endpoint-level coverage is tracked
separately, in `docs/API_INVENTORY.md`.

Gate names match `PLAN.md` §7.

## Phase 0 — Bootstrap · **Gate G0**

- [x] Mill 1.1.7 pinned in `.mill-version`; bootstrap `./mill` committed (ADR-0006)
- [x] Six hexagonal modules: `domain`, `core`, `codec`, `transport`, `client`, `it`
- [x] `scalacOptions` with warnings fatal
- [x] Scalafmt + Scalafix configs, `mill modules.__.reformat` green
- [x] scoverage wired via `mill-contrib-scoverage`
- [x] ADRs 0001–0006, constitution mapping
- [x] Scalafix wired via `mill-scalafix` (Mill 1.x has no built-in `fix`; ADR-0006)
- [x] `verify.sh` with the ordered gate and an architecture-boundary check
- [x] CI pipeline (`.forgejo/workflows/ci.yml`) — the `verify` job runs on push
      and pull request. The `nightly` and `spec-drift` jobs are defined but
      unreachable; see Phase 4.

## Phase 1 — Recon and foundation · **Gate G-R**, **Gate G1**

- [x] Swagger spec vendored and checksummed (`spec/swagger.v1.json`, `docs/SPEC_PROVENANCE.md`)
- [x] `docs/API_INVENTORY.md` — 506 operations listed, in-scope marked
- [x] `docs/HAZARDS.md` — all six hazards verified against live probes; two of
      PLAN.md's assumptions turned out to be wrong
- [x] 54 golden fixtures captured (`modules/codec/test/resources/golden/`)
- [x] `domain`: error ADT, `CallContext`, opaque identifiers, `Auth`, config, paging value types
- [x] `core`: `Exec[F]`, ports, `RetryEngine`, `Pagination`, `LinkHeader`, `Pages`,
      `ApiPipeline`, `StatusMapping`, `Redaction`
- [x] Vertical slice: `GET /version` and `GET /repos/{owner}/{repo}` through every layer, both rails
- [x] Full error paths on the slice: 404, decode failure, transport failure, retry-then-succeed

## Phase 2 — Cross-cutting hardening · **Gate G2**

- [x] Track A — retry engine, pagination driver, `Page` / `foldPages`, `Telemetry` port
      wired through both `CodebergClient` factories
- [x] Track B — error ADT, Forgejo error-body parsing against captured samples,
      redaction guarantees, `CodebergException` bridging
- [ ] `listAll` / `foldPages` reachable from the public API. `core.Pagination`
      implements both and `PaginationSuite` covers them, but **no `*Api` class
      exposes either**, and `Pagination` is not constructible from outside
      `modules/core`. A user today can only drive pages by hand off
      `Page.nextPage`. Every group with a listing operation needs the two
      methods, on both rails.
- [ ] Property suites (`*Props.scala`, `Property` tag) for codec laws, pagination
      invariants, retry bounds. **Partially done:** `modules/domain` now has
      `PropertyBase` (pinned ScalaCheck seed, `Property` tag), `IdentifierProps`
      (13 properties over the opaque identifiers and `BaseUri`) and
      `SecretProps` (9 properties asserting no rendering path emits a
      credential). The three areas `PLAN.md` §6.3 actually names — codec
      round-trip laws in `codec`, pagination-driver invariants and retry bounds
      in `core` — have no property suite yet.

## Phase 3 — Endpoint waves · **Gate G3** per wave

Priority order is value-weighted, per `PLAN.md` §7. Shared models are owned by
the first wave that needs them; later waves consume rather than redefine
(`docs/LEDGER.md`).

- [x] Wave 1 — **users** (8 operations): current user, lookup by name, search,
      repositories, followers, following, keys
- [x] Wave 2 — **repos** (11 operations): read, search, branches, branch, tags,
      releases, release, topics, commits, forks, contents (the union response —
      see `docs/HAZARDS.md`)
- [x] Wave 3 — **issues** (10 operations): list/read/create/edit, comments, labels, milestones
- [x] Wave 4 — **pulls** (8 operations): list/read/create/edit, merge, reviews, commits, files
- [x] Wave 5 — **orgs** (10 operations): organisation read and list, repos, members,
      public members, teams, team read, team members, team repos, a user's orgs
- [x] Wave 6 — **notifications** (7 operations): list, mark all read, unread count,
      thread read, thread mark-read, per-repository list and mark-read
- [x] Wave 7 — **misc** (6 operations): markdown render (both forms), the three
      `settings/*` endpoints, signing key
- [ ] `GET /repos/issues/search` — deferred out of wave 7 because it needs wave 3's
      `IssueDto`, which did not exist when that lane ran. Note it returns a bare
      array, not the `{ok, data}` envelope the other search endpoints use.

That is **61 of the 439 in-scope operations, 13.9 %** (`docs/API_INVENTORY.md`
§0). Gate G3-final is 100 %, so the seven waves are the common core, not the
finish line: the untouched remainder is the long tail of `repository` (actions,
hooks, deploy keys, wikis, attachments, collaborators, subscriptions) and of
`user` (settings, stars, blocks, GPG keys, quotas, tokens), plus every write
operation outside issues, pulls and labels.

Per-wave definition of done: models from golden fixtures · codec round-trips ·
both rails · Scaladoc stating the error contract · inventory checkbox flipped ·
coverage thresholds hold. (CRAP and CPD thresholds are part of this definition
on paper only until those tools are proven — `docs/CONSTITUTION_MAPPING.md`.)

## Phase 4 — Release engineering · **Gate G4**

- [ ] Stryker4s wired, ≥ 80 % mutation score on `domain` + `core` + `codec` —
      `scripts/mutate.sh` exists and the **runner is proven**: the 1.1.1 command
      runner generates mutants from these sources, scalameta parses all 196
      production files under the Scala 3 dialect, the instrumented output
      recompiles under `-Werror`, and the break threshold demonstrably fails the
      run. **No score exists for this repository** — that needs a run with the
      real test command, roughly 1400 `mill test` invocations. The ≥ 80 % figure
      is still a target. `docs/CONSTITUTION_MAPPING.md` has the detail.
- [ ] PMD CPD wired, fails above 40 duplicated tokens in production sources —
      **wired and verified; the gate is red.** PMD 7.26.0's scalameta Scala
      module tokenises Scala 3 here without a lexical error, and
      `scripts/cpd.sh --report` currently finds **62 duplication groups** (128
      locations in `codec`, 40 in `client`, 15 in `domain`, 2 in `core`), so
      `./verify.sh --with-slow` fails at that step. Unticked because the
      codebase does not pass, not because the tool does not work. The fix is
      `docs/LEDGER.md` §"Helpers awaiting promotion", not a lower threshold.
- [x] `scripts/crap.sc`, fails on any method with CRAP > 30 — implemented over
      the scoverage XML. Note the complexity input is a documented proxy
      (`branch="true"` statement count), not a control-flow analysis; the
      script's header lists the three directions it is known to be wrong in.
      Read it as a ranking, not a certified metric.
- [x] `modules/it` — Testcontainers-Forgejo suite (`ForgejoContainerSuite`) plus
      the live-Codeberg smoke suite behind `CODEBERG_IT=1` (`CodebergLiveSmokeSuite`),
      both tagged `Integration` and excluded from `verify.sh`
- [x] `scripts/coverage-gate.sc` — reads scoverage's own `statement-rate` and
      `branch-rate`, floors at 90/85 for `domain`+`core`+`codec` and 80/80 for
      `transport`+`client`, and treats a missing report as a failure rather than
      a skip. Called by `verify.sh`; the thresholds have not yet been asserted
      against a freshly generated report.
- [ ] README with compiling examples, `CHANGELOG.md` — `CHANGELOG.md` written;
      README rewritten and every Scala block compiled against the current
      sources under the project's flags. Unticked because that check is manual:
      `PLAN.md` §"Phase 4" asks for mdoc so the *build* enforces it.
- [ ] Maven Central publishing config, MIMA baseline from 0.1.0 — publishing is
      **configured**: `build.mill` publishes five artifacts
      (`codeberg4s-domain`, `-core`, `-codec`, `-transport`, `-client`) under
      `com.worxbend` at `0.1.0-SNAPSHOT`, MIT, `EarlySemVer`, with `modules.it`
      deliberately excluded. Nothing has been published, and there is **no MIMA
      setup at all** — no plugin, no baseline. Both remain.
- [ ] Nightly spec-drift detector — `.forgejo/workflows/ci.yml` defines both a
      `nightly` job and a `spec-drift` job, and **both are gated on
      `github.event_name == 'schedule'` while the workflow declares only `push`
      and `pull_request` triggers.** Neither has ever run. Adding a `schedule:`
      trigger is the whole fix, and the drift job currently only warns on a
      sha mismatch — it does not open an issue, as `PLAN.md` §7 asks.

## Distance to 0.1.0

`PLAN.md` §10 defines done. Measured against it, honestly:

| Definition-of-done clause | State |
| ------------------------- | ----- |
| All in-scope endpoints on both rails with documented error contracts | 61 / 439 — every one of the 61 is on both rails with a Scaladoc error contract, so the shape is right and the surface is 14 % of the way there |
| `verify.sh` green | Default run yes: format, lint, zero-warning compile, 1072 unit tests, boundary check, coverage. **`--with-slow` is red** at the CPD step |
| Coverage per §6.1 (≥ 90 % line / ≥ 85 % branch on `domain`+`core`+`codec`) | Report is produced and `scripts/coverage-gate.sc` now enforces the floors; the assertion has not yet been run against a fresh report |
| Mutation ≥ 80 % | Runner proven, **no score produced** — see Phase 4 |
| Zero CRAP > 30 | Gate implemented; not yet run against a fresh coverage report, and its complexity input is a proxy |
| CPD clean | **No — 62 duplication groups at 40 tokens.** The tool works; the codebase does not pass it yet |
| Acceptance features + Gherkin mutation clean | Dormant by decision — `docs/CONSTITUTION_MAPPING.md` |
| Published to Maven Central | Configured but not published; no MIMA baseline |
| README quickstart works against live codeberg.org | Samples are checked against the source signatures by hand. `CodebergLiveSmokeSuite` exercises the same calls against codeberg.org under `CODEBERG_IT=1`, but nobody has run the README itself |
| Mapping doc, ADRs, inventory, provenance current | Yes, as of this revision |

The single largest remaining item is the endpoint surface. The most urgent one
is the CPD result: the duplication gate now works, and it says the codebase has
62 duplication groups. That is a real finding about the code, not a tooling
problem, and `docs/LEDGER.md` already names most of them.

## Out of scope for 0.1.0

Per `PLAN.md` §0: OAuth2 token *acquisition* flows (pre-obtained tokens only),
ActivityPub federation, admin endpoints, attachment streaming above 50 MB, and
Scala.js / Native cross-builds. The Gherkin acceptance pipeline is dormant —
see `docs/CONSTITUTION_MAPPING.md`.
