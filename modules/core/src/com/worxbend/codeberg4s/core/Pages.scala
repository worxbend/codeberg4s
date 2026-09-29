package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.paging.{Page, PageNumber, PageParams}

/** Turns a decoded item list plus a response's paging signals into a [[com.worxbend.codeberg4s.paging.Page]].
  *
  * Assembly lives here rather than in each endpoint so that no endpoint can invent its own end-of-collection test.
  * There has been exactly one such invention worth naming: `items.size < params.size`, which is wrong against Forgejo.
  * The instance clamps `limit` to its own `max_response_items` and still echoes the requested limit in the `Link`
  * header, so a caller asking for 50 items per page can receive short pages forever while more pages remain; a page
  * past the end answers `200` with `[]` rather than `404`. Nothing in this object compares how many items arrived with
  * how many were asked for.
  *
  * ==Two signals, in a fixed order==
  *
  * '''`Link` is authoritative when it is there.''' A response carrying at least one readable RFC 5988 relation has said
  * where the collection ends: `rel="next"` present means another page, absent means this was the last. That is the only
  * signal most Forgejo listings need, and `docs/HAZARDS.md` §5 has the measured evidence for it.
  *
  * '''The total count stands in when `Link` is not.''' Several Forgejo handlers set `X-Total-Count` and never a `Link`
  * header at all — repository, organisation and user webhooks, tree listings, wiki revisions, topics, issue
  * dependencies and blocks all call `SetTotalCountHeader` without `SetLinkHeader` — and before this object read the
  * total, every one of those listings reported its first page as its last and a walk over it silently returned a
  * fraction of the collection. So when no relation was offered and a total is known, the next page is
  * '''synthesised''': it is `page + 1` when the page is non-empty and `page × items.size < total`. `items.size`, the
  * number the server actually served, is the only page size that can be trusted — the requested `limit` may have been
  * clamped — and using it makes the rule exact on every full page. On a short last page it errs in the safe direction:
  * the arithmetic cannot tell "the last page, short" from "a full page under a clamped limit", so it offers one more
  * page, which comes back empty and ends the walk. One extra request on such a collection is the price of never
  * truncating one.
  *
  * The total itself comes from `x-total-count` where the instance sends it, and from the body where the instance puts
  * it there instead — see [[fromEnvelope]]. Its absence means "unknown", never zero: treating a missing total as zero
  * is how a caller convinces itself an inhabited collection is empty.
  */
object Pages:

  /** Builds one page from a bare-array listing, reading every paging signal off the response.
    *
    * `nextPage` follows the two-signal rule above, with the total taken from `x-total-count`. `prevPage` prefers
    * `rel="prev"` and falls back to the page before the one that was requested, which is sound because page numbers are
    * one-based and contiguous: if the caller asked for page `n > 1`, page `n - 1` exists whether or not a proxy
    * preserved the header.
    *
    * This function cannot fail: an unreadable `Link` header and a missing total simply produce a page that reports
    * itself as the last one, which is the safe direction to be wrong in.
    *
    * @param response
    *   the response the items were decoded from, read only for its headers
    * @param params
    *   the window that was requested, kept on the page so the caller can resume or reproduce the request
    * @param items
    *   the already-decoded items, in the order the server returned them
    */
  def from[A](response: CodebergResponse, params: PageParams, items: Vector[A]): Page[A] =
    assemble(response, params, items, response.totalCount)

  /** Builds one page from an envelope listing, whose total may travel in the body rather than in a header.
    *
    * Identical to [[from]] except for where the total is looked for: `x-total-count` first, because a header the
    * instance chose to send is the same signal every other listing uses, and the envelope's own count when the header
    * is absent — which on the tree route it always is. With the total in hand, the end-of-collection rule is the one
    * described on this object, so a tree of four thousand entries served without a `Link` header walks to its end
    * instead of stopping after page one.
    *
    * @param response
    *   the response the envelope was decoded from, read only for its headers
    * @param params
    *   the window that was requested
    * @param envelope
    *   the decoded items and the total the body reported, if any
    */
  def fromEnvelope[A](response: CodebergResponse, params: PageParams, envelope: ListEnvelope[A]): Page[A] =
    assemble(response, params, envelope.items, response.totalCount.orElse(envelope.totalCount))

  private def assemble[A](
      response: CodebergResponse,
      params: PageParams,
      items: Vector[A],
      totalCount: Option[Int],
  ): Page[A] =
    Page(
      items      = items,
      params     = params,
      totalCount = totalCount,
      nextPage   = nextPageOf(response, params.page, items.size, totalCount),
      prevPage   = response.prevPage.orElse(params.page.previous),
    )

  /** The two-signal rule: `rel="next"` when the response carried any readable relation, else the page synthesised from
    * the total, else none.
    *
    * "Carried any readable relation" rather than "carried a `Link` header" is deliberate. A `Link` header a proxy
    * mangled into something unreadable yields no relations, and a response like that is better served by the total than
    * by the assumption that the collection ended — the synthesised path terminates by itself on the empty page past the
    * end, so leaning on it can cost a request but cannot loop.
    *
    * @param served
    *   how many items this page holds — the effective page size, whatever `limit` was asked for
    */
  private[core] def nextPageOf(
      response: CodebergResponse,
      page: PageNumber,
      served: Int,
      totalCount: Option[Int],
  ): Option[PageNumber] =
    if response.links.nonEmpty then response.nextPage
    else totalCount.filter(total => served > 0 && page.value.toLong * served.toLong < total.toLong).map(_ => page.next)
