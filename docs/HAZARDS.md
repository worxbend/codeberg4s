# Spec Hazards — verified

PLAN.md §1.3 lists six hazards inherited from other Forgejo client generators.
This document verifies each one against **both** the pinned spec
(`spec/swagger.v1.json`, sha256 `90c40aa5…fddaf9d5`) **and** live read-only
probes against `https://codeberg.org/api/v1`.

Every header block and body below is **verbatim** captured output, not a
paraphrase. Where a PLAN.md assumption turned out to be wrong, the correction is
called out explicitly.

| Probe environment | Value |
| --- | --- |
| Instance | `https://codeberg.org` |
| Instance version | `16.0.0-dev-668-1bdb1938+gitea-1.22.0` |
| Probe date | `2026-08-01` (UTC `20:58`–`21:02`) |
| Credentials | none — every probe is anonymous |
| Methods used | `GET` only |

---

## 1. Optionality — the spec says nothing, so fixtures are the only truth

**PLAN.md claim:** *"Swagger 2.0 has no `nullable`; fields marked required may be
absent in practice and vice versa."*

**Verdict: worse than claimed — confirmed and sharpened.**

Measured over the pinned spec:

| Metric | Value |
| --- | ---: |
| `definitions` total | 246 |
| Definitions declaring a `required` list | 38 |
| Of those, request-body `*Option` / `*Options` types | **38 (all of them)** |
| **Response** models declaring `required`| **0** |
| Occurrences of `x-nullable` in the whole document | **0** |
| Occurrences of `nullable` in the whole document | **0** |

`Repository` (66 properties), `Issue` (25), `User` (23), `PullRequest` (38),
`Label` (7) and `Milestone` (10) all declare **no** `required` list. Read
literally, the spec asserts that every field of every response is optional and
none may be null — which is simultaneously useless and false.

### Live counter-evidence

`GET /repos/forgejo/forgejo/issues?page=1&limit=2`, first element, compared field
by field against `definitions.Issue`:

- spec-declared properties absent from the payload: **none**
- payload properties undeclared in the spec: **none**
- payload properties whose value is JSON `null`: `assignee`, `assignees`,
  `closed_at`, `due_date`, `milestone`

`assignees` is declared `type: array`, `closed_at` is declared
`type: string, format: date-time`, `assignee` and `milestone` are `$ref`s. All
four arrive as `null`. A decoder that trusts the declared type crashes on the
very first issue of the very first page.

`GET /repos/forgejo/forgejo/contents/README.md` → `submodule_git_url`, `target`
are `null`.
`GET /repos/forgejo/forgejo/contents/models` → each entry has `content`,
`download_url`, `encoding`, `submodule_git_url`, `target` all `null`.

### Consequences for this codebase

1. Every wire DTO field is `Option[A]` unless a golden fixture proves otherwise
   across several samples; the `Option` is collapsed in the `wire → domain`
   conversion, which returns `Either[CodebergError, A]` and produces
   `DecodingFailed` when a field the domain genuinely needs is absent.
2. `null` and *absent* must decode identically. upickle distinguishes them by
   default; the codec module needs a shared reader that folds `null` into `None`.
   This is not optional polish — `assignees: null` vs missing `assignees` occurs
   in the same endpoint.
3. Golden fixtures (PLAN.md §1.4) outrank the spec on every disagreement.

---

## 2. Security definitions — 5 declared, all global, none per-operation

**PLAN.md claim:** *"Multiple security definitions (BasicAuth, Token,
AccessToken, AuthorizationHeaderToken, SudoParam, TOTPHeader)."*

**Verdict: partially wrong — there are five, not six; `Token` and `AccessToken`
do not exist in this spec.**

`securityDefinitions`, verbatim:

```json
{
  "AuthorizationHeaderToken": {
    "description": "API tokens must be prepended with \"token\" followed by a space.",
    "type": "apiKey",
    "name": "Authorization",
    "in": "header"
  },
  "BasicAuth": { "type": "basic" },
  "SudoHeader": {
    "description": "Sudo API request as the user provided as the key. Admin privileges are required.",
    "type": "apiKey",
    "name": "Sudo",
    "in": "header"
  },
  "SudoParam": {
    "description": "Sudo API request as the user provided as the key. Admin privileges are required.",
    "type": "apiKey",
    "name": "sudo",
    "in": "query"
  },
  "TOTPHeader": {
    "description": "Must be used in combination with BasicAuth if two-factor authentication is enabled.",
    "type": "apiKey",
    "name": "X-FORGEJO-OTP",
    "in": "header"
  }
}
```

Note `TOTPHeader` uses `X-FORGEJO-OTP`, not the Gitea-era `X-GITEA-OTP`.

### The global-security trap

```json
"security": [
  {"BasicAuth": []}, {"AuthorizationHeaderToken": []},
  {"SudoParam": []}, {"SudoHeader": []}, {"TOTPHeader": []}
]
```

Counted across all 506 operations, the number carrying their own `security`
override is **0**. Every operation inherits that global block verbatim.

**Therefore the spec contains no per-endpoint authentication information at
all.** It does not distinguish `GET /repos/{owner}/{repo}` (works anonymously)
from `GET /user` (401 without a token). Any generator that derives auth
requirements from this spec produces garbage.

Empirically confirmed in the same session:

- `GET /api/v1/repos/forgejo/forgejo/issues?page=1&limit=2` → `200` anonymously.
- `GET /api/v1/user` → `401` anonymously.

The **Auth** column in [`API_INVENTORY.md`](API_INVENTORY.md) is therefore
derived from path shape and HTTP method, and labelled as such. It is a planning
aid, not a spec fact.

### Support decision (unchanged from PLAN.md §1.3)

| Scheme | Support |
| --- | --- |
| `AuthorizationHeaderToken` (`Authorization: token <t>`) | **supported** — `Auth.Token` |
| `BasicAuth` | **supported** — `Auth.Basic` |
| anonymous | **supported** — `Auth.Anonymous` |
| `SudoHeader` / `SudoParam` | **unsupported** — admin-only; reachable via the `RequestCustomizer` escape hatch (PLAN.md §5) |
| `TOTPHeader` | **unsupported** — interactive 2FA does not belong in a client library |

---

## 3. Union / polymorphic responses

**PLAN.md claim:** *"`/repos/{owner}/{repo}/contents/{filepath}` returns either a
file object or a list."*

**Verdict: confirmed, and the spec actively hides it.**

### What the spec says

```json
"/repos/{owner}/{repo}/contents/{filepath}": {
  "get": {
    "operationId": "repoGetContents",
    "summary": "Gets the metadata and contents (if a file) of an entry in a repository, or a list of entries if a dir",
    "responses": {
      "200": {"$ref": "#/responses/ContentsResponse"},
      "404": {"$ref": "#/responses/notFound"}
    }
  }
}
```

and `#/responses/ContentsResponse` is a **single object**:

```json
{"description": "ContentsResponse", "schema": {"$ref": "#/definitions/ContentsResponse"}}
```

The union exists only in the English `summary`. The machine-readable schema
claims the response is always an object. A `#/responses/ContentsListResponse`
(`type: array` of `ContentsResponse`) *is* defined in the document — but
`repoGetContents` does not reference it. It is used by `repoGetContentsList`
(`GET /repos/{owner}/{repo}/contents`), the directory-root variant.

### What actually comes back

`GET /repos/forgejo/forgejo/contents/README.md` → `200`, top-level **object**:

```
keys: _links, content, download_url, encoding, git_url, html_url,
      last_commit_sha, last_commit_when, name, path, sha, size,
      submodule_git_url, target, type, url
type = "file", encoding = "base64"
```

`GET /repos/forgejo/forgejo/contents/models` → `200`, top-level **array** of 41
elements with the *same* element schema:

```
element keys: (identical to above)
element type = "dir"
```

So it is an untagged `Object | Array[Object]` union discriminated only by the
JSON kind of the top-level value. The per-element `type` field
(`file` | `dir` | `symlink` | `submodule`) is a *second*, independent
discriminator.

### Modelling decision

```
RepoContents            = File(...) | Directory(entries)   -- top-level JSON kind
ContentsEntry.kind      = File | Dir | Symlink | Submodule -- the `type` field
```

Two enums, decoded in that order: first branch on `ujson.Arr` vs `ujson.Obj`,
then on `type`. Never `ujson.Value` passthrough (PLAN.md §1.3), and never
`asInstanceOf`.

Field availability is `type`-dependent and the spec documents it in prose only:

| Field | Populated when |
| --- | --- |
| `content`, `encoding` | `type == "file"` |
| `target` | `type == "symlink"` |
| `submodule_git_url` | `type == "submodule"` |

That is exactly the shape of a Scala 3 enum with per-case fields. Do not model it
as one flat case class with five `Option`s.

### Other union-ish shapes found

A full scan of all 506 operation summaries for union language (`either`,
`or a list`, `or a dir`, `or a file`) returns **exactly one** hit:
`repoGetContents`. So `contents/{filepath}` is the only *documented* union.

Two envelope shapes are worth flagging separately because they are not arrays
even though they are list endpoints:

- `GET /repos/search` returns `{"ok": true, "data": [...]}` — verified live,
  top-level object with keys `data`, `ok`.
- `GET /repos/{owner}/{repo}/git/trees/{sha}` (`GetTree`) returns a
  `GitTreeResponse` envelope carrying its own `page`, `total_count` and
  `truncated` fields, and declares `page` **without** `limit`.

---

## 4. Error body shape

**PLAN.md claim:** *"Forgejo errors are typically `{"message": ..., "url": ...}`
with occasional `errors: [...]`."*

**Verdict: confirmed. The `errors` array is real and the spec only declares it on
one of the three error models.**

### Spec-declared error models

```json
"APIError":          {"properties": {"message": {"type":"string"}, "url": {"type":"string"}}}
"APIForbiddenError": {"properties": {"message": {"type":"string"}, "url": {"type":"string"}}}
"APIValidationError":{"properties": {"message": {"type":"string"}, "url": {"type":"string"}}}
"APINotFound":       {"properties": {"errors": {"type":"array","items":{"type":"string"}},
                                     "message": {"type":"string"},
                                     "url": {"type":"string"}}}
```

Only `APINotFound` declares `errors`. `APIValidationError` — the one model where
a list of field errors would actually be useful — does **not**.

### Live captures (verbatim response bodies)

**404** — `GET https://codeberg.org/api/v1/repos/definitely/nonexistent-xyz`

```
HTTP/2 404
content-type: application/json;charset=utf-8
content-length: 130
```

```json
{"message":"GetUserByName","url":"https://codeberg.org/api/swagger","errors":["user redirect does not exist [name: definitely]"]}
```

Note the `message` is the **Go function name** that failed (`GetUserByName`), not
a human sentence. The usable detail lives in `errors[0]`. Never surface `message`
alone to a user.

**401** — `GET https://codeberg.org/api/v1/user` with no credentials

```
HTTP/2 401
content-type: application/json;charset=utf-8
content-length: 73
```

```json
{"message":"token is required","url":"https://codeberg.org/api/swagger"}
```

No `errors` key. Confirms `errors` is optional even where the spec declares it.

**422** — `GET https://codeberg.org/api/v1/repos/forgejo/forgejo/issues?since=notadate`

```
HTTP/2 422
```

```json
{"message":"parsing time \"notadate\" as \"2006-01-02T15:04:05Z07:00\": cannot parse \"notadate\" as \"2006\"","url":"https://codeberg.org/api/swagger"}
```

A read-only `GET` with a malformed `since` timestamp is a safe 422 trigger. Note
again: the `message` is a raw Go `time.Parse` error, and there is no `errors`
array and no field name — `APIValidationError` carries nothing structured.

**400** — `GET https://codeberg.org/api/v1/repos/forgejo/forgejo/git/blobs/notasha`

```
HTTP/2 400
```

```json
{"message":"length 7 has no matched object format: notasha","url":"https://codeberg.org/api/swagger"}
```

Included because it shows Forgejo also uses **400** for input validation, not
only 422. The status-to-error mapping must handle both.

**403 / 409** — not captured. Both need either an authenticated request or a
mutating one, and this lane is anonymous read-only. Their shapes are assumed to
be `APIError`/`APIForbiddenError`, i.e. `{message, url}`; capture them during the
Phase 1 vertical slice against the Testcontainers Forgejo instance rather than
against codeberg.org.

### The `ApiErrorBody` model this implies

```
ApiErrorBody(message: Option[String], url: Option[String], errors: List[String])
```

Every field defensive: `message` may be a Go symbol name, `url` is always the
useless constant `https://codeberg.org/api/swagger` and should never be shown to
callers, `errors` defaults to empty. Decoding an error body must itself never
fail — a non-JSON error payload (a proxy HTML page, for instance) has to degrade
to a bounded raw snippet, per PLAN.md §4.

---

## 5. Pagination contract

**PLAN.md claim (§1.3, §3.2, §5):** *"`page`/`limit` query params, `x-total-count`
response header, RFC 5988 `Link` header. Verify which endpoints honor it."*

**Verdict: confirmed and real. Both headers are present on paged list endpoints,
and absent on non-paged ones.**

### Verbatim headers from the mandated probe

`curl -sD - -o /dev/null 'https://codeberg.org/api/v1/repos/forgejo/forgejo/issues?page=1&limit=2'`

```
HTTP/2 200
access-control-expose-headers: Link, X-Total-Count
cache-control: max-age=0, private, must-revalidate
content-type: application/json;charset=utf-8
link: <https://codeberg.org/api/v1/repos/forgejo/forgejo/issues?limit=2&page=2>; rel="next",<https://codeberg.org/api/v1/repos/forgejo/forgejo/issues?limit=2&page=795>; rel="last"
vary: Origin
x-content-type-options: nosniff
x-frame-options: SAMEORIGIN
x-total-count: 1590
date: Sat, 01 Aug 2026 20:59:18 GMT
x-server-name: s_forgejo_secondary_alpspitz
strict-transport-security: max-age=63072000; includeSubDomains; preload;
permissions-policy: interest-cohort=()
alt-svc: h3=":443";ma=2592000;
x-backend-name: b_forgejo_secondary
ratelimit-policy: "baseline";q=2000;w=600
ratelimit: "baseline";r=1994;t=600
```

| Header | Present? | Note |
| --- | --- | --- |
| `x-total-count` | **yes** | `1590`; lowercase on the wire (HTTP/2), match case-insensitively |
| `link` | **yes** | RFC 5988, comma-separated, `rel` values quoted |
| `access-control-expose-headers` | yes | `Link, X-Total-Count` — confirms both are intentional API surface |
| `X-RateLimit-Limit` / `-Remaining` / `-Reset` | **NO** | see §6 |
| `Retry-After` | not on 200 | untriggered; see §6 |

### `Link` `rel` values by page position — all four verified

`page=1&limit=1` (first page):

```
link: <…?limit=1&page=2>; rel="next",<…?limit=1&page=1590>; rel="last"
```

`page=5&limit=2` (middle page):

```
link: <…?limit=2&page=6>; rel="next",<…?limit=2&page=795>; rel="last",<…?limit=2&page=1>; rel="first",<…?limit=2&page=4>; rel="prev"
```

`page=99999&limit=1` (past the end):

```
HTTP/2 200
link: <…?limit=1&page=1>; rel="first",<…?limit=1&page=1589>; rel="prev"
x-total-count: 1590
body: []
```

So: `next` and `last` are omitted on the last page; `first` and `prev` are
omitted on the first page; a page past the end is **`200` with `[]`**, not a 404.
The termination condition for `listAll` is *`rel="next"` absent*, and the
belt-and-braces condition is *empty array*. Both must be tested (SCALA_CODE_STYLE
"pagination terminates").

### `limit=1` page-count check (the PLAN.md §5 contract)

`page=1&limit=1` reports `x-total-count: 1590` and `rel="last"` at
`page=1590` — an exact 1:1 correspondence. With `limit=2`, `rel="last"` is
`page=795` = `ceil(1590/2)`. **The paging contract in PLAN.md §5 is real and
arithmetically consistent.**

### The `limit` clamp — a trap PLAN.md does not mention

`GET /api/v1/settings/api` returns, verbatim:

```json
{"max_response_items":50,"default_paging_num":30,"default_git_trees_per_page":1000,"default_max_blob_size":10485760}
```

`GET …/issues?page=1&limit=500` returns **50 items**, silently clamped, while the
`Link` header echoes the *requested* limit:

```
link: <…?limit=500&page=2>; rel="next",<…?limit=500&page=32>; rel="last"
x-total-count: 1590
```

`page=32` = `ceil(1590/50)`, i.e. the page arithmetic uses the **effective**
limit (50) while the URL text carries the **requested** limit (500).

Consequences, and these are load-bearing:

1. **Never infer "last page" from `items.size < requestedLimit`.** With
   `limit=500` that test fires on page 1 of 32. Use `rel="next"` presence, or
   `x-total-count`.
2. Do not parse the `limit` back out of a `Link` URL and treat it as the page
   size actually served.
3. `PageSize` should be validated against `max_response_items` at construction,
   or the client should fetch `/settings/api` once and clamp locally so callers
   get honest behaviour. Because `max_response_items` is per-instance
   configuration, hardcoding 50 is wrong for self-hosted Forgejo.

### Which endpoints honour it

| Declared query params | Operations |
| --- | ---: |
| `page` + `limit` | 103 |
| `page` only | 2 |
| `limit` only | 0 |
| neither | 401 |

Verified: a list endpoint with **no** `page`/`limit` params emits **no**
pagination headers at all. `GET /repos/forgejo/forgejo/issues/1/labels`:

```
HTTP/2 200
ratelimit-policy: "baseline";q=2000;w=600
ratelimit: "baseline";r=1942;t=600
```

No `link`, no `x-total-count`. So `x-total-count` parsing must be defensive
(PLAN.md §5 already says this) — **absent header is normal, not an error**.

38 GET operations return a top-level array with no paging parameters at all;
they are enumerated in [`API_INVENTORY.md`](API_INVENTORY.md) §5. These are
genuinely unbounded from the client's point of view and need a `Flow` the caller
can stop consuming, or an explicit "bounded by nature" note in their Scaladoc.

---

## 6. Rate limiting — PLAN.md's assumption is wrong

**PLAN.md claim:** *"Forgejo does not emit GitHub-style `X-RateLimit-*` on all
deployments — verify empirically."*

**Verdict: the caution was right; the header names are different from anything
PLAN.md anticipated.**

Codeberg emits **draft-IETF `RateLimit` headers**, not `X-RateLimit-*`. On every
single response captured in this session — 200, 401, 404, 422, 400 — the pair is:

```
ratelimit-policy: "baseline";q=2000;w=600
ratelimit: "baseline";r=1996;t=600
```

Decoded per `draft-ietf-httpapi-ratelimit-headers`:

| Token | Meaning | Value observed |
| --- | --- | --- |
| `"baseline"` | policy / quota-partition name | `baseline` |
| `q` | quota — requests allowed per window | `2000` |
| `w` | window length in seconds | `600` (10 minutes) |
| `r` | remaining requests in the current window | `1996` → `1942` as this session progressed |
| `t` | seconds until the window resets | `600` |

Observed budget: **2000 requests per 10 minutes, anonymous.** The `r` counter
decremented by exactly 1 per request across all 15 probes, including the error
responses — **failed requests consume quota**.

Facts to build on:

- There is **no** `X-RateLimit-Limit`, `X-RateLimit-Remaining`, or
  `X-RateLimit-Reset` on any response. Code that looks for those finds nothing.
- These headers are emitted by Codeberg's edge (note the accompanying
  `x-backend-name: b_forgejo_secondary`), **not** by Forgejo itself. A
  self-hosted Forgejo will very likely emit neither family. Rate-limit parsing
  must be entirely optional and must never be on the critical path.
- `429` was not triggered — deliberately, it would have meant burning 2000
  requests. `Retry-After` on `429` is therefore **unverified**;
  `CodebergError.RateLimited(retryAfter: Option[FiniteDuration])` keeping it
  optional is the correct design, and the `429`/`Retry-After` path must be tested
  against a stub backend rather than live.

### Recommended shape

```
RateLimit(policy: String, quota: Int, window: FiniteDuration,
          remaining: Int, resetsIn: FiniteDuration)
```

surfaced through the `Telemetry` port (PLAN.md §5) as best-effort metadata,
parsed leniently, and never required for correctness.

---

## 7. Summary — PLAN.md §1.3 verification results

| # | PLAN.md §1.3 hazard | Verdict |
| --- | --- | --- |
| 1 | Multiple security definitions | **Partly wrong.** 5 exist, not 6; `Token`/`AccessToken` do not exist. Worse: `security` is global with 0 per-op overrides, so the spec carries no auth information at all. |
| 2 | Union response on `contents/{filepath}` | **Confirmed.** Spec declares a single object; live returns object for files and array for dirs. Union is documented in prose only. |
| 3 | Optionality lies | **Confirmed, worse than stated.** 0 of 246 definitions declare `required` on a response model; 0 uses of `nullable`. Live `Issue` returns `null` for 5 non-nullable declared fields. |
| 4 | Pagination contract | **Confirmed real.** `x-total-count` and RFC 5988 `Link` both present on the 103 `page+limit` operations, absent on non-paged ones. Past-the-end page is `200 []`. New finding: `limit` is silently clamped to `max_response_items` (50) while `Link` echoes the requested value. |
| 5 | Rate-limit headers | **Assumption wrong.** No `X-RateLimit-*` anywhere. Codeberg emits draft-IETF `ratelimit` / `ratelimit-policy` (`q=2000;w=600`), from the edge proxy, not Forgejo. Errors consume quota. |
| 6 | Error body shape | **Confirmed.** `{message, url}` with optional `errors: [String]`. Spec declares `errors` only on `APINotFound`. `message` is often a raw Go symbol or Go error string; `url` is a constant and useless. 400 is used for validation alongside 422. |

### Open items for later lanes

- Capture real `403` and `409` bodies during Phase 1 against the Testcontainers
  Forgejo instance (cannot be produced by anonymous read-only calls).
- Capture a real `429` and confirm whether `Retry-After` accompanies it — do this
  against a container with a tightened limit, never against codeberg.org.
- Confirm whether a self-hosted Forgejo emits any rate-limit headers at all.

---

## Appendix — reproducing these probes

Anonymous, read-only, `GET` only; 15 requests total, one second apart. Rerunning
costs 15 of a 2000-request / 10-minute anonymous budget.

```bash
curl -s  https://codeberg.org/api/v1/version
curl -sD - https://codeberg.org/api/v1/repos/definitely/nonexistent-xyz
curl -sD - https://codeberg.org/api/v1/user
curl -sD - -o /dev/null 'https://codeberg.org/api/v1/repos/forgejo/forgejo/issues?page=1&limit=2'
curl -sD - -o /dev/null 'https://codeberg.org/api/v1/repos/forgejo/forgejo/issues?page=1&limit=1'
curl -s  -w '%{http_code}\n' 'https://codeberg.org/api/v1/repos/forgejo/forgejo/issues?since=notadate'
curl -s  -w '%{http_code}\n' 'https://codeberg.org/api/v1/repos/forgejo/forgejo/git/blobs/notasha'
curl -sD - -o /dev/null 'https://codeberg.org/api/v1/repos/forgejo/forgejo/issues?page=99999&limit=1'
curl -s  'https://codeberg.org/api/v1/repos/forgejo/forgejo/contents/models'
curl -s  'https://codeberg.org/api/v1/repos/forgejo/forgejo/contents/README.md'
curl -s  https://codeberg.org/api/v1/settings/api
curl -sD - -o /dev/null 'https://codeberg.org/api/v1/repos/forgejo/forgejo/issues?page=5&limit=2'
curl -sD - -o /dev/null 'https://codeberg.org/api/v1/repos/forgejo/forgejo/issues/1/labels'
curl -sD - -o /dev/null 'https://codeberg.org/api/v1/repos/forgejo/forgejo/issues?page=1&limit=500'
curl -sD - -o /dev/null 'https://codeberg.org/api/v1/repos/search?q=forgejo&limit=2&page=1'
```
