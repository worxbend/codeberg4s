package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Timestamps, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.pulls.{ReviewComment, ReviewCommentId, ReviewId}
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.users.wire.UserDto

/** Forgejo's `PullReviewComment` model, field for field.
  *
  * ==Derived from the pinned spec, not from a capture==
  *
  * Every field below is one of the fifteen properties of `spec/swagger.v1.json`'s `PullReviewComment` definition.
  * `golden/MANIFEST.md` records no review-comment fixture — the reviews the anonymous harvest could reach all carried
  * `comments_count: 0` — so nothing here has been checked against a real payload, and the Scaladoc says so rather than
  * implying a verified shape. Because `docs/HAZARDS.md` §1 measured that the spec asserts nothing about optionality,
  * every field is treated as absent-able, which is the same rule the captured models follow; only the evidence is
  * weaker.
  *
  * ==The two commit ids are strict, the review id is not==
  *
  * `commit_id` and `original_commit_id` go through [[com.worxbend.codeberg4s.codec.Wire.optional]]: legitimately
  * absent, and a present-but-unparseable object id is reported at its own path rather than dropped, for the reason
  * [[com.worxbend.codeberg4s.codec.Wire.optional]] gives.
  *
  * `pull_request_review_id` is deliberately treated differently. Forgejo's Go struct types it as a plain `int64` with
  * no `omitempty`, so a comment that is not yet attached to a submitted review serialises it as `0` — which
  * [[com.worxbend.codeberg4s.pulls.ReviewId.from]] rejects. Zero is filtered to absence '''before''' validation, so
  * that placeholder costs the caller the link and not the comment.
  */
final case class ReviewCommentDto(
    id: Option[Long],
    pullRequestReviewId: Option[Long],
    body: Option[String],
    path: Option[String],
    position: Option[Long],
    originalPosition: Option[Long],
    extraLinesCount: Option[Long],
    diffHunk: Option[String],
    commitId: Option[String],
    originalCommitId: Option[String],
    user: Option[UserDto],
    resolver: Option[UserDto],
    htmlUrl: Option[String],
    pullRequestUrl: Option[String],
    createdAt: Option[String],
    updatedAt: Option[String],
) extends WireModel[ReviewComment]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Only `id` is required, because it is the only way any endpoint addresses a review comment. The three positional
    * counts fall back to `0`, which is the same value Forgejo's own `uint64` sentinel carries, so an absent key and an
    * explicit `0` reach the domain identically — see [[com.worxbend.codeberg4s.pulls.ReviewComment]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, ReviewComment] =
    for
      identifier <- Wire.validated(at, "id", id)(ReviewCommentId.from)
      review     <- Wire.optional(at, "pull_request_review_id", pullRequestReviewId.filter(_ > 0L))(ReviewId.from)
      pinned     <- Wire.optional(at, "commit_id", commitId)(CommitSha.from)
      original   <- Wire.optional(at, "original_commit_id", originalCommitId)(CommitSha.from)
      author     <- Wire.nested(at, "user", user)(_.toDomainAt(_))
      resolvedBy <- Wire.nested(at, "resolver", resolver)(_.toDomainAt(_))
    yield ReviewComment(
      id               = identifier,
      reviewId         = review,
      body             = body,
      path             = path,
      position         = position.getOrElse(0L),
      originalPosition = originalPosition.getOrElse(0L),
      extraLinesCount  = extraLinesCount.getOrElse(0L),
      diffHunk         = diffHunk,
      commit           = pinned,
      originalCommit   = original,
      author           = author,
      resolver         = resolvedBy,
      htmlUrl          = htmlUrl,
      pullRequestUrl   = pullRequestUrl,
      createdAt        = Timestamps.parseOptional(createdAt),
      updatedAt        = Timestamps.parseOptional(updatedAt),
    )

object ReviewCommentDto:

  /** Reads a `PullReviewComment` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ReviewCommentDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, reusing [[com.worxbend.codeberg4s.users.wire.UserDto.fromFields]] for both
    * account fields so that no field spelling is written twice.
    *
    * `diff_hunk` is read with `rawText` rather than `text`: a hunk is whitespace-significant, and folding a hunk that
    * happens to be blank into absence would be this module editing a diff.
    */
  def fromFields(fields: JsonFields): ReviewCommentDto =
    ReviewCommentDto(
      id                  = fields.number("id"),
      pullRequestReviewId = fields.number("pull_request_review_id"),
      body                = fields.text("body"),
      path                = fields.text("path"),
      position            = fields.number("position"),
      originalPosition    = fields.number("original_position"),
      extraLinesCount     = fields.number("extra_lines_count"),
      diffHunk            = fields.rawText("diff_hunk").filter(_.nonEmpty),
      commitId            = fields.text("commit_id"),
      originalCommitId    = fields.text("original_commit_id"),
      user                = fields.nested("user").map(UserDto.fromFields),
      resolver            = fields.nested("resolver").map(UserDto.fromFields),
      htmlUrl             = fields.text("html_url"),
      pullRequestUrl      = fields.text("pull_request_url"),
      createdAt           = fields.text("created_at"),
      updatedAt           = fields.text("updated_at"),
    )
