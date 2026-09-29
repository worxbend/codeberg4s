package com.worxbend.codeberg4s.paging

import com.worxbend.codeberg4s.ValidationError

/** A one-based page index, as Forgejo's `page` query parameter expects it.
  *
  * Forgejo treats `page=0` as `page=1` rather than reporting an error, which silently hides an off-by-one in caller
  * code; this type rejects it instead.
  */
opaque type PageNumber = Int

object PageNumber:

  /** The first page, `1`. */
  val First: PageNumber = 1

  private val MinValue: Int = 1

  /** Parses a page number.
    *
    * Rejects anything below `1`. There is no upper bound: how many pages exist is a property of the response, not of
    * the request.
    *
    * @return
    *   the page number, or a [[ValidationError]] on the `"pageNumber"` field
    */
  def from(value: Int): Either[ValidationError, PageNumber] =
    if value < MinValue then Left(ValidationError("pageNumber", s"must be at least $MinValue"))
    else Right(value)

  extension (page: PageNumber)

    /** The page index as an `Int`, ready for the `page` query parameter. */
    def value: Int = page

    /** The following page.
      *
      * Always valid: page numbers have no upper bound, and the one place `Int` runs out is handled rather than wrapped.
      * `Int.MaxValue.next` is `Int.MaxValue` again — saturating, not overflowing — because `page + 1` there would be a
      * negative number, which is exactly the value [[from]] exists to keep out of a `page` parameter. No instance holds
      * two billion pages of anything, so a fold that reaches this point is one that has already stopped terminating on
      * an empty page, and repeating the last page is the failure that ends in a `Left` rather than in an invalid
      * request.
      */
    def next: PageNumber = if page < Int.MaxValue then page + 1 else page

    /** The preceding page, or `None` when this is already the first page. */
    def previous: Option[PageNumber] = if page > MinValue then Some(page - 1) else None
