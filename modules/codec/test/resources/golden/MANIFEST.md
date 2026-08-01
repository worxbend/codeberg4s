# Golden fixture manifest

Real, unmodified JSON responses captured from the live Codeberg deployment of
Forgejo. They are the ground truth for codec tests; the Swagger spec is not.

| Field | Value |
| --- | --- |
| Base URL | `https://codeberg.org/api/v1` |
| Capture date | 2026-08-01 (UTC) |
| Server version | `16.0.0-dev-668-1bdb1938+gitea-1.22.0` (from `/version`) |
| Spec version | `16.0.0-dev-668-1bdb1938+gitea-1.22.0` (`spec/swagger.v1.json`) — identical, so the delta below is not a version skew |
| Authentication | none — anonymous, read-only `GET` |
| Requests issued | 61, one per second |
| Fixtures stored | 54 (53 real, 1 synthetic) |

## Handling rules

- Bodies are **verbatim**. The only transformation is pretty-printing with a
  4-space indent (`json.dumps(indent=4, ensure_ascii=False)`, equivalent to
  `python3 -m json.tool` but keeping UTF-8 literal instead of `\uXXXX`).
- No redaction, no field removal, no hand-fixing. Everything here is public data
  that any anonymous client sees.
- Do not regenerate a fixture to make a test pass. If the API shape changed,
  re-harvest deliberately and update this manifest.

## Fixtures

`Total` and `Link` record whether the response carried `X-Total-Count` and an
RFC 5988 `Link` header.

| File | Source path | Status | Total | Link | Origin |
| --- | --- | --- | --- | --- | --- |
| `version/version.json` | `/version` | 200 | — | no | real |
| `user/user-single.json` | `/users/earl-warren` | 200 | — | no | real |
| `user/user-single-org-shaped.json` | `/users/forgejo` | 200 | — | no | real |
| `user/user-repos-list.json` | `/users/earl-warren/repos?page=1&limit=3` | 200 | 25 | yes | real |
| `user/user-search.json` | `/users/search?q=earl&page=1&limit=3` | 200 | 128 | yes | real |
| `repository/repo-single.json` | `/repos/forgejo/forgejo` | 200 | — | no | real |
| `repository/repo-single-community.json` | `/repos/codeberg/Community` | 200 | — | no | real |
| `repository/branches-list.json` | `/repos/forgejo/forgejo/branches?limit=3` | 200 | 27 | yes | real |
| `repository/branch-single.json` | `/repos/forgejo/forgejo/branches/forgejo` | 200 | — | no | real |
| `repository/tags-list.json` | `/repos/forgejo/forgejo/tags?limit=3` | 200 | 296 | yes | real |
| `repository/releases-list.json` | `/repos/forgejo/forgejo/releases?limit=2` | 200 | 113 | yes | real |
| `repository/release-latest.json` | `/repos/forgejo/forgejo/releases/latest` | 200 | — | no | real |
| `repository/topics.json` | `/repos/forgejo/forgejo/topics` | 200 | 4 | no | real |
| `repository/contents-file.json` | `/repos/forgejo/forgejo/contents/README.md` | 200 | — | no | real |
| `repository/contents-dir.json` | `/repos/forgejo/forgejo/contents/` | 200 | — | no | real |
| `repository/contents-dir-small.json` | `/repos/codeberg/Community/contents/` | 200 | — | no | real |
| `repository/search.json` | `/repos/search?q=forgejo&limit=3` | 200 | 1902 | yes | real |
| `repository/commits-list.json` | `/repos/forgejo/forgejo/commits?limit=2` | 200 | 25481 | yes | real |
| `repository/git-commit-single.json` | `/repos/forgejo/forgejo/git/commits/647de8b3279b0ce6e9721cc651e431981725edf1` | 200 | — | no | real |
| `repository/languages.json` | `/repos/forgejo/forgejo/languages` | 200 | — | no | real |
| `repository/forks-list.json` | `/repos/forgejo/forgejo/forks?page=1&limit=2` | 200 | 862 | no | real |
| `repository/stargazers-list.json` | `/repos/forgejo/forgejo/stargazers?page=1&limit=3` | 200 | 5233 | no | real |
| `repository/git-tree.json` | `/repos/codeberg/Community/git/trees/main?per_page=5` | 200 | 2 | no | real |
| `issue/list-open.json` | `/repos/codeberg/Community/issues?state=open&limit=3` | 200 | 440 | yes | real |
| `issue/list-closed.json` | `/repos/codeberg/Community/issues?state=closed&limit=3` | 200 | 2152 | yes | real |
| `issue/list-labelled.json` | `/repos/forgejo/forgejo/issues?state=open&page=1&limit=3` | 200 | 1590 | yes | real |
| `issue/single.json` | `/repos/codeberg/Community/issues/2966` | 200 | — | no | real |
| `issue/comments-list.json` | `/repos/codeberg/Community/issues/2966/comments` | 200 | 2 | no | real |
| `issue/labels-repo.json` | `/repos/codeberg/Community/labels?page=1&limit=5` | 200 | 24 | no | real |
| `issue/labels-on-issue-empty.json` | `/repos/codeberg/Community/issues/2966/labels` | 200 | — | no | real |
| `issue/milestones-list.json` | `/repos/forgejo/forgejo/milestones?state=all&page=1&limit=3` | 200 | 180 | no | real |
| `issue/search.json` | `/repos/issues/search?state=open&page=1&limit=3` | 200 | 193825 | yes | real |
| `pull/list-all.json` | `/repos/forgejo/forgejo/pulls?state=all&limit=3` | 200 | 9453 | yes | real |
| `pull/list-closed.json` | `/repos/forgejo/forgejo/pulls?state=closed&limit=3` | 200 | 9286 | yes | real |
| `pull/single-merged.json` | `/repos/forgejo/forgejo/pulls/13726` | 200 | — | no | real |
| `pull/single-open.json` | `/repos/forgejo/forgejo/pulls/13731` | 200 | — | no | real |
| `pull/reviews-list.json` | `/repos/forgejo/forgejo/pulls/13726/reviews` | 200 | 3 | no | real |
| `pull/commits-list.json` | `/repos/forgejo/forgejo/pulls/13726/commits?page=1&limit=2` | 200 | 1 | no | real |
| `pull/files-list.json` | `/repos/forgejo/forgejo/pulls/13726/files?page=1&limit=3` | 200 | 1 | no | real |
| `organization/org-single.json` | `/orgs/forgejo` | 200 | — | no | real |
| `organization/org-repos-list.json` | `/orgs/forgejo/repos?page=1&limit=3` | 200 | 20 | yes | real |
| `organization/org-labels-list.json` | `/orgs/forgejo/labels?page=1&limit=3` | 200 | 17 | no | real |
| `organization/org-list.json` | `/orgs?page=1&limit=3` | 200 | 24159 | yes | real |
| `misc/settings-api.json` | `/settings/api` | 200 | — | no | real |
| `misc/settings-repository.json` | `/settings/repository` | 200 | — | no | real |
| `misc/settings-attachment.json` | `/settings/attachment` | 200 | — | no | real |
| `misc/gitignore-templates.json` | `/gitignore/templates` | 200 | — | no | real |
| `error/404-repo-not-found.json` | `/repos/forgejo/codeberg4s-no-such-repo` | 404 | — | no | real |
| `error/404-user-not-found.json` | `/users/codeberg4s-no-such-user-xyz` | 404 | — | no | real |
| `error/401-token-required.json` | `/user` | 401 | — | no | real |
| `error/401-org-teams.json` | `/orgs/forgejo/teams` | 401 | — | no | real |
| `error/401-user-orgs.json` | `/users/earl-warren/orgs?page=1&limit=3` | 401 | — | no | real |
| `error/422-invalid-sort.json` | `/repos/search?q=a&sort=bogus&page=1&limit=2` | 422 | — | no | real |
| `notification/list-synthetic.json` | (none — hand-authored from spec `NotificationThread`) | — | — | no | synthetic |

## Not captured, and why

| Wanted | Outcome |
| --- | --- |
| `GET /notifications` | Not reachable anonymously (`401 token is required`). Replaced by `notification/list-synthetic.json`, hand-authored from the spec's `NotificationThread` / `NotificationSubject` definitions. Its embedded `repository` object is the **real** `repository/repo-single-community.json` body, so only the thread wrapper is invented. Treat it as shape-only evidence, never as evidence of optionality. |
| `POST /markdown/raw` | `401 token is required`. Codeberg requires a token even for the anonymous-in-spec markdown endpoints, so no `misc/markdown-*.json` exists. |
| `GET /orgs/{org}/teams` | `401`. Kept as `error/401-org-teams.json` rather than an organization fixture. |
| `GET /orgs/{org}/members` | `401`. Body identical to `error/401-token-required.json`; not stored twice. |
| `GET /users/{u}/followers`, `/orgs`, `/starred` | All `401` on this deployment even though the spec marks them anonymous. Only `/users/{u}/orgs` is stored (`error/401-user-orgs.json`); the other two are byte-identical. |
| `GET /nodeinfo` | `404`, and the body is **plain text** `404 page not found`, not JSON. Not stored as `.json`. See the error-shape note below. |
| `GET /licenses` | Returns 80 KB with no `limit` support; dropped as fixture noise. `misc/gitignore-templates.json` covers the same "array of strings" shape. |
| A `403` body | Not obtainable anonymously. Every authorization failure observed was `401`. |
| `GET /repos/{o}/{r}/forks`, `/stargazers` | Captured only after adding `page=1`; see the pagination hazard below. |

## Verified hazards

**Pagination — `limit` alone is silently ignored on some endpoints.**
`GET /repos/forgejo/forgejo/forks?limit=2` returned all **862** forks (4.3 MB);
`GET .../stargazers?limit=3` returned all **5233** stargazers (3.2 MB). Adding
`page=1` made `limit` take effect. A client must always send `page` together
with `limit`, or it will pull an unbounded collection.

**`Link` is not universal.** `X-Total-Count` was present on every list endpoint
observed, but the RFC 5988 `Link` header was absent from `/forks`,
`/stargazers`, `/labels`, `/milestones`, `/issues/{n}/comments`,
`/pulls/{n}/reviews`, `/pulls/{n}/commits` and `/pulls/{n}/files`. Link-header
following cannot be the only pagination strategy.

**Two different error encodings.** Handler-level failures return the JSON
`APIError` shape. Router-level failures (unknown path, e.g. `/nodeinfo`) return
`text/plain` `404 page not found`. An error decoder must tolerate a non-JSON
body on a 4xx.

**No rate-limit headers.** No `X-RateLimit-*` header appeared on any of the 61
responses.

## Optionality delta versus `spec/swagger.v1.json`

Spec and server are the same build, so every difference below is the spec
lying, not drift.

- **No response definition declares `required`.** Only 38 of 246 definitions
  carry a `required` array and all of them are request/option types
  (`CreateIssueOption`, `EditTeamOption`, …). The spec therefore guarantees
  nothing about responses; the fixtures are the only evidence.
- **`User.username` is returned but is not in the spec.** Present on all 102
  observed `User` objects, and it duplicates `login`.
- **`APIError.errors` is returned but is not in the spec.** Seen on
  `error/404-repo-not-found.json` as `[]`; absent from the other five error
  bodies. `APIError` is specified as `{message, url}` only.
- **Absent versus null are both used, for the same model.** `Repository`
  omits `external_tracker` / `external_wiki` entirely when unconfigured, omits
  `internal_tracker` and `wiki_branch` on some repos, but sends
  `repo_transfer: null` and `parent: null` as explicit nulls. Every field on a
  response model must be optional *and* nullable.
- **Zero-time sentinel instead of null.** `User.last_login` (104×),
  `Repository.mirror_updated` (41×) and `CommitMeta.created` (4×) come back as
  `"0001-01-01T00:00:00Z"` where the natural encoding would be `null`.
- **`ContentsResponse` is a tagged union on `type`.** `type: "dir"` nulls
  `content`, `encoding` and `download_url`; `type: "file"` populates them.
  `submodule_git_url` and `target` were null on all 71 observed entries.
  Fixtures `repository/contents-file.json` and `repository/contents-dir.json`
  are the two arms.
- **Search endpoints wrap their payload.** `/users/search` and `/repos/search`
  return `{"data": [...], "ok": true}`, not a bare array.
