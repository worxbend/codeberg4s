package com.worxbend.codeberg4s.organizations.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.organizations.BlockId
import com.worxbend.codeberg4s.organizations.BlockedUser
import com.worxbend.codeberg4s.repositories.wire.Elements

/** Forgejo's `BlockedUser` model, field for field — both of its fields.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' `GET /orgs/{org}/list_blocked` needs a
  * token and `modules/codec/test/resources/golden` was harvested anonymously.
  *
  * The model names no account; see [[com.worxbend.codeberg4s.organizations.BlockedUser]] for why that is reported as-is
  * rather than patched over. There is no `login`, `user` or `username` key to read, so this DTO has no field for one —
  * adding a speculative field would be exactly the invention `docs/HAZARDS.md` warns against.
  *
  * @param blockId
  *   the `block_id` key
  * @param createdAt
  *   the `created_at` key as a raw string; parsed during conversion
  */
final case class BlockedUserDto(blockId: Option[Long], createdAt: Option[String]):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `block_id` is required and goes through [[com.worxbend.codeberg4s.organizations.BlockId.from]], because it is the
    * only thing that tells two entries of the listing apart — an entry with neither an id nor an account would be
    * indistinguishable from every other one, which is worse than a decoding failure naming the field.
    *
    * `created_at` that is blank, unparseable or the Go zero-time sentinel becomes `None`; see
    * [[com.worxbend.codeberg4s.codec.Timestamps]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, BlockedUser] =
    Wire
      .validated(at, "block_id", blockId)(BlockId.from)
      .map(identifier => BlockedUser(blockId = identifier, createdAt = Timestamps.parseOptional(createdAt)))

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, BlockedUser] =
    toDomainAt(JsonPath.Root)

object BlockedUserDto:

  /** Reads a `BlockedUser` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given upickle.default.Reader[BlockedUserDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so the field spellings exist in exactly one place. */
  def fromFields(fields: JsonFields): BlockedUserDto =
    BlockedUserDto(blockId = fields.number("block_id"), createdAt = fields.text("created_at"))

  /** Converts a decoded array of entries, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[BlockedUserDto]): Either[DecodeFailure, Vector[BlockedUser]] =
    Elements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
