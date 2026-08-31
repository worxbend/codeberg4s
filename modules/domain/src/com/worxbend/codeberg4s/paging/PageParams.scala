package com.worxbend.codeberg4s.paging

/** The pagination window a caller asks for: which page, and how large.
  *
  * Both halves are validated, so a `PageParams` can always be turned into `page` and `limit` query parameters without
  * further checks.
  *
  * @param page
  *   the one-based page index
  * @param size
  *   how many items the page may hold
  */
final case class PageParams(page: PageNumber, size: PageSize):

  /** The same window advanced by one page, keeping the size. */
  def next: PageParams = PageParams(page.next, size)

  /** The same size at an explicit page. */
  def at(target: PageNumber): PageParams = PageParams(target, size)

object PageParams:

  /** The first page at [[PageSize.Default]] — where a fold over all pages starts.
    *
    * This constant is deliberately config-free: the domain module knows nothing about any particular client. A
    * `CodebergClient` exposes `firstPage`, the same window at the client's configured `defaultPageSize` — prefer that
    * when a client is in hand.
    */
  val First: PageParams = PageParams(PageNumber.First, PageSize.Default)
