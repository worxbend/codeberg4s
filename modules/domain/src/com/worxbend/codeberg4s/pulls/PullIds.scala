package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.ValidationError

/** Validation shared by every identifier in this group that Forgejo expresses as a positive integer.
  *
  * [[PullRequestNumber]] and [[ReviewId]] are both `int64` on the wire and both end up interpolated into a request
  * path. A number cannot forge a path, so the point is confusion rather than escaping: a pull request's per-repository
  * `number` and its instance-wide `id` are both `Long` and are routinely mixed up, and `/pulls/0` is a request Forgejo
  * answers with a `404` that reads like a missing pull request rather than like a caller bug.
  *
  * This duplicates `com.worxbend.codeberg4s.issues.NumericId`, which is `private[issues]` and therefore unreachable
  * from here. `docs/LEDGER.md` already lists that kind of helper under "helpers awaiting promotion"; the right fix is
  * one shared validator in the domain module root, not a widened `issues` internal.
  */
private[pulls] object PullIds:

  private val MinValue: Long = 1L

  /** Accepts `value` only if it is a positive identifier.
    *
    * @param field
    *   the field name to report in a [[ValidationError]]
    */
  def from(field: String, value: Long): Either[ValidationError, Long] =
    if value < MinValue then Left(ValidationError(field, s"must be at least $MinValue"))
    else Right(value)
