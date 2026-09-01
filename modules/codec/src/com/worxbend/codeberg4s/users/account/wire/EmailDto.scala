package com.worxbend.codeberg4s.users.account.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.users.account.{Email, EmailAddress}

/** Forgejo's `Email` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' `/user/emails` requires a token and the golden
  * harvest was anonymous, so no fixture exists for this shape.
  *
  * The `email` property is declared `format: email`, which Swagger 2.0 attaches no validation to — the shape is
  * enforced by [[com.worxbend.codeberg4s.users.account.EmailAddress.from]] on the way into the domain, and an address
  * the instance sends that this library cannot read fails the whole listing rather than being silently dropped. That is
  * the right direction to be wrong in: a page that quietly lost an address would make "is this address already on the
  * account" answerable incorrectly.
  */
final case class EmailDto(
    email: Option[String],
    primary: Option[Boolean],
    verified: Option[Boolean],
    userId: Option[Long],
    username: Option[String],
) extends WireModel[Email]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `email` is the one required field, because it is the identifier: both mutating endpoints name an address and
    * neither takes an id, so an entry without one describes nothing a caller could act on. `primary` and `verified`
    * default to `false` when absent, per the note on [[com.worxbend.codeberg4s.users.account.Email]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, Email] =
    Wire
      .validated(at, "email", email)(EmailAddress.from)
      .map: address =>
        Email(
          address    = address,
          isPrimary  = primary.getOrElse(false),
          isVerified = verified.getOrElse(false),
          userId     = userId,
          username   = username,
        )

object EmailDto:

  /** Reads an `Email` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[EmailDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): EmailDto =
    EmailDto(
      email    = fields.text("email"),
      primary  = fields.boolean("primary"),
      verified = fields.boolean("verified"),
      userId   = fields.number("user_id"),
      username = fields.text("username"),
    )

  /** Converts a decoded array of addresses, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[EmailDto]): Either[DecodeFailure, Vector[Email]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
