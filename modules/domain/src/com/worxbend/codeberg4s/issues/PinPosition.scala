package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

/** Where a pinned issue sits in a repository's pinned list — the `{position}` of
  * `PATCH /repos/{owner}/{repo}/issues/{index}/pin/{position}`.
  *
  * '''One-based, and that is the API's choice, not a convention invented here.''' Forgejo's own `Issue.pin_order` is
  * `0` for an issue that is not pinned and counts from `1` for one that is, so `0` as an argument would ask to move an
  * issue to the position that means "unpinned" — which is what [[com.worxbend.codeberg4s.issues.IssueApi.unpin]] is
  * for. Rejecting it here keeps a caller from discovering that distinction from a `404`.
  *
  * An `Int` rather than a `Long` because the spec declares this parameter as a plain `integer` with no `format`, unlike
  * every identifier in this group.
  *
  * ==Error contract==
  *
  * Construction produces [[ValidationError]] on the `"pinPosition"` field and nothing else; it performs no I/O.
  */
opaque type PinPosition = Int

object PinPosition:

  /** The first slot in the pinned list. */
  val First: PinPosition = 1

  /** Parses a pin position.
    *
    * Rejects anything below `1`; see the type note for why `0` in particular is refused.
    *
    * @return
    *   the position, or a [[ValidationError]] on the `"pinPosition"` field
    */
  def from(value: Int): Either[ValidationError, PinPosition] =
    if value < First then Left(ValidationError("pinPosition", s"must be at least $First")) else Right(value)

  extension (position: PinPosition)

    /** The position as an `Int`, ready to be rendered into a path segment. */
    def value: Int = position
