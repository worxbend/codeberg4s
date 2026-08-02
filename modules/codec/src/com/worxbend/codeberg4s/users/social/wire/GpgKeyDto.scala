package com.worxbend.codeberg4s.users.social.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.wire.Elements
import com.worxbend.codeberg4s.users.social.GpgKey
import com.worxbend.codeberg4s.users.social.GpgKeyEmail
import com.worxbend.codeberg4s.users.social.GpgKeyId
import com.worxbend.codeberg4s.users.social.OpenPgpKeyId

/** Forgejo's `GPGKeyEmail` model — an address a GPG key claims, and whether the instance confirmed it.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response''': every GPG endpoint requires a token and the
  * golden harvest was anonymous. Both declared properties are represented and both are `Option`, per rule 2 of
  * [[com.worxbend.codeberg4s.codec.WireConventions]] and `docs/HAZARDS.md` §1.
  *
  * @param email
  *   the `email` key
  * @param verified
  *   the `verified` key
  */
final case class GpgKeyEmailDto(email: Option[String], verified: Option[Boolean]):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `email` is required, because an entry that names no address says nothing: the whole content of the object is which
    * address the key claims. An absent `verified` reads as `false`, the answer that claims the least.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, GpgKeyEmail] =
    Wire
      .required(at, "email", email)
      .map(address => GpgKeyEmail(email = address, isVerified = verified.getOrElse(false)))

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, GpgKeyEmail] =
    toDomainAt(JsonPath.Root)

object GpgKeyEmailDto:

  /** Reads a `GPGKeyEmail` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[GpgKeyEmailDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so the field spellings exist in exactly one place. */
  def fromFields(fields: JsonFields): GpgKeyEmailDto =
    GpgKeyEmailDto(
      email    = fields.text("email"),
      verified = fields.boolean("verified"),
    )

/** Forgejo's `GPGKey` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response''': `/user/gpg_keys` answers `401` without a
  * token, `/users/{username}/gpg_keys` was not among the 61 anonymous captures, and the bodies in
  * `modules/codec/test/resources/golden` are all anonymous. The thirteen fields below are exactly the properties of
  * `definitions.GPGKey` in the pinned spec, and `docs/HAZARDS.md` §1 is why every one of them is `Option` anyway — the
  * spec declares no `required` list on any response model, so it is evidence of what keys may appear and of nothing
  * else.
  *
  * ==The model is recursive, and so is this==
  *
  * `subkeys` is an array of `GPGKey`, so a key's subkeys are read by this same DTO. That is the wire's own shape; a
  * reduced "subkey" type would be a shape the API never sends. Nothing here bounds the nesting depth, because the
  * parser has already parsed the document by the time [[fromFields]] runs — the depth a hostile payload could reach is
  * bounded by the parser, not by this projection.
  *
  * Timestamps stay as raw strings; [[com.worxbend.codeberg4s.codec.Timestamps]] normalises them, sentinels included,
  * during conversion. That is what turns the Go zero time Forgejo sends for a non-expiring key into an absent
  * `expiresAt` rather than into a date in the year 1.
  *
  * @param id
  *   the `id` key — the instance-local row number, not the OpenPGP identifier
  * @param primaryKeyId
  *   the `primary_key_id` key
  * @param keyId
  *   the `key_id` key — the OpenPGP long identifier
  * @param publicKey
  *   the `public_key` key, an armored public key block
  * @param emails
  *   the `emails` key
  * @param subkeys
  *   the `subkeys` key
  * @param canSign
  *   the `can_sign` key
  * @param canEncryptComms
  *   the `can_encrypt_comms` key
  * @param canEncryptStorage
  *   the `can_encrypt_storage` key
  * @param canCertify
  *   the `can_certify` key
  * @param verified
  *   the `verified` key
  * @param created
  *   the `created_at` key as a raw string
  * @param expires
  *   the `expires_at` key as a raw string
  */
final case class GpgKeyDto(
    id: Option[Long],
    primaryKeyId: Option[String],
    keyId: Option[String],
    publicKey: Option[String],
    emails: Vector[GpgKeyEmailDto],
    subkeys: Vector[GpgKeyDto],
    canSign: Option[Boolean],
    canEncryptComms: Option[Boolean],
    canEncryptStorage: Option[Boolean],
    canCertify: Option[Boolean],
    verified: Option[Boolean],
    created: Option[String],
    expires: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `id` is the one required field: it is what `/user/gpg_keys/{id}` addresses, and a key entry that cannot be
    * addressed can be neither read again nor deleted. It goes through
    * [[com.worxbend.codeberg4s.users.social.GpgKeyId.from]], so a non-positive one is reported at `$.id` rather than
    * carried.
    *
    * '''`key_id` and `primary_key_id` are lenient.''' Both are optional in the domain, so a value
    * [[com.worxbend.codeberg4s.users.social.OpenPgpKeyId.from]] rejects becomes `None` instead of failing the key — the
    * same trade [[com.worxbend.codeberg4s.issues.wire.RepositoryMetaDto.toSlug]] makes. A key whose identifier this
    * library will not carry is still a perfectly good key: it can be listed, read and deleted by its row id.
    *
    * '''`emails` and `subkeys` are not lenient.''' Each element is converted at its own path, so a failure says
    * `$.emails[1].email` or `$.subkeys[0].id`, and one bad element fails the key — the contract every list in this
    * library has, for the reason [[com.worxbend.codeberg4s.repositories.wire.Elements]] states.
    *
    * Every flag absent reads as `false`, which grants the fewest capabilities.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, GpgKey] =
    for
      identifier <- Wire.validated(at, "id", id)(GpgKeyId.from)
      addresses  <- Elements.convert(at.field("emails"), emails)((dto, path) => dto.toDomainAt(path))
      children   <- Elements.convert(at.field("subkeys"), subkeys)((dto, path) => dto.toDomainAt(path))
    yield GpgKey(
      id                = identifier,
      keyId             = keyId.flatMap(value => OpenPgpKeyId.from(value).toOption),
      primaryKeyId      = primaryKeyId.flatMap(value => OpenPgpKeyId.from(value).toOption),
      publicKey         = publicKey,
      emails            = addresses,
      subkeys           = children,
      canSign           = canSign.getOrElse(false),
      canEncryptComms   = canEncryptComms.getOrElse(false),
      canEncryptStorage = canEncryptStorage.getOrElse(false),
      canCertify        = canCertify.getOrElse(false),
      isVerified        = verified.getOrElse(false),
      createdAt         = Timestamps.parseOptional(created),
      expiresAt         = Timestamps.parseOptional(expires),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, GpgKey] =
    toDomainAt(JsonPath.Root)

object GpgKeyDto:

  /** Reads a `GPGKey` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[GpgKeyDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, so the field spellings exist in exactly one place.
    *
    * Recurses through `subkeys`, which is the same object shape one level down.
    */
  def fromFields(fields: JsonFields): GpgKeyDto =
    GpgKeyDto(
      id                = fields.number("id"),
      primaryKeyId      = fields.text("primary_key_id"),
      keyId             = fields.text("key_id"),
      publicKey         = fields.text("public_key"),
      emails            = fields.nestedAll("emails").map(GpgKeyEmailDto.fromFields),
      subkeys           = fields.nestedAll("subkeys").map(fromFields),
      canSign           = fields.boolean("can_sign"),
      canEncryptComms   = fields.boolean("can_encrypt_comms"),
      canEncryptStorage = fields.boolean("can_encrypt_storage"),
      canCertify        = fields.boolean("can_certify"),
      verified          = fields.boolean("verified"),
      created           = fields.text("created_at"),
      expires           = fields.text("expires_at"),
    )

  /** Converts a decoded array of keys, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[GpgKeyDto]): Either[DecodeFailure, Vector[GpgKey]] =
    Elements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
