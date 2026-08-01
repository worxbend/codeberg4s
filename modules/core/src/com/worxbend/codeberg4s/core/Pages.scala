package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams

/** Turns a decoded item list plus a response's paging headers into a [[com.worxbend.codeberg4s.paging.Page]].
  *
  * Assembly lives here rather than in each endpoint so that no endpoint can invent its own end-of-collection test.
  * There has been exactly one such invention worth naming: `items.size < params.size`, which is wrong against Forgejo.
  * The instance clamps `limit` to its own `max_response_items` and still echoes the requested limit in the `Link`
  * header, so a caller asking for 50 items per page can receive short pages forever while more pages remain; a page
  * past the end answers `200` with `[]` rather than `404`. Nothing in this object looks at how many items arrived.
  */
object Pages:

  /** Builds one page from what the response said about its neighbours.
    *
    * `nextPage` comes from `rel="next"` and from nothing else. `prevPage` prefers `rel="prev"` and falls back to the
    * page before the one that was requested, which is sound because page numbers are one-based and contiguous: if the
    * caller asked for page `n > 1`, page `n - 1` exists whether or not a proxy preserved the header. `totalCount` comes
    * from `x-total-count`, and its absence means "unknown" — several Forgejo endpoints omit it, and treating that as
    * zero is how a caller convinces itself an inhabited collection is empty.
    *
    * This function cannot fail: an unreadable or missing `Link` header simply produces a page that reports itself as
    * the last one, which is the safe direction to be wrong in.
    *
    * @param response
    *   the response the items were decoded from, read only for its headers
    * @param params
    *   the window that was requested, kept on the page so the caller can resume or reproduce the request
    * @param items
    *   the already-decoded items, in the order the server returned them
    */
  def from[A](response: CodebergResponse, params: PageParams, items: Vector[A]): Page[A] =
    Page(
      items      = items,
      params     = params,
      totalCount = response.totalCount,
      nextPage   = response.nextPage,
      prevPage   = response.prevPage.orElse(params.page.previous),
    )
