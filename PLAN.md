# codeberg4s — Agent Implementation Plan

Ground-up Scala 3 HTTP client library for the Codeberg (Forgejo) REST API v1. Future-based public API, sttp client4 transport, SoftwareMill ecosystem utilities, hexagonal architecture, full swarm-forge constitution compliance.

---

## Status of this document — read first

This is the **plan of record as written before implementation began**, kept
verbatim so that the twenty-odd files across this repository that cite it by
section number (`PLAN.md §3.1`, `PLAN.md ADR-3`, `PLAN.md §6.6`) point at
something a reader can actually open. It is a historical document, not a
description of the code as it stands.

Where the code and this plan disagree, an ADR in [`docs/adr/`](docs/adr/) says
which won and why, and the ADR is authoritative. The divergences that matter:

| This plan says | What shipped | Recorded in |
| --- | --- | --- |
| upickle for JSON (§3.3 ADR-3, §3.1 `codec`) | jsoniter-scala, with a hand-written codec over a document model rather than derived per-DTO codecs | [ADR-0003](docs/adr/0003-jsoniter-for-json.md) |
| package prefix `codeberg4s.*` (§3) | `com.worxbend.codeberg4s.*` | [ADR-0005](docs/adr/0005-future-public-api.md) |
| module `sttp-transport` (§3) | module `transport`, under the Mill `modules/<name>/src` layout | [docs/VERSIONS.md §2](docs/VERSIONS.md) |
| softwaremill/retry evaluated first (§3.3 ADR-4) | `RetryPolicy` implemented in `core`, no new dependency | [ADR-0004](docs/adr/0004-retry-implemented-in-core.md) |
| Gherkin acceptance pipeline (§2, §6.2), `acceptance-runner` module | not built; no Gherkin tooling exists in this repository | [CLAUDE.md](CLAUDE.md) § Acceptance tests, [docs/CONSTITUTION_MAPPING.md](docs/CONSTITUTION_MAPPING.md) |
| quicklens as a candidate utility (§0) | not a dependency | [docs/VERSIONS.md §6](docs/VERSIONS.md) |

For what is actually built and what is left, read
[`docs/ROADMAP.md`](docs/ROADMAP.md). For the pinned versions, read
[`docs/VERSIONS.md`](docs/VERSIONS.md). For how the constitution's rules were
translated to this toolchain, read
[`docs/CONSTITUTION_MAPPING.md`](docs/CONSTITUTION_MAPPING.md).

---

## 0. Mission Statement

Build **codeberg4s**: a publishable, well-tested, hexagonally structured Scala 3 client library for the Codeberg API (`https://codeberg.org/api/v1`), which is the Forgejo v1 API. The library must:

- Expose a **convenient `Future`-based interface** — no effect-system dependency leaks into the public API.
- Use **sttp client4** as the transport layer and other SoftwareMill utilities where they fit (sttp-model, quicklens, softwaremill/retry).
- Work against **any Forgejo/Gitea-compatible instance** via configurable base URL (Codeberg is the default, not a hardcode).
- Follow clean code, clean architecture, parse-don't-validate, rich error context propagation, and the swarm-forge engineering constitution (adapted for Scala — see §2).

Non-goals (v1): OAuth2 token *acquisition* flows (accept pre-obtained tokens only), ActivityPub federation endpoints, admin endpoints, streaming attachments over 50 MB, Scala.js / Native cross-builds (design for it, don't ship it).

---

## 1. Research Phase (Agent Task R1 — must complete before any code)

### R1.1 Spec acquisition
1. Download the spec: `https://codeberg.org/swagger.v1.json` (Swagger 2.0). Mirror fallback: `https://code.forgejo.org/swagger.v1.json`.
2. Record the Forgejo version it corresponds to (`GET /api/v1/version`) in `docs/SPEC_PROVENANCE.md`. Vendor the spec file into `spec/swagger.v1.json` with a checksum — all model work references this pinned copy, never a live fetch.
3. Convert to OpenAPI 3 for tooling convenience if needed (`converter.swagger.io`), but treat the Swagger 2.0 original as source of truth.

### R1.2 Spec inventory
Produce `docs/API_INVENTORY.md`:
- Full endpoint list grouped by tag (repository, issue, user, organization, notification, package, miscellaneous, settings, activitypub, admin).
- For each group: endpoint count, auth requirements, pagination behavior, known spec quirks.
- Mark each group **in-scope-v1 / deferred / out-of-scope** per §0.

### R1.3 Known spec hazards to verify and document
These are documented pain points from other Forgejo client generators — verify each against the pinned spec:
- **Multiple security definitions** (BasicAuth, Token, AccessToken, AuthorizationHeaderToken, SudoParam, TOTPHeader). We support: `AuthorizationHeaderToken` (`Authorization: token <t>`), Basic auth, and anonymous. Document the rest as unsupported.
- **Endpoints with union response types** — e.g. `/repos/{owner}/{repo}/contents/{filepath}` returns either a file object or a list. Model these as explicit Scala 3 union/enum ADTs, never `ujson.Value` passthrough.
- **Optionality lies**: Swagger 2.0 has no `nullable`; fields marked required may be absent in practice and vice versa. Every model must be validated against **live golden responses** (R1.4), not just the spec.
- **Pagination contract**: `page`/`limit` query params, `x-total-count` response header, RFC 5988 `Link` header. Verify which endpoints honor it.
- **Rate limiting**: capture actual headers Codeberg returns (Forgejo does not emit GitHub-style `X-RateLimit-*` on all deployments — verify empirically).
- **Error body shape**: Forgejo errors are typically `{"message": ..., "url": ...}` with occasional `errors: [...]`. Capture real samples for 401/403/404/409/422.

### R1.4 Golden fixture harvest
Using anonymous read-only calls against codeberg.org (respect rate limits; a scratch account + token for authed shapes if available):
- Capture real JSON responses for every in-scope model into `modules/codec/test/resources/golden/<endpoint>/<case>.json`.
- These fixtures drive codec round-trip tests and are the ground truth over the spec.

### R1.5 Ecosystem version resolution
Resolve **latest stable** versions at build start (constitution: no stale caches): Scala 3 LTS line, Mill, sttp client4, upickle, softwaremill/retry, munit, scalacheck, Stryker4s, scoverage, scalafmt, scalafix. Record in `docs/VERSIONS.md`.

**Gate G-R:** API_INVENTORY.md + SPEC_PROVENANCE.md + at least 30 golden fixtures + hazard verification notes exist and are committed. No production code before this gate.

---

## 2. Constitution Compliance (swarm-forge `engineering.prompt` → Scala)

The constitution's startup tool table covers only Go, Clojure, and Java. Scala is unlisted, so we adopt the closest faithful equivalents and record the mapping in `docs/CONSTITUTION_MAPPING.md`:

| Constitution requirement | Scala adaptation |
|---|---|
| Mutation tool (`mutate4*`) | **Stryker4s** (Mill via command-runner or sbt-shim module if needed; verify current Mill support at bootstrap, fall back to running Stryker4s CLI) |
| CRAP tool (`crap4*`) | **scoverage per-method coverage × cyclomatic complexity** via a small project-local `crap4scala` script (`scripts/crap.sc`, scala-cli) computing CRAP = comp² × (1 − cov)³ + comp from scoverage XML + scalameta complexity walk |
| DRY tool (`dry4*`) | **PMD CPD** with Scala language support; threshold config committed |
| Acceptance Pipeline (APS) | **Use as-is** — `gherkin-parser` / `gherkin-mutator` from `github.com/unclebob/Acceptance-Pipeline-Specification`, **Babashka variants preferred**, Go variants only as fallback. Install fresh from upstream at startup, never vendored/stale |
| Speclj / Clojure defaults | N/A (Clojure-only rules) |
| "Avoid Maven for Java tests; dedicated runners" | Analog: acceptance tests get a **dedicated runner module** (`modules/acceptance-runner`), not run through the general `mill __.test` sweep |

Constitution rules adopted verbatim:
- **Small, reviewable increments** — every task in §7 must land as an independently green, conventional-commit PR ≤ ~400 changed lines.
- **Testable vs environmentally-unsuitable module separation** (§3 architecture enforces this): anything touching the live network (integration tests against a real Forgejo) lives in `modules/it` and is **excluded** from unit coverage, mutation, CRAP, and DRY-with-tests runs.
- **Property tests separated**: ScalaCheck suites live under a `Property` munit tag in dedicated `*Props.scala` files; excluded from normal unit coverage/mutation/CRAP unless explicitly requested.
- **Acceptance generation and acceptance tests run sequentially**, never concurrently with the whole-suite unit test command.
- **Gherkin mutation runs must emit periodic progress output** (wrap `gherkin-mutator` invocation in a script printing heartbeats).
- **Project-local caches**: `COURSIER_CACHE`, Mill `out/`, and tool caches pinned inside the worktree (`.cache/`); CI and agent sandboxes must not write outside the project.
- **Never hand-edit mutation/acceptance-mutation manifests**; only the tools update them.
- **Inspect `--help`/docs before relying on any unfamiliar command.**
- **Run local verification (`./verify.sh`, §6.6) before every handoff.**

**Gate G-C:** All tools install fresh from upstream and run green on the empty skeleton before Phase 1 begins.

---

## 3. Architecture

Hexagonal / ports-and-adapters, enforced at Mill module boundaries. Domain never imports sttp, upickle, or `scala.concurrent`.

```
codeberg4s/
├── build.mill
├── spec/swagger.v1.json                  # pinned, checksummed
├── modules/
│   ├── domain/          # pure: models, error ADT, pagination types, ids
│   │   └── src/codeberg4s/domain/...
│   ├── core/            # ports + use-case logic, tagless over F[_] internally
│   │   └── src/codeberg4s/core/...
│   ├── codec/           # upickle ReadWriters, isolated from domain
│   ├── sttp-transport/  # adapter: sttp client4 request building/execution
│   ├── client/          # public Future façade — THE published artifact surface
│   ├── acceptance-runner/  # dedicated APS runtime + step handlers (constitution)
│   └── it/              # live-network integration tests (unsuitable boundary)
├── scripts/             # crap.sc, cpd.sh, gherkin-mutate.sh (heartbeat wrapper)
├── acceptance/          # .feature files + generated entrypoints
└── docs/                # inventory, provenance, versions, ADRs, ROADMAP.md
```

### 3.1 Module rules (enforced; adversarial reviewer checks every PR)
- `domain`: zero dependencies beyond stdlib. Opaque types for identifiers (`RepoName`, `Owner`, `IssueNumber`, `Sha`, `Token`), enums for states (`IssueState`, `MergeStyle`, …). **Parse, don't validate**: smart constructors return `Either[ValidationError, A]`; no `String`-typed domain fields where a refined type is meaningful.
- `core`: defines ports as traits over an abstract `F[_]` with a minimal internal capability typeclass (`Exec[F]` — pure/flatMap/raise/attempt; hand-rolled ~40 lines, **no cats dependency** to keep the published dependency footprint tiny). Contains cross-cutting logic: pagination driving, retry orchestration, error mapping. All unit-testable with `F = Either[CodebergError, *]` — synchronous, deterministic, mutation-testable.
- `codec`: upickle `ReadWriter`s only (sttp's upickle integration is the JSON path; fits the lihaoyi-stack preference and keeps transitive deps minimal). Golden-fixture round-trip tested. Codec failures never throw raw `upickle.core.Abort` outward — they map to `CodebergError.DecodingFailed` with context (§4).
- `sttp-transport`: the only module importing sttp. Implements the `HttpPort` from core: builds `Request`, executes on an injected `Backend[Future]`, translates responses to core's transport-level result type. `HttpClientFutureBackend` is the default, but the backend is constructor-injected → `SttpBackendStub` in tests, and users can bring OkHttp/Pekko backends.
- `client`: the public API. Instantiates core logic with `F = Future`. This is what users see; everything else is implementation detail (package-private where Mill allows, documented as internal otherwise).

### 3.2 Public API shape

```scala
val client: CodebergClient = CodebergClient(
  CodebergConfig(
    baseUri  = uri"https://codeberg.org/api/v1",   // default
    auth     = Auth.Token(sys.env("CODEBERG_TOKEN")),
    retry    = RetryPolicy.default,                // backoff on 429/502/503/504
    userAgent = "codeberg4s/x.y.z",
  )
)(using ExecutionContext, backend: Backend[Future] = HttpClientFutureBackend())

// Resource-grouped, mirroring API tags:
client.repos.get(Owner("forgejo"), RepoName("forgejo")): Future[Repository]
client.issues.list(owner, repo, IssueQuery(state = IssueState.Open)): Future[Page[Issue]]
client.pulls.merge(owner, repo, PrNumber(42), MergeStyle.Squash): Future[Unit]
client.users.current(): Future[User]
```

Dual-rail error contract:
- **Convenience rail** (shown above): `Future[A]`, failing with `CodebergException` (carries the full `CodebergError` ADT — idiomatic for Future users).
- **Typed rail**: every op also available as `client.repos.attempt.get(...): Future[Either[CodebergError, Repository]]` for callers who refuse exceptions. Implemented once in core; the two rails are mechanical projections, not duplicated logic.

Pagination conveniences: `Page[A]` carries items + `totalCount` (from `x-total-count`) + next-page handle; `client.issues.listAll(...): Future[Vector[Issue]]` drives pages sequentially with the retry policy applied per page, plus `foldPages` for bounded-memory processing.

### 3.3 Key trade-offs (record as ADRs in `docs/adr/`)
1. **Hand-written curated models over codegen.** The Swagger 2.0 spec lies about optionality and has union types codegen handles badly (§1.3). Curated models + golden fixtures give correctness and clean-code naming; the cost (manual endpoint coverage) is mitigated by the wave plan (§7) and spec-diff checks in CI. ADR must document the rejected alternative (guardrail/openapi-generator/sttp-openapi).
2. **Hand-rolled `Exec[F]` over cats-effect/ZIO.** Future-first public API + minimal transitive deps for a publishable library (same reasoning that favored zio-config ergonomics in gitea4s — dependency footprint matters for libraries). Cost: ~40 lines of well-tested boilerplate.
3. **upickle over circe/jsoniter.** Aligns with lihaoyi-stack preference, tiny footprint, first-class sttp integration. Cost: fewer derivation knobs — mitigated by explicit `ReadWriter`s in codec (which we want anyway for golden-fixture discipline).
4. **softwaremill/retry (Future-native) evaluated first** for the retry port implementation; if its odelay dependency or maintenance status disqualifies it at version-resolution time, implement `RetryPolicy` in core (jittered exponential backoff, `Retry-After` header respected) — the port makes this swappable without API change.

---

## 4. Error Context Propagation (design contract, not an afterthought)

```scala
enum CodebergError:
  case Transport(ctx: CallContext, cause: TransportCause)          // DNS, TLS, timeout, connection
  case Api(ctx: CallContext, status: StatusCode, body: ApiErrorBody) // 4xx/5xx with parsed Forgejo message
  case DecodingFailed(ctx: CallContext, snippet: String, path: JsonPath, cause: String)
  case Validation(field: String, message: String)                  // pre-flight, smart constructors
  case RetriesExhausted(ctx: CallContext, attempts: Int, last: CodebergError)

final case class CallContext(
  operation: String,          // "repos.get" — stable, greppable
  method: Method, uri: Uri,   // uri with token REDACTED
  requestId: Option[String],  // X-Request-Id if present
  durationMs: Long,
)
```

Rules the reviewer enforces:
- Every failure path attaches `CallContext`. No bare `new Exception(msg)` anywhere in the codebase (scalafix custom rule or grep-based CI check).
- Decoding failures include a **bounded** body snippet (≤ 512 chars) and JSON path — enough to debug, no unbounded payloads in logs.
- Secrets never appear in errors, `toString`, or logs: `Token` is an opaque type with a redacting `toString`; property test asserts no configured token substring ever occurs in any rendered error (this one earns its place as a normal unit test, not just a property tag).
- `RetriesExhausted` preserves the last underlying error — no context loss through the retry loop.
- Public scaladoc on every operation documents which errors it can produce and when.

---

## 5. Cross-Cutting Behaviors

- **Auth**: `Auth.Anonymous | Auth.Token | Auth.Basic`. Applied in transport as a request transformation; core is auth-agnostic.
- **Retry**: policy on idempotent methods (GET/HEAD) for 429/5xx + transport failures; POST/PATCH/DELETE never auto-retried unless the caller opts in per-call. `Retry-After` honored when present.
- **Pagination**: as §3.2; `x-total-count` parsed defensively (absent header ≠ error).
- **Sudo / conditional requests / ETag**: out of scope v1, but `RequestCustomizer` hook (`Request => Request`) in config keeps the escape hatch open.
- **Logging**: no logging dependency in the library. A `Telemetry` port (callback trait: `onRequest/onResponse/onError`, no-op default) lets applications wire their own — keeps the published artifact silent and dependency-free.

---

## 6. Testing & Verification Strategy

### 6.1 Unit tests (munit)
- `core` logic tested with `F = Either` — fully synchronous, no `Future.await` flakiness, mutation-testing friendly.
- `sttp-transport` tested with `SttpBackendStub` (request-shape assertions: path, query encoding, headers, auth redaction).
- `codec` tested by golden-fixture round-trips (decode → encode → decode == identity where the API contract allows; decode-only otherwise).
- Coverage target: ≥ 90% line / ≥ 85% branch on `domain`+`core`+`codec`; transport measured but gated at ≥ 80% (stub-reachable paths).

### 6.2 Acceptance tests (constitution APS pipeline)
- Gherkin `.feature` files per resource group in `acceptance/` (e.g. `issues.feature`: "listing open issues returns the first page with total count").
- `gherkin-parser` + `gherkin-mutator` installed fresh from `unclebob/Acceptance-Pipeline-Specification` (Babashka preferred, Go fallback).
- Project components we own: acceptance entrypoint generator (scala-cli script emitting munit suites from parsed Gherkin), acceptance runtime, step handlers (backed by `SttpBackendStub` with golden fixtures — **testable module**, no live network), runner adapter, convenience scripts.
- Acceptance runs are **sequential** and via the **dedicated runner module**, never mixed into `mill __.test`.
- `scripts/gherkin-mutate.sh` wraps mutator runs with heartbeat output every 15s.

### 6.3 Property tests (ScalaCheck, `Property` munit tag, `*Props.scala`)
- Codec laws over generated model instances; pagination driver invariants (no page fetched twice, ordering preserved); retry policy bounds (attempt count, monotone backoff, jitter within envelope). Excluded from normal coverage/mutation/CRAP runs per constitution.

### 6.4 Mutation, CRAP, DRY
- **Stryker4s** on `domain`, `core`, `codec`; threshold ≥ 80% mutation score, manifest tool-managed only.
- **`scripts/crap.sc`**: fails build on any method with CRAP > 30; report committed to CI artifacts.
- **CPD**: fails on duplicated blocks > 40 tokens across production sources (test fixtures exempt).

### 6.5 Integration tests (`modules/it` — the unsuitable boundary)
- **Testcontainers with a Forgejo image** (deterministic, seedable, CI-friendly) as primary target; optional live-Codeberg smoke suite behind `CODEBERG_IT=1` + token, read-only ops only, never in default CI.
- Excluded from coverage, mutation, CRAP, DRY-with-tests, and acceptance mutation.

### 6.6 `./verify.sh` (pre-handoff, constitution-mandated, in order)
1. `scalafmt --check` + scalafix check
2. `mill __.compile` (fatal warnings on)
3. Unit tests (excluding `Property` tag and `it`)
4. Coverage report + threshold check
5. Acceptance generation → acceptance tests (sequential)
6. CPD, CRAP script
7. (nightly/pre-release only) Stryker4s + gherkin-mutator

---

## 7. Phased Roadmap (ROADMAP.md-driven; each phase = milestone gate)

### Phase 0 — Bootstrap (sequential, one agent)
Repo skeleton, Mill build with all modules, CI (Forgejo Actions or Woodpecker — decide by where the repo lives; mirror to GitHub if tooling needs it), constitution tooling installed fresh (§2), empty-walking-skeleton `verify.sh` green, `.editorconfig`, scalafmt/scalafix configs, conventional-commit lint hook, ADR template.
**Gate G0** = G-C + CI green on skeleton.

### Phase 1 — Vertical slice (sequential — this de-risks everything)
One endpoint end-to-end: `GET /version` + `GET /repos/{owner}/{repo}` through all layers: domain model, codec + golden fixture, port, transport, Future façade, both rails, one acceptance feature, unit + property + IT coverage, full error paths (404, decode failure, timeout, retry-then-succeed).
**Gate G1**: vertical slice passes full `verify.sh` including mutation ≥ 80% on touched code; adversarial review sign-off on the architecture seams. **Everything after G1 is pattern replication.**

### Phase 2 — Cross-cutting hardening (2 parallel tracks)
- **Track A**: retry engine, pagination driver + `Page`/`listAll`/`foldPages`, Telemetry port.
- **Track B**: full error ADT, error-body parsing against captured samples, redaction guarantees, `CodebergException` bridging.
**Gate G2**: both tracks merged, property suites green, no CRAP regressions.

### Phase 3 — Endpoint waves (parallel tracks with beat rotation; each wave = one agent lane)
Priority order (value-weighted):
1. **users** (current user, keys, by-name lookups)
2. **repos** (CRUD, contents [union type!], branches, tags, releases, topics)
3. **issues** (CRUD, comments, labels, milestones)
4. **pulls** (CRUD, merge, reviews, diff/patch)
5. **orgs** (org, teams, membership)
6. **notifications**
7. **misc** (markdown render, settings, search)

Per-wave definition of done: models from golden fixtures, codec round-trips, both rails, acceptance feature(s), scaladoc with error contracts, inventory checkbox flipped, coverage/CRAP/CPD thresholds hold. Deduplication ledger (`docs/LEDGER.md`) tracks shared models (User appears in issues, pulls, repos — first wave to need it owns it; later waves consume).
**Gate G3** per wave; **Gate G3-final** when in-scope-v1 inventory is 100% checked.

### Phase 4 — Release engineering
- mdoc-checked README (every snippet compiles), scaladoc site, CHANGELOG (conventional-commit generated).
- Publishing: Maven Central via Sonatype Central portal, Mill publish setup, MIMA binary-compat baseline from 0.1.0 onward.
- Nightly job: spec drift detector (fetch live swagger, diff against pinned, open issue on divergence) + full mutation/acceptance-mutation run.
**Gate G4**: `0.1.0` published, README quickstart verified against live Codeberg by a human.

---

## 8. Agent Orchestration

- **Roles**: research agent (R1), implementer lanes (one per wave/track), **adversarial reviewer** (every PR: architecture-boundary violations, error-context gaps, constitution breaches, test-theater detection — asserts that tests would actually fail if the behavior broke), release agent (Phase 4).
- **Workflow**: ROADMAP.md is the single source of truth; agents claim tasks by PR referencing roadmap IDs; small increments (≤ ~400 lines); conventional commits; no unrelated changes or generated artifacts committed (constitution guardrail).
- **Reviewer veto list** (auto-reject): sttp/upickle import in domain or core; `Await.result` in production code; bare exceptions; unredacted token in any string rendering; new endpoint without golden fixture; acceptance manifest hand-edits; coverage/mutation threshold lowered without ADR.
- **Handoff protocol**: `verify.sh` green + roadmap checkbox + ledger update, or the handoff is invalid.

---

## 9. Risk Register

| Risk | Mitigation |
|---|---|
| Spec optionality lies → runtime decode failures | Golden fixtures as ground truth; `DecodingFailed` carries path+snippet; nightly drift detector |
| Forgejo API changes between Codeberg deploys | Pinned spec + provenance doc; drift detector opens issues, doesn't auto-break |
| Stryker4s/Mill integration friction | Verify at G0; CLI fallback documented; worst case run via thin sbt shim confined to CI |
| `Future` eagerness makes retry/pagination subtle | All logic in core over `Exec[F]`, tested synchronously with `Either`; Future is only the outermost projection |
| softwaremill/retry maintenance status | Port-based design; drop-in internal implementation ready (ADR-4) |
| APS Babashka tools fail in sandbox | Constitution-sanctioned Go fallback; verified at G-C, not discovered mid-flight |
| Rate limits during fixture harvest / IT | Testcontainers-Forgejo as primary IT target; live smoke opt-in only |

---

## 10. Definition of Done (v1 / 0.1.0)

- All in-scope inventory endpoints implemented on both rails with documented error contracts.
- `verify.sh` green; mutation ≥ 80%, coverage per §6.1, zero CRAP > 30, CPD clean.
- Acceptance features + Gherkin mutation run clean.
- Published to Maven Central; README quickstart works against live codeberg.org.
- Constitution mapping doc, ADRs 1–4, API inventory, and spec provenance all current.
