# codeberg4s roadmap

The single source of truth for what is done and what is next. Every commit that
completes a line ticks its box here. Endpoint-level coverage is tracked
separately, in `docs/API_INVENTORY.md`.

Gate names match `PLAN.md` §7.

## Phase 0 — Bootstrap · **Gate G0**

- [x] Mill 1.1.7 pinned in `.mill-version`; bootstrap `./mill` committed (ADR-0006)
- [x] Seven modules under `object modules`: the five published ones — `domain`,
      `core`, `codec`, `transport`, `client` — plus `it` (integration suites)
      and `examples` (compiled by the build so a stale example breaks it)
- [x] `scalacOptions` with warnings fatal
- [x] Scalafmt + Scalafix configs, `mill modules.__.reformat` green
- [x] scoverage wired via `mill-contrib-scoverage`
- [x] ADRs 0001–0006, constitution mapping
- [x] Scalafix wired via `mill-scalafix` (Mill 1.x has no built-in `fix`; ADR-0006)
- [x] `verify.sh` with the ordered gate and an architecture-boundary check
- [x] CI pipeline — `.forgejo/workflows/ci.yml` runs the `verify` job on push
      and pull request, and `.github/workflows/` carries the same gate plus
      `nightly.yml`, which holds the slow analysis and the spec-drift detector
      behind a real `schedule:` trigger. Every action is pinned to a commit
      SHA rather than a tag, and Dependabot keeps those pins moving.

## Phase 1 — Recon and foundation · **Gate G-R**, **Gate G1**

- [x] Swagger spec vendored and checksummed (`spec/swagger.v1.json`, `docs/SPEC_PROVENANCE.md`)
- [x] `docs/API_INVENTORY.md` — 506 operations listed, in-scope marked
- [x] `docs/HAZARDS.md` — all six hazards verified against live probes; two of
      PLAN.md's assumptions turned out to be wrong
- [x] 54 golden fixtures captured (`modules/codec/test/resources/golden/`)
- [x] `domain`: error ADT, `CallContext`, opaque identifiers, `Auth`, config, paging value types
- [x] `core`: `Exec[F]`, ports, `RetryEngine`, `LinkHeader`, `Pages`,
      `ApiPipeline`, `StatusMapping`, `Redaction`
- [x] Vertical slice: `GET /version` and `GET /repos/{owner}/{repo}` through every layer, both rails
- [x] Full error paths on the slice: 404, decode failure, transport failure, retry-then-succeed

## Phase 2 — Cross-cutting hardening · **Gate G2**

- [x] Track A — retry engine, pagination driver, `Page` / `PageWalk`, `Telemetry` port
      wired through both `CodebergClient` factories
- [x] Track B — error ADT, Forgejo error-body parsing against captured samples,
      redaction guarantees, `CodebergException` bridging
- [x] Walking every page is reachable from the public API as
      `com.worxbend.codeberg4s.paging.PageWalk` (`all` / `fold` / `foreach`).
      One helper taking the listing operation as an argument, rather than a
      `listAll` on each of the thirty-eight API classes — the termination rule
      is the subtle part of pagination and belongs in one place.
- [ ] Property suites (`*Props.scala`, `Property` tag) for codec laws, pagination
      invariants, retry bounds. **Partially done:** `modules/domain` has
      `PropertyBase` (pinned ScalaCheck seed, `Property` tag), `IdentifierProps`
      (13 properties over the opaque identifiers and `BaseUri`), `SecretProps`
      (9 properties asserting no rendering path emits a credential),
      `CodebergErrorProps` and `PageProps`. Of the three areas `PLAN.md` §6.3
      names, two are covered: codec round-trip and totality laws by `JsonProps`
      and `ApiErrorBodyCodecProps`, retry bounds by `RetryEngineProps`. The
      pagination driver is the one still open — `PaginationProps` was deleted
      along with `core.Pagination`, and the walker that replaced it,
      `paging.PageWalk`, has example-based tests only.

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
- [x] `GET /repos/issues/search` — returns a bare array, not the `{ok, data}`
      envelope the other search endpoints use
- [x] **The long tail — all 378 remaining in-scope operations**, in three rounds:
      repository Actions, git data and publishing; issues, hooks, access control
      and administration; user account, user social, organizations and
      miscellaneous. The in-scope surface is now 439 of 439.

That is **439 of 439 in-scope operations, 100 %** (`docs/API_INVENTORY.md` §0).
Gate G3-final is met. The 67 operations not implemented are the ones `PLAN.md`
§0 puts out of scope for v1 — `admin`, `activitypub` and `package`.

Per-wave definition of done: models from golden fixtures · codec round-trips ·
both rails · Scaladoc stating the error contract · inventory checkbox flipped ·
coverage thresholds hold. (CRAP and CPD thresholds are part of this definition
on paper only until those tools are proven — `docs/CONSTITUTION_MAPPING.md`.)

## Phase 4 — Release engineering · **Gate G4**

- [ ] Stryker4s wired, ≥ 80 % mutation score on `domain` + `core` + `codec` —
      `scripts/mutate.sh` exists and the **runner is proven**: the 1.1.1 command
      runner generates mutants from these sources, scalameta parsed every one of
      the 196 production files those modules held at the time under the Scala 3
      dialect, the instrumented output recompiles under `-Werror`, and the break
      threshold demonstrably fails the run. Those modules hold 503 production
      files today, so even the parse half of that proof predates the current
      sources. **No score exists for this repository** — that needs a run with
      the real test command, roughly 1400 `mill test` invocations. The ≥ 80 %
      figure is still a target and is still **unproven**.
      `docs/CONSTITUTION_MAPPING.md` has the detail.
- [x] PMD CPD wired, fails above 40 duplicated tokens in production sources —
      **wired, verified, and green.** PMD 7.26.0's scalameta Scala module
      tokenises Scala 3 here without a lexical error, and `scripts/cpd.sh
      --report` finds **363 duplication groups** at 40+ tokens. Ticked because
      the tool runs and the gate holds, not because the code is
      duplication-free: `verify.sh` compares that count against
      `CPD_BASELINE_GROUPS`, so it fails on an increase and tells you to bank a
      decrease. `CPD_MIN_TOKENS` is still 40 and every group is still reported.
      Paying the debt down is `docs/LEDGER.md` §"Helpers awaiting promotion".
- [x] `scripts/crap.sc`, fails on any method with CRAP > 30 — implemented over
      the scoverage XML, and **run against a fresh report**: 2,217 methods
      measured, worst 28.0, limit 30. Note the complexity input is a documented
      proxy (`branch="true"` statement count), not a control-flow analysis; the
      script's header lists the three directions it is known to be wrong in.
      Read it as a ranking, not a certified metric.
- [x] `modules/it` — Testcontainers-Forgejo suite (`ForgejoContainerSuite`) plus
      the live-Codeberg smoke suite behind `CODEBERG_IT=1` (`CodebergLiveSmokeSuite`),
      both tagged `Integration` and excluded from `verify.sh`
- [x] `scripts/coverage-gate.sc` — reads scoverage's own `statement-rate` and
      `branch-rate`, floors at 90/85 for `domain`+`core`+`codec` and 80/80 for
      `transport`+`client`, and treats a missing report as a failure rather than
      a skip. Called by `verify.sh`, and **asserted against a freshly generated
      report**: `domain` 100.00 % statement / 100.00 % branch, `core` 96.59 % /
      92.48 %, `codec` 95.20 % / 91.47 %.
- [ ] README with compiling examples, `CHANGELOG.md` — `CHANGELOG.md` written
      and corrected against the current sources; README rewritten and every
      Scala block compiled against those sources under the project's flags.
      Unticked because that check is manual: `PLAN.md` §"Phase 4" asks for mdoc
      so the *build* enforces it.
- [ ] Maven Central publishing config, MIMA baseline from 0.1.0 — publishing is
      **configured**: `build.mill` publishes five artifacts
      (`codeberg4s-domain`, `-core`, `-codec`, `-transport`, `-client`) under
      `com.worxbend` at `0.1.0-SNAPSHOT`, MIT, `EarlySemVer`, with `modules.it`
      deliberately excluded. **MIMA is now wired** through
      `com.github.lolgab::mill-mima::0.2.2`, mixed into
      `Codeberg4sPublishModule` so one declaration covers all five artifacts.
      What remains is the publication itself: `binaryCompatibleWith` is
      `Seq.empty` because there is nothing on Central to compare against, so
      `mimaReportBinaryIssues` reports nothing until 0.1.0 is released.
      `RELEASING.md` § "Binary compatibility is checked by MIMA" carries the
      measured detail of what MIMA does and does not see through the response
      models' `private[codeberg4s]` constructors.
- [x] Nightly spec-drift detector — it lives in `.github/workflows/nightly.yml`
      behind a real `schedule:` trigger (03:00 UTC) plus `workflow_dispatch`,
      alongside the `verify.sh --nightly` job. It was previously declared in
      `.forgejo/workflows/ci.yml` gated on a `schedule` event that workflow
      never emitted, so it had never run. It compares the sha256 of the live
      `swagger.v1.json` against the pinned copy and, on a mismatch, emits a
      warning and a truncated diff in the job summary. It still does **not**
      open an issue, as `PLAN.md` §7 asks — that is the piece left.

## Distance to 0.1.0

`PLAN.md` §10 defines done. Measured against it, honestly:

| Definition-of-done clause | State |
| ------------------------- | ----- |
| All in-scope endpoints on both rails with documented error contracts | **439 / 439, 100 %** (`docs/API_INVENTORY.md` §0) — every one on both rails with a Scaladoc error contract |
| `verify.sh` green | **Yes, both modes.** Default run: format, lint, zero-warning compile, **3,658 unit tests**, boundary check, coverage. `--with-slow` adds duplication and CRAP and also passes |
| Coverage per §6.1 (≥ 90 % line / ≥ 85 % branch on `domain`+`core`+`codec`) | **Enforced and asserted against a fresh report** by `scripts/coverage-gate.sc`: `domain` 100.00 % / 100.00 %, `core` 96.59 % / 92.48 %, `codec` 95.20 % / 91.47 % |
| Mutation ≥ 80 % | Runner proven, **no score produced — the figure is unproven** — see Phase 4 |
| Zero CRAP > 30 | **Yes:** 2,217 methods measured, worst 28.0. Its complexity input is a documented proxy, so read it as a ranking |
| CPD clean | **No — 363 duplication groups at 40 tokens.** The gate passes because it fails on an increase over that recorded number, not because the duplication is gone. A real finding about the code; `docs/LEDGER.md` names most of them |
| Acceptance features + Gherkin mutation clean | Dormant by decision — `docs/CONSTITUTION_MAPPING.md` |
| Published to Maven Central | Configured and MIMA wired, but nothing published, so there is still no baseline to compare against |
| README quickstart works against live codeberg.org | Samples are checked against the source signatures by hand. `CodebergLiveSmokeSuite` exercises the same calls against codeberg.org under `CODEBERG_IT=1`, but nobody has run the README itself |
| Mapping doc, ADRs, inventory, provenance current | Yes, as of this revision |

The endpoint surface is done. What is left is evidence, not code. Two clauses
are genuinely unmet — the mutation score does not exist, and the codebase
carries 363 duplication groups that the gate records rather than forgives — and
two more are met only by hand: the README's examples are checked by a person
rather than by mdoc, and nothing has been published, so MIMA has nothing to
compare 0.1.1 against until the 0.1.0 tag is on Central.

## Out of scope for 0.1.0

Per `PLAN.md` §0: OAuth2 token *acquisition* flows (pre-obtained tokens only),
ActivityPub federation, admin endpoints, attachment streaming above 50 MB, and
Scala.js / Native cross-builds. The Gherkin acceptance pipeline is dormant —
see `docs/CONSTITUTION_MAPPING.md`.

The 50 MB line is now enforced rather than merely written down:
`CodebergConfig.maxDownloadBodyBytes` defaults to 50 MiB and a body past it is
`TransportCause.ResponseTooLarge`, non-retryable. Textual responses have their
own, smaller bound at `maxResponseBodyBytes`.
