package com.worxbend.codeberg4s.paging

import com.worxbend.codeberg4s.ValidationError

/** How many items one page may hold — Forgejo's `limit` query parameter.
  *
  * Forgejo caps `limit` at 50 and silently clamps anything larger, which makes a caller believe it asked for 200 items
  * and got a short page. This type rejects the value instead, so the surprise happens at construction rather than three
  * pages into a fold.
  */
opaque type PageSize = Int

object PageSize:

  private val MinValue: Int = 1
  private val MaxValue: Int = 50

  /** The smallest page Forgejo serves, `1`. */
  val Min: PageSize = MinValue

  /** The largest page Forgejo serves, `50`. Asking for more is an error, not a clamp. */
  val Max: PageSize = MaxValue

  /** The size used when a caller expresses no preference, `30`. */
  val Default: PageSize = 30

  /** Parses a page size.
    *
    * Rejects anything outside `1..50`.
    *
    * @return
    *   the page size, or a [[ValidationError]] on the `"pageSize"` field
    */
  def from(value: Int): Either[ValidationError, PageSize] =
    if value < MinValue || value > MaxValue then
      Left(ValidationError("pageSize", s"must be between $MinValue and $MaxValue"))
    else Right(value)

  extension (size: PageSize)

    /** The size as an `Int`, ready for the `limit` query parameter. */
    def value: Int = size
