package com.worxbend.codeberg4s

/** Validation shared by every identifier Forgejo expresses as a positive integer.
  *
  * Almost every row identifier in the API — an issue number, a review id, a run id, a deploy key id, a webhook id — is
  * an `int64` that ends up interpolated into a request path. A number cannot forge a path, so this is not a security
  * boundary the way [[PathSegment]] is; the point is confusion. A pull request's per-repository `number` and its
  * instance-wide `id` are both `Long`, both readable off the same response, and routinely mixed up. `/pulls/0` is a
  * request Forgejo answers with a `404` that reads like a missing pull request rather than like a caller bug, so the
  * mistake is discovered late and misattributed.
  *
  * '''It lives in the root package so that the rule is written once.''' Each group of identifiers used to carry its own
  * copy — `issues.NumericId`, `pulls.PullIds`, `users.social.SocialIds`, `repositories.admin.AdminIds`,
  * `repositories.access.AccessIds`, `repositories.actions.ActionIds` — because each was private to its own package and
  * therefore unreachable from the next one, and a further handful of opaque types spelled the same two lines out
  * inline. That is the shape [[PathSegment]] was in before it was promoted, and it drifts the same way: a copy is what
  * gets forgotten when the rule changes.
  *
  * A value below `1` is refused rather than clamped, and the rejection names the caller's field, so the failure is
  * reported where the wrong number was supplied instead of at the endpoint that would have received it.
  */
private[codeberg4s] object PositiveId:

  /** The smallest identifier the instance can issue. Forgejo's identifiers are database row ids, which start at one. */
  private val MinValue: Long = 1L

  /** Accepts `value` only if it is a positive identifier.
    *
    * @param field
    *   the field name to report in a [[ValidationError]]
    * @return
    *   the value unchanged, or a [[ValidationError]] on `field`
    */
  def from(field: String, value: Long): Either[ValidationError, Long] =
    if value < MinValue then Left(ValidationError(field, s"must be at least $MinValue")) else Right(value)
