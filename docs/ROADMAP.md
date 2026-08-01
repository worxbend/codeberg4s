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
- [x] CI pipeline (`.forgejo/workflows/ci.yml`), including a spec-drift job

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
- [ ] `listAll` convenience on the paged resource groups
- [ ] Property suites (`*Props.scala`, `Property` tag) for codec laws, pagination invariants, retry bounds

## Phase 3 — Endpoint waves · **Gate G3** per wave

Priority order is value-weighted, per `PLAN.md` §7. Shared models are owned by
the first wave that needs them; later waves consume rather than redefine
(`docs/LEDGER.md`).

- [x] Wave 1 — **users** (8 operations): current user, lookup by name, search,
      repositories, followers, following, keys
- [x] Wave 2 — **repos** (10 operations): read, search, branches, tags, releases,
      topics, commits, forks, contents (the union response — see `docs/HAZARDS.md`)
- [x] Wave 3 — **issues** (10 operations): list/read/create/edit, comments, labels, milestones
- [ ] Wave 4 — **pulls**: list/read/create/edit, merge, reviews, commits, files
- [ ] Wave 5 — **orgs**: organisation, teams, membership
- [ ] Wave 6 — **notifications**: list, mark read, per-thread
- [x] Wave 7 — **misc** (6 operations): markdown render, server settings, signing key
- [ ] `GET /repos/issues/search` — deferred out of wave 7 because it needs wave 3's
      `IssueDto`, which did not exist when that lane ran. Note it returns a bare
      array, not the `{ok, data}` envelope the other search endpoints use.

Per-wave definition of done: models from golden fixtures · codec round-trips ·
both rails · Scaladoc stating the error contract · inventory checkbox flipped ·
coverage / CRAP / CPD thresholds hold.

## Phase 4 — Release engineering · **Gate G4**

- [ ] Stryker4s wired, ≥ 80 % mutation score on `domain` + `core` + `codec`
- [ ] PMD CPD wired, fails above 40 duplicated tokens in production sources
- [ ] `scripts/crap.sc`, fails on any method with CRAP > 30
- [ ] `modules/it` — Testcontainers-Forgejo suite; live-Codeberg smoke behind `CODEBERG_IT=1`
- [ ] README with compiling examples, `CHANGELOG.md`
- [ ] Maven Central publishing config, MIMA baseline from 0.1.0
- [ ] Nightly spec-drift detector

## Out of scope for 0.1.0

Per `PLAN.md` §0: OAuth2 token *acquisition* flows (pre-obtained tokens only),
ActivityPub federation, admin endpoints, attachment streaming above 50 MB, and
Scala.js / Native cross-builds. The Gherkin acceptance pipeline is dormant —
see `docs/CONSTITUTION_MAPPING.md`.
