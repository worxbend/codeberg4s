package com.worxbend.codeberg4s.repositories.admin.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Timestamps, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.wire.CommentDto
import com.worxbend.codeberg4s.repositories.admin.{ActivityId, ActivityOperation, RepositoryActivity}
import com.worxbend.codeberg4s.repositories.wire.RepositoryDto
import com.worxbend.codeberg4s.users.wire.UserDto

/** Forgejo's `Activity` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' The endpoint is repository-scoped and
  * `modules/codec/test/resources/golden` was harvested anonymously, so no fixture backs this shape. All thirteen
  * declared properties are represented; three of them — `act_user_id`, `repo_id` and `user_id` — are kept here and
  * dropped in conversion, because each duplicates something the embedded object already carries.
  *
  * The embedded objects are the existing DTOs rather than new ones: `act_user` is a `User`, `repo` a `Repository` and
  * `comment` a `Comment`, so [[com.worxbend.codeberg4s.users.wire.UserDto]],
  * [[com.worxbend.codeberg4s.repositories.wire.RepositoryDto]] and [[com.worxbend.codeberg4s.issues.wire.CommentDto]]
  * read them and the field spellings stay in one place each.
  *
  * @param id
  *   the `id` key
  * @param actUser
  *   the `act_user` key
  * @param actUserId
  *   the `act_user_id` key, dropped in conversion
  * @param opType
  *   the `op_type` key
  * @param refName
  *   the `ref_name` key
  * @param content
  *   the `content` key
  * @param repo
  *   the `repo` key
  * @param repoId
  *   the `repo_id` key, dropped in conversion
  * @param comment
  *   the `comment` key
  * @param commentId
  *   the `comment_id` key, dropped in conversion
  * @param userId
  *   the `user_id` key, dropped in conversion
  * @param isPrivate
  *   the `is_private` key
  * @param created
  *   the `created` key as a raw string
  */
final case class ActivityDto(
    id: Option[Long],
    actUser: Option[UserDto],
    actUserId: Option[Long],
    opType: Option[String],
    refName: Option[String],
    content: Option[String],
    repo: Option[RepositoryDto],
    repoId: Option[Long],
    comment: Option[CommentDto],
    commentId: Option[Long],
    userId: Option[Long],
    isPrivate: Option[Boolean],
    created: Option[String],
) extends WireModel[RepositoryActivity]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Fails only on `id`, through [[com.worxbend.codeberg4s.repositories.admin.ActivityId.from]]: without it two entries
    * describing the same action are indistinguishable and no caller can de-duplicate a feed it polls. A failure inside
    * `act_user`, `repo` or `comment` is reported at that key's own path, because each of those has required fields of
    * its own.
    *
    * `op_type` that this release does not recognise becomes `None` rather than a failure — see
    * [[com.worxbend.codeberg4s.repositories.admin.ActivityOperation.parse]] for why a feed must survive a Forgejo
    * release that adds one.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, RepositoryActivity] =
    for
      identifier <- Wire.validated(at, "id", id)(ActivityId.from)
      actor      <- Wire.nested(at, "act_user", actUser)(_.toDomainAt(_))
      repository <- Wire.nested(at, "repo", repo)(_.toDomainAt(_))
      remark     <- Wire.nested(at, "comment", comment)(_.toDomainAt(_))
    yield RepositoryActivity(
      id         = identifier,
      actor      = actor,
      operation  = opType.flatMap(ActivityOperation.parse),
      refName    = refName,
      content    = content,
      repository = repository,
      comment    = remark,
      isPrivate  = isPrivate.getOrElse(false),
      createdAt  = Timestamps.parseOptional(created),
    )

object ActivityDto:

  /** Reads an `Activity` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ActivityDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so the field spellings exist in exactly one place. */
  def fromFields(fields: JsonFields): ActivityDto =
    ActivityDto(
      id        = fields.number("id"),
      actUser   = fields.nested("act_user").map(UserDto.fromFields),
      actUserId = fields.number("act_user_id"),
      opType    = fields.text("op_type"),
      refName   = fields.text("ref_name"),
      content   = fields.text("content"),
      repo      = fields.nested("repo").map(RepositoryDto.fromFields),
      repoId    = fields.number("repo_id"),
      comment   = fields.nested("comment").map(CommentDto.fromFields),
      commentId = fields.number("comment_id"),
      userId    = fields.number("user_id"),
      isPrivate = fields.boolean("is_private"),
      created   = fields.text("created"),
    )

  /** Converts a whole array, each element failing at its own index. */
  def toDomainAll(base: JsonPath, dtos: Vector[ActivityDto]): Either[DecodeFailure, Vector[RepositoryActivity]] =
    ArrayElements.convert(base, dtos)((dto, at) => dto.toDomainAt(at))
