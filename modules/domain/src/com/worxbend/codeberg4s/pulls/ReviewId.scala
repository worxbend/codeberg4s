package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.PositiveId
import com.worxbend.codeberg4s.ValidationError

/** The instance-wide identifier of a [[Review]] — the `{id}` of `/repos/{owner}/{repo}/pulls/{index}/reviews/{id}`.
  *
  * Reviews are addressed by this id and never by a per-pull-request index: the ids on `golden/pull/reviews-list.json`
  * are `1654064`, `1654067` and `1654076` for three reviews of the '''same''' pull request, which is what an
  * instance-wide sequence looks like.
  */
opaque type ReviewId = Long

object ReviewId:

  /** Parses a review id.
    *
    * Rejects anything below `1`.
    *
    * @return
    *   the id, or a [[ValidationError]] on the `"reviewId"` field
    */
  def from(value: Long): Either[ValidationError, ReviewId] =
    PositiveId.from("reviewId", value)

  extension (id: ReviewId)

    /** The id as a `Long`. */
    def value: Long = id
