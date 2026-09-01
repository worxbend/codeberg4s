package com.worxbend.codeberg4s.users.social.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Timestamps, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.users.social.{BlockId, BlockedUser}

/** Forgejo's `BlockedUser` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response''': `/user/list_blocked` requires a token and
  * the golden harvest was anonymous. Both declared properties are represented, and there are only two — see
  * [[com.worxbend.codeberg4s.users.social.BlockedUser]] for what the model conspicuously does not carry.
  *
  * @param blockId
  *   the `block_id` key
  * @param created
  *   the `created_at` key as a raw string
  */
final case class BlockedUserDto(blockId: Option[Long], created: Option[String]) extends WireModel[BlockedUser]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `block_id` is required, and on this model that is not a formality: it is the only field that distinguishes two
    * entries, so an entry without one is indistinguishable from every other entry in the page. It goes through
    * [[com.worxbend.codeberg4s.users.social.BlockId.from]], so a non-positive one is reported at `$.block_id`.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, BlockedUser] =
    Wire
      .validated(at, "block_id", blockId)(BlockId.from)
      .map(identifier => BlockedUser(blockId = identifier, createdAt = Timestamps.parseOptional(created)))

object BlockedUserDto:

  /** Reads a `BlockedUser` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[BlockedUserDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so the field spellings exist in exactly one place. */
  def fromFields(fields: JsonFields): BlockedUserDto =
    BlockedUserDto(
      blockId = fields.number("block_id"),
      created = fields.text("created_at"),
    )
