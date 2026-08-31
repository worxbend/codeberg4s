package com.worxbend.codeberg4s.users.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Timestamps, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.users.PublicKey

/** Forgejo's `PublicKey` model, field for field.
  *
  * Unlike every other DTO in this module, this one has '''no golden fixture behind it''': the captures in
  * `modules/codec/test/resources/golden` are anonymous, `/user/keys` answers `401` without a token, and
  * `/users/{username}/keys` was not among the 61 paths harvested. The eleven fields below are exactly the properties of
  * `definitions.PublicKey` in `spec/swagger.v1.json`, and `docs/HAZARDS.md` §1 is why every one of them is `Option`
  * anyway — the spec declares no `required` list on any response model, so it is evidence of what keys may appear and
  * of nothing else.
  *
  * `user` nests [[UserDto]] rather than flattening a login out of it, per rule 6 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]].
  *
  * Timestamps stay as raw strings; [[com.worxbend.codeberg4s.codec.Timestamps]] normalises them, sentinels included,
  * during conversion.
  */
final case class PublicKeyDto(
    id: Option[Long],
    key: Option[String],
    title: Option[String],
    fingerprint: Option[String],
    keyType: Option[String],
    url: Option[String],
    user: Option[UserDto],
    readOnly: Option[Boolean],
    verified: Option[Boolean],
    createdAt: Option[String],
    updatedAt: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Two things are required. `id` is what `DELETE /user/keys/{id}` addresses, and `key` is the key material itself —
    * an entry carrying neither identifies nothing and describes nothing, so it is not a public key. Everything else is
    * optional or defaulted: absent flags become `false`, which is the reading that grants the fewest privileges.
    *
    * A failure inside `user` is reported at that nested path, not at the key's.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, PublicKey] =
    for
      identifier <- Wire.required(at, "id", id)
      material   <- Wire.required(at, "key", key)
      ownerModel <- Wire.nested(at, "user", user)(_.toDomainAt(_))
    yield PublicKey(
      id          = identifier,
      key         = material,
      title       = title,
      fingerprint = fingerprint,
      keyType     = keyType,
      url         = url,
      owner       = ownerModel,
      isReadOnly  = readOnly.getOrElse(false),
      isVerified  = verified.getOrElse(false),
      createdAt   = Timestamps.parseOptional(createdAt),
      updatedAt   = Timestamps.parseOptional(updatedAt),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, PublicKey] =
    toDomainAt(JsonPath.Root)

object PublicKeyDto:

  /** Reads a `PublicKey` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[PublicKeyDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. Reuses [[UserDto.fromFields]] for `user`, so the user field spellings are not
    * repeated here.
    */
  def fromFields(fields: JsonFields): PublicKeyDto =
    PublicKeyDto(
      id          = fields.number("id"),
      key         = fields.text("key"),
      title       = fields.text("title"),
      fingerprint = fields.text("fingerprint"),
      keyType     = fields.text("key_type"),
      url         = fields.text("url"),
      user        = fields.nested("user").map(UserDto.fromFields),
      readOnly    = fields.boolean("read_only"),
      verified    = fields.boolean("verified"),
      createdAt   = fields.text("created_at"),
      updatedAt   = fields.text("updated_at"),
    )
