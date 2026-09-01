package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Timestamps, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.pulls.{Review, ReviewId, ReviewState}
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.users.wire.UserDto

/** Forgejo's `PullReview` model, field for field.
  *
  * Every key on all three elements of `golden/pull/reviews-list.json` is represented. `team` is kept here and dropped
  * in conversion: it is Forgejo's `Team`, which `docs/LEDGER.md` assigns to the organisation wave, and it is `null` on
  * all three rows — so the DTO records that the key exists without this group inventing a model for it.
  *
  * ==What Forgejo puts in the empty spellings==
  *
  * Two of the three fixture rows are review '''requests''' rather than reviews, and they show the difference in the
  * data: `body` is `""`, `commit_id` is `""` and `html_url` is `""`, while the third row — an `APPROVED` review —
  * carries a real commit id and a real anchor URL. [[com.worxbend.codeberg4s.codec.JsonFields.text]] folds `""` into
  * absence, so the domain sees three genuinely absent fields rather than three empty strings.
  *
  * `state` stays a raw string here; [[com.worxbend.codeberg4s.pulls.ReviewState.parse]] interprets it during
  * conversion, and does so leniently — see [[toDomainAt]].
  */
final case class ReviewDto(
    id: Option[Long],
    user: Option[UserDto],
    team: Option[JsonFields],
    state: Option[String],
    body: Option[String],
    commitId: Option[String],
    stale: Option[Boolean],
    official: Option[Boolean],
    dismissed: Option[Boolean],
    commentsCount: Option[Long],
    htmlUrl: Option[String],
    pullRequestUrl: Option[String],
    submittedAt: Option[String],
    updatedAt: Option[String],
) extends WireModel[Review]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Only `id` is required, and it goes through [[com.worxbend.codeberg4s.pulls.ReviewId.from]] because it is the only
    * way to address a review. `commit_id` is strict when present — a non-hexadecimal object id is reported at
    * `$.commit_id` rather than dropped — for the reason [[com.worxbend.codeberg4s.codec.Wire.optional]] gives.
    *
    * `state` is the deliberately '''lenient''' field: a spelling [[com.worxbend.codeberg4s.pulls.ReviewState.parse]]
    * does not recognise becomes `None` instead of failing the review, which is also what happens to the `""` Forgejo
    * sends for a stateless row. A state this library has not seen must not cost the caller the other reviews on the
    * page.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, Review] =
    for
      identifier <- Wire.validated(at, "id", id)(ReviewId.from)
      reviewer   <- Wire.nested(at, "user", user)(_.toDomainAt(_))
      pinned     <- Wire.optional(at, "commit_id", commitId)(CommitSha.from)
    yield Review(
      id             = identifier,
      state          = state.flatMap(ReviewState.parse),
      body           = body,
      author         = reviewer,
      commit         = pinned,
      isStale        = stale.getOrElse(false),
      isOfficial     = official.getOrElse(false),
      isDismissed    = dismissed.getOrElse(false),
      commentCount   = commentsCount.getOrElse(0L),
      htmlUrl        = htmlUrl,
      pullRequestUrl = pullRequestUrl,
      submittedAt    = Timestamps.parseOptional(submittedAt),
      updatedAt      = Timestamps.parseOptional(updatedAt),
    )

object ReviewDto:

  /** Reads a `PullReview` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ReviewDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, reusing [[com.worxbend.codeberg4s.users.wire.UserDto.fromFields]] for the
    * `user` field. `team` is kept as the raw field view rather than as a model; see the class note.
    */
  def fromFields(fields: JsonFields): ReviewDto =
    ReviewDto(
      id             = fields.number("id"),
      user           = fields.nested("user").map(UserDto.fromFields),
      team           = fields.nested("team"),
      state          = fields.text("state"),
      body           = fields.text("body"),
      commitId       = fields.text("commit_id"),
      stale          = fields.boolean("stale"),
      official       = fields.boolean("official"),
      dismissed      = fields.boolean("dismissed"),
      commentsCount  = fields.number("comments_count"),
      htmlUrl        = fields.text("html_url"),
      pullRequestUrl = fields.text("pull_request_url"),
      submittedAt    = fields.text("submitted_at"),
      updatedAt      = fields.text("updated_at"),
    )

  /** Converts a decoded array of reviews, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[ReviewDto]): Either[DecodeFailure, Vector[Review]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
