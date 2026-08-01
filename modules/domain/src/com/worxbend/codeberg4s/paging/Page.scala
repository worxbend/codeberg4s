package com.worxbend.codeberg4s.paging

/** One page of a paginated Forgejo collection, together with what the response said about its neighbours.
  *
  * A `Page` is what a single request returns; it is never the whole collection. Walking every page is an explicit
  * decision the caller makes, because a repository can hold tens of thousands of issues.
  *
  * @param items
  *   the items on this page, in the order the server returned them
  * @param params
  *   the window that produced this page, so the caller can reproduce or resume the request
  * @param totalCount
  *   the value of the `x-total-count` header. Absent is '''not''' an error: several Forgejo endpoints omit the header,
  *   so a caller must treat `None` as "unknown", never as zero
  * @param nextPage
  *   the following page when the response indicated one, otherwise `None`
  * @param prevPage
  *   the preceding page when there is one, otherwise `None`
  */
final case class Page[A](
    items: Vector[A],
    params: PageParams,
    totalCount: Option[Int],
    nextPage: Option[PageNumber],
    prevPage: Option[PageNumber],
):

  /** Applies `f` to every item, keeping the pagination metadata untouched.
    *
    * This is how a wire DTO page becomes a domain page. It cannot fail, so a conversion that can fail belongs before
    * the page is built.
    */
  def map[B](f: A => B): Page[B] =
    Page(items.map(f), params, totalCount, nextPage, prevPage)

  /** Whether the collection ends here — that is, the response offered no following page. */
  def isLast: Boolean = nextPage.isEmpty

  /** How many items this page holds. Not the size of the collection; see [[totalCount]]. */
  def size: Int = items.size
