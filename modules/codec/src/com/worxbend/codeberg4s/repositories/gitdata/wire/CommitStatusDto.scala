package com.worxbend.codeberg4s.repositories.gitdata.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.gitdata.CommitStatus
import com.worxbend.codeberg4s.repositories.gitdata.CommitStatusState
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.wire.UserDto

/** Forgejo's `CommitStatus` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' Note that the wire spells the verdict `status` while
  * the combined-status envelope spells the same thing `state`; both are read here and by [[CombinedStatusDto]]
  * respectively, and the domain calls both `state`.
  *
  * @param id
  *   the `id` key
  * @param status
  *   the `status` key: the verdict
  * @param context
  *   the `context` key: the check's name for itself
  * @param description
  *   the `description` key
  * @param targetUrl
  *   the `target_url` key
  * @param creator
  *   the `creator` key
  * @param createdAt
  *   the `created_at` key as a raw string
  * @param updatedAt
  *   the `updated_at` key as a raw string
  * @param url
  *   the `url` key
  */
final case class CommitStatusDto(
    id: Option[Long],
    status: Option[String],
    context: Option[String],
    description: Option[String],
    targetUrl: Option[String],
    creator: Option[UserDto],
    createdAt: Option[String],
    updatedAt: Option[String],
    url: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Requires `id`, which is the only thing that distinguishes two statuses written by the same check. An unrecognised
    * `status` becomes `None` rather than a failure — see
    * [[com.worxbend.codeberg4s.repositories.gitdata.CommitStatusState.parse]]. A failure inside `creator` is reported
    * at `creator`'s own path.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, CommitStatus] =
    for
      identifier <- Wire.required(at, "id", id)
      author     <- creatorAt(at)
    yield CommitStatus(
      id          = identifier,
      state       = status.flatMap(CommitStatusState.parse),
      context     = context,
      description = description,
      targetUrl   = targetUrl,
      creator     = author,
      created     = Timestamps.parseOptional(createdAt),
      updated     = Timestamps.parseOptional(updatedAt),
      url         = url,
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, CommitStatus] =
    toDomainAt(JsonPath.Root)

  private def creatorAt(at: JsonPath): Either[DecodeFailure, Option[User]] =
    creator.fold(Right(None))(dto => dto.toDomainAt(at.field("creator")).map(Some.apply))

object CommitStatusDto:

  /** Reads a `CommitStatus` object. */
  given JsonDecoder[CommitStatusDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, for the combined-status envelope that embeds these. */
  def fromFields(fields: JsonFields): CommitStatusDto =
    CommitStatusDto(
      id          = fields.number("id"),
      status      = fields.text("status"),
      context     = fields.text("context"),
      description = fields.text("description"),
      targetUrl   = fields.text("target_url"),
      creator     = fields.nested("creator").map(UserDto.fromFields),
      createdAt   = fields.text("created_at"),
      updatedAt   = fields.text("updated_at"),
      url         = fields.text("url"),
    )
