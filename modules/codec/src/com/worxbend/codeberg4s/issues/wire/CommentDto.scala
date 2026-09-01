package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Timestamps, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.{Comment, CommentId}
import com.worxbend.codeberg4s.users.wire.UserDto

/** Forgejo's `Comment` model, field for field.
  *
  * Every key observed on the two elements of `golden/issue/comments-list.json` is represented, except one:
  *
  *   - `assets` is an array of Forgejo's `Attachment` model, which no wave owns. It is `[]` on both comments in the
  *     fixture, and inventing an attachment model from an empty array would be a guess. It belongs to whichever wave
  *     first needs to read an attachment, and until then [[com.worxbend.codeberg4s.codec.JsonFields]] ignores the key,
  *     so a comment carrying attachments still decodes.
  *
  * `original_author` and `pull_request_url` are `""` on both fixture comments.
  * [[com.worxbend.codeberg4s.codec.JsonFields.text]] folds Forgejo's `""`-for-absent convention into `None`, so the
  * domain sees absence rather than an empty string.
  */
final case class CommentDto(
    id: Option[Long],
    body: Option[String],
    user: Option[UserDto],
    originalAuthor: Option[String],
    originalAuthorId: Option[Long],
    htmlUrl: Option[String],
    issueUrl: Option[String],
    pullRequestUrl: Option[String],
    createdAt: Option[String],
    updatedAt: Option[String],
) extends WireModel[Comment]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Only `id` is required, and it goes through [[com.worxbend.codeberg4s.issues.CommentId.from]]. Everything else is
    * genuinely optional: an empty comment body is legal, and a comment imported from another forge carries a
    * placeholder account rather than a real one, which is why `user` is not required.
    *
    * A failure inside `user` is reported at `$.user`, not at the comment's own path.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, Comment] =
    for
      identifier <- Wire.validated(at, "id", id)(CommentId.from)
      writer     <- Wire.nested(at, "user", user)(_.toDomainAt(_))
    yield Comment(
      id             = identifier,
      body           = body,
      author         = writer,
      originalAuthor = originalAuthor,
      htmlUrl        = htmlUrl,
      issueUrl       = issueUrl,
      pullRequestUrl = pullRequestUrl,
      createdAt      = Timestamps.parseOptional(createdAt),
      updatedAt      = Timestamps.parseOptional(updatedAt),
    )

object CommentDto:

  /** Reads a `Comment` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[CommentDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, reusing [[com.worxbend.codeberg4s.users.wire.UserDto.fromFields]] for the
    * `user` field.
    */
  def fromFields(fields: JsonFields): CommentDto =
    CommentDto(
      id               = fields.number("id"),
      body             = fields.text("body"),
      user             = fields.nested("user").map(UserDto.fromFields),
      originalAuthor   = fields.text("original_author"),
      originalAuthorId = fields.number("original_author_id"),
      htmlUrl          = fields.text("html_url"),
      issueUrl         = fields.text("issue_url"),
      pullRequestUrl   = fields.text("pull_request_url"),
      createdAt        = fields.text("created_at"),
      updatedAt        = fields.text("updated_at"),
    )

  /** Converts a decoded array of comments, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[CommentDto]): Either[DecodeFailure, Vector[Comment]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
