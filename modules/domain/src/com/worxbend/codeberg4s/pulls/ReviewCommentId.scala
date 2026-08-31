package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.PositiveId
import com.worxbend.codeberg4s.ValidationError

/** The instance-wide identifier of a [[ReviewComment]] — the `{comment}` of
  * `/repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments/{comment}`.
  *
  * Kept apart from [[ReviewId]] even though both are `int64` and both appear in the same path, one segment from each
  * other. `/reviews/{id}/comments/{comment}` with the two swapped is a request Forgejo answers `404` to, and a `404` on
  * a review comment reads like "the maintainer deleted it" rather than like a caller bug. The types are what make that
  * mistake fail to compile instead.
  *
  * ==No fixture proves the shape of the values==
  *
  * `golden/MANIFEST.md` holds no capture of a review-comment payload, so the only thing asserted here is Forgejo's
  * declared type — a positive `int64`, as every other row identifier in the API is. See [[ReviewComment]] for what that
  * means for the model as a whole.
  */
opaque type ReviewCommentId = Long

object ReviewCommentId:

  /** Parses a review-comment id.
    *
    * Rejects anything below `1`, for the reason [[com.worxbend.codeberg4s.PositiveId]] gives.
    *
    * @return
    *   the id, or a [[ValidationError]] on the `"reviewCommentId"` field
    */
  def from(value: Long): Either[ValidationError, ReviewCommentId] =
    PositiveId.from("reviewCommentId", value)

  extension (id: ReviewCommentId)

    /** The id as a `Long`. */
    def value: Long = id
