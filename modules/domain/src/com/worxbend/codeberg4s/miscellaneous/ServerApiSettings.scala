package com.worxbend.codeberg4s.miscellaneous

/** What `GET /settings/api` says about the instance's own limits.
  *
  * This is the endpoint that explains the hazard the rest of this library is built around. `maxResponseItems` is the
  * ceiling Forgejo puts on the `limit` query parameter, and it applies it '''silently''': a request for `limit=500`
  * comes back with 50 items while the RFC 5988 `Link` header echoes `limit=500` and computes `rel="last"` from the
  * effective limit. That is why `items.size < requestedLimit` is a broken end-of-pages test everywhere in this library,
  * why `PageSize` rejects anything above 50 at construction, and why `Pages` decides whether a next page exists from
  * `rel="next"` and from nothing else. The measurement is in `docs/HAZARDS.md` §5.
  *
  * Because the cap is per-instance configuration rather than a protocol constant, a client talking to a self-hosted
  * Forgejo should read this endpoint once rather than assume Codeberg's 50.
  *
  * @param maxResponseItems
  *   the largest number of items any paged endpoint will return, whatever `limit` was asked for; `50` on Codeberg
  * @param defaultPagingNum
  *   the page size used when a request omits `limit`; `30` on Codeberg
  * @param gitTreesPerPage
  *   the page size of `GET /repos/{owner}/{repo}/git/trees/{sha}`, which paginates on its own terms and is not bound by
  *   `maxResponseItems`. Absent on an instance that does not report it
  * @param maxBlobSizeBytes
  *   the largest blob the contents endpoints will inline, in bytes. Absent on an instance that does not report it
  */
final case class ServerApiSettings private[codeberg4s] (
    maxResponseItems: Long,
    defaultPagingNum: Long,
    gitTreesPerPage: Option[Long],
    maxBlobSizeBytes: Option[Long],
)
