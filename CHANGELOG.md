# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog][kac], and this project adheres to
[Semantic Versioning][semver].

[kac]: https://keepachangelog.com/en/1.1.0/
[semver]: https://semver.org/spec/v2.0.0.html

## [Unreleased]

Nothing yet.

## [0.1.0] — unreleased

First release. `build.mill` publishes `0.1.0-SNAPSHOT` until the tag is cut;
this entry is the release note that tag will carry.

### Added

- **Public `Future` API.** `CodebergClient` exposes seven endpoint groups —
  `repos`, `users`, `issues`, `pulls`, `organizations`, `notifications` and
  `misc` — plus `version`, covering 61 REST operations against Codeberg,
  Forgejo or any Gitea-compatible instance. The base URI is configuration, not
  a constant.
- **Two error rails over one code path.** Every operation exists twice: the
  convenience rail fails the `Future` with `CodebergException`, and
  `.attempt` returns `Future[Either[CodebergError, A]]` and never fails. Both
  are projections of the same `Exec[F]` pipeline, so they cannot drift.
- **A closed error ADT with call context.** `CodebergError` has five cases —
  `Transport`, `Api`, `DecodingFailed`, `Validation`, `RetriesExhausted`. Every
  remote case carries a `CallContext` (a stable operation id, the HTTP method,
  the redacted URI, the server's `x-request-id`, the attempt duration), so a
  caller can tell *which* call failed without correlating logs. Forgejo's error
  payloads are parsed into `ApiErrorBody` against captured samples.
- **Link-header pagination.** List operations return `Page[A]` with the items,
  the total count and the next page parsed from the RFC 8288 `Link` header
  rather than guessed from a page counter. `core.Pagination` provides the
  sequential `listAll` and `foldPages` drivers, so walking every page is opt-in
  and never materialises the whole collection by accident.
- **Retry that honours the server.** `RetryEngine` retries `429` and `5xx` on
  idempotent methods only, with jittered exponential backoff, and prefers the
  server's `Retry-After` over its own schedule when the policy allows it.
  `POST`, `PATCH` and `DELETE` are never retried automatically.
- **Tokens are redacted everywhere.** `ApiToken` renders as `***` in `toString`
  and in string interpolation, and no `CodebergError` — including the URI
  captured in `CallContext` — can carry one. There are tests that assert it.
- **A `Telemetry` port** for request/response visibility. The library has no
  logging dependency and writes nothing to stdout.
- **Hexagonal module layout,** published as five artifacts under
  `com.worxbend`: `codeberg4s-domain` (no dependencies at all),
  `codeberg4s-core`, `codeberg4s-codec` (upickle), `codeberg4s-transport`
  (sttp client4) and `codeberg4s-client`. Naming `codeberg4s-client` pulls in
  the other four transitively.
- **Verification.** 1,072 unit tests, 54 golden fixtures captured from the live
  API, scoverage thresholds, and a `verify.sh` gate that also enforces the
  architecture boundaries (no sttp, upickle or `Future` below `client`; no
  `Await`; no bare exceptions).

### Known limitations

- Endpoint coverage is 61 of 439 in-scope operations. The rest is the long tail
  of `repository` (actions, hooks, deploy keys, wikis, attachments) and `user`
  (settings, stars, blocks, GPG keys, tokens), plus most write operations
  outside issues, pulls and labels. See `docs/API_INVENTORY.md`.
- `listAll` and `foldPages` live on `core.Pagination`; they are not yet surfaced
  as convenience methods on the client resource groups.
- `GET /repos/issues/search` is deferred — it returns a bare array rather than
  the `{ok, data}` envelope the other search endpoints use.
- ScalaCheck property suites carry the `Property` tag and are excluded from the
  default gate; `docs/ROADMAP.md` tracks how much of the planned surface they
  reach. Runners exist for mutation testing (Stryker4s), duplication (PMD CPD)
  and CRAP, but `docs/CONSTITUTION_MAPPING.md` is the authority on which of
  them are actually proven against Mill and Scala 3.
- No binary-compatibility baseline. 0.1.0 is that baseline; MIMA gets wired
  against it for 0.1.1.
- Out of scope by design: OAuth2 token acquisition, ActivityPub federation,
  admin endpoints, attachment streaming above 50 MB, and Scala.js / Native.

[Unreleased]: https://codeberg.org/worxbend/codeberg4s/compare/v0.1.0...main
[0.1.0]: https://codeberg.org/worxbend/codeberg4s/releases/tag/v0.1.0
