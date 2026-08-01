package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

/** Validation shared by every identifier in this group that Forgejo expresses as a positive integer.
  *
  * [[IssueNumber]], [[LabelId]], [[MilestoneId]] and [[CommentId]] are all `int64` on the wire and all end up
  * interpolated into a request path. Unlike [[com.worxbend.codeberg4s.repositories.Owner]] a number cannot forge a
  * path, so the point here is not escaping but confusion: an issue's per-repository `number` and its instance-wide `id`
  * are both `Long` and are routinely mixed up, and `/issues/0` is a request Forgejo answers with a `404` that reads
  * like a missing issue rather than like a caller bug. Rejecting non-positive values once, here, keeps both problems
  * out of the four opaque types.
  */
private[issues] object NumericId:

  private val MinValue: Long = 1L

  /** Accepts `value` only if it is a positive identifier.
    *
    * @param field
    *   the field name to report in a [[ValidationError]]
    */
  def from(field: String, value: Long): Either[ValidationError, Long] =
    if value < MinValue then Left(ValidationError(field, s"must be at least $MinValue"))
    else Right(value)
