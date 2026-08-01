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
- [ ] `verify.sh` green on the skeleton
- [ ] CI pipeline

## Phase 1 — Recon and foundation · **Gate G-R**, **Gate G1**

- [ ] Swagger spec vendored and checksummed (`spec/swagger.v1.json`, `docs/SPEC_PROVENANCE.md`)
- [ ] `docs/API_INVENTORY.md` — every operation listed, in-scope marked
- [ ] `docs/HAZARDS.md` — spec hazards verified against live probes
- [ ] ≥ 30 golden fixtures captured (`modules/codec/test/resources/golden/`)
- [ ] `domain`: error ADT, `CallContext`, opaque identifiers, `Auth`, config, paging value types
- [ ] `core`: `Exec[F]`, ports, `RetryEngine`, `Pagination`, `StatusMapping`, `Redaction`
- [ ] Vertical slice: `GET /version` and `GET /repos/{owner}/{repo}` through every layer, both rails
- [ ] Full error paths on the slice: 404, decode failure, transport failure, retry-then-succeed

## Phase 2 — Cross-cutting hardening · **Gate G2**

- [ ] Track A — retry engine, pagination driver, `Page` / `listAll` / `foldPages`, `Telemetry` port
- [ ] Track B — error ADT completion, Forgejo error-body parsing against captured samples,
      redaction guarantees, `CodebergException` bridging
- [ ] Property suites (`*Props.scala`, `Property` tag) for codec laws, pagination invariants, retry bounds

## Phase 3 — Endpoint waves · **Gate G3** per wave

Priority order is value-weighted, per `PLAN.md` §7. Shared models are owned by
the first wave that needs them; later waves consume rather than redefine
(`docs/LEDGER.md`).

- [ ] Wave 1 — **users**: current user, lookup by name, keys, followers
- [ ] Wave 2 — **repos**: read, search, branches, tags, releases, topics, commits,
      contents (the union response — see `docs/HAZARDS.md`)
- [ ] Wave 3 — **issues**: list/read/create/edit, comments, labels, milestones
- [ ] Wave 4 — **pulls**: list/read/create/edit, merge, reviews, files
- [ ] Wave 5 — **orgs**: organisation, teams, membership
- [ ] Wave 6 — **notifications**: list, mark read, per-thread
- [ ] Wave 7 — **misc**: markdown render, server settings, search

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
