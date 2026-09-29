# Spec Provenance

The Codeberg / Forgejo OpenAPI (Swagger 2.0) document vendored at
[`spec/swagger.v1.json`](../spec/swagger.v1.json) is the **single pinned source
of truth** for every model, endpoint, and codec in this repository.

> **Rule (PLAN.md §1.1):** all model work references this pinned copy. No build
> step, test, or code generator may fetch the spec from the network. Re-pinning
> is a deliberate, reviewed change that updates this file and regenerates
> [`API_INVENTORY.md`](API_INVENTORY.md).

## 1. Acquisition

| Field | Value |
| --- | --- |
| Source URL | `https://codeberg.org/swagger.v1.json` |
| Fallback mirror (not used) | `https://code.forgejo.org/swagger.v1.json` |
| Command | `curl -sL https://codeberg.org/swagger.v1.json -o spec/swagger.v1.json` |
| HTTP status | `200` |
| Fetch date (`date -I`, local) | `2026-09-29` |
| Fetch instant (UTC, server `Date` header) | `2026-09-29T19:14:42Z` |
| Serving node (`x-server-name`) | `s_forgejo_secondary_alpspitz` |

## 2. Integrity

| Field | Value |
| --- | --- |
| Path | `spec/swagger.v1.json` |
| Bytes | `853956` |
| sha256 | `253d09802be4a97244170c8c68afce22d1ce2e2a7ff6fc3ede2cd0ec4b9eafa0` |
| Validity | `python3 -m json.tool spec/swagger.v1.json` — parses clean |

Verify at any time:

```bash
sha256sum spec/swagger.v1.json
# 253d09802be4a97244170c8c68afce22d1ce2e2a7ff6fc3ede2cd0ec4b9eafa0  spec/swagger.v1.json
```

## 3. Version correspondence

| Field | Value |
| --- | --- |
| Spec `info.version` | `16.0.0-dev-753-6bcc6da0+gitea-1.22.0` |
| Spec `info.title` | `Forgejo API` |
| Live `GET https://codeberg.org/api/v1/version` | `{"version":"16.0.0-dev-753-6bcc6da0+gitea-1.22.0"}` |
| Match? | **Yes** — the pinned spec is exactly the document the live instance serves |
| Swagger version | `2.0` |
| `basePath` | `/api/v1` |
| `host` | *absent* — the spec declares no host, so the base URI is entirely a client concern (consistent with PLAN.md's configurable `baseUri`) |
| `schemes` | *absent* |
| `consumes` | `application/json`, `text/plain` |
| `produces` | `application/json`, `text/html` |

The version string decomposes as:

- `16.0.0-dev` — Forgejo 16.0.0 development line.
- `-753-6bcc6da0` — 753 commits past the tag, at commit `6bcc6da0`.
- `+gitea-1.22.0` — the Gitea API compatibility level Forgejo advertises.

Codeberg tracks a Forgejo development branch, so this string moves between
deploys. The nightly spec-drift detector (PLAN.md §7 Phase 4) compares the live
document against this pinned copy and opens an issue on divergence rather than
breaking the build.

## 4. Spec shape

| Metric | Count |
| --- | ---: |
| `paths` | 326 |
| Operations (`get`/`post`/`put`/`patch`/`delete`) | 506 |
| `definitions` | 246 |
| `responses` (reusable) | 174 |
| `securityDefinitions` | 5 |
| Global `parameters` | 0 |

Every operation carries at least one `tags` entry; there are no untagged
operations. Exactly one operation is dual-tagged: `POST /user/repos`
(`createCurrentUserRepo`, tagged `repository` and `user`).

## 5. OpenAPI 3 conversion

Not performed. PLAN.md §1.3 permits conversion via `converter.swagger.io` for
tooling convenience, but the project hand-writes its models (ADR-1) and has no
code generator that needs OpenAPI 3. Converting would introduce a second
artifact to keep in sync with no consumer. If a converted copy is ever added it
must live beside the original as a *derived* file and must never become the
source of truth.

## 6. Known divergences from reality

The spec is not a faithful description of the running instance. Verified
divergences — union responses the spec flattens, error bodies with undeclared
fields, and pagination behaviour the spec does not describe — are catalogued
with live evidence in [`HAZARDS.md`](HAZARDS.md). Read that file before trusting
any schema in this document.

## 7. Re-pin history

Every re-pin is recorded here with what it changed, so a reader can tell a
cosmetic bump from one that moved the API surface.

| Date | `info.version` | sha256 | What changed |
| --- | --- | --- | --- |
| 2026-08-01 | `16.0.0-dev-668-1bdb1938+gitea-1.22.0` | `90c40aa5…fddaf9d5` | Initial pin. |
| 2026-09-29 | `16.0.0-dev-753-6bcc6da0+gitea-1.22.0` | `253d0980…9eafa0` | Structural diff against the previous pin: **0 operations added, removed or changed**; 0 definitions added or removed; one definition changed — `IssueMeta` gained `required: [index, owner, repo]`, which [`IssueRef`](../modules/domain/src/com/worxbend/codeberg4s/issues/IssueRef.scala) already treated as mandatory. Everything else that differs is `info.version`. The live probes in [`HAZARDS.md`](HAZARDS.md) were made against the 2026-08-01 pin and carry over unchanged, because no response definition moved. |

The 2026-09-29 diff was produced by loading both documents and comparing every
`paths[*][method]` object, every `definitions[*]` object and every other
top-level key as parsed JSON, not as text.
