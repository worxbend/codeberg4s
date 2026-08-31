package com.worxbend.codeberg4s.users.account.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.ArrayElements
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.users.account.ClientSecret
import com.worxbend.codeberg4s.users.account.OAuth2Application
import com.worxbend.codeberg4s.users.account.OAuth2ApplicationId

/** Forgejo's `OAuth2Application` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a capture.''' Every `/user/applications/oauth2` route requires a
  * token and the golden harvest behind `modules/codec/test/resources/golden` was anonymous, so no fixture exists for
  * this shape; the field set below is the spec's `OAuth2Application` definition read literally.
  *
  * ==This DTO holds a credential in the clear, for as long as a conversion takes==
  *
  * `clientSecret` is a raw `String` here, because a DTO is the shape of the wire and the wire carries a string. It
  * becomes a [[com.worxbend.codeberg4s.users.account.ClientSecret]] — which masks itself — in [[toDomainAt]], and
  * nothing between the two logs, renders or copies it. The same discipline
  * [[com.worxbend.codeberg4s.repositories.actions.wire.RegisteredRunnerDto]] applies to a registration token.
  *
  * '''The key is absent on every read.''' Only the `201` of `POST /user/applications/oauth2` carries `client_secret`;
  * the single-application read and the listing do not, because Forgejo stores it hashed. That is why the field is
  * optional in the domain model too — see [[com.worxbend.codeberg4s.users.account.OAuth2Application.clientSecret]].
  */
final case class OAuth2ApplicationDto(
    id: Option[Long],
    name: Option[String],
    clientId: Option[String],
    clientSecret: Option[String],
    redirectUris: Vector[String],
    confidentialClient: Option[Boolean],
    created: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `id` is the only required field, because it is the only thing that addresses the application: one that cannot be
    * named cannot be read again, updated or deleted, so a payload without one is a decoding failure rather than an
    * application with a hole in it. Everything else is absence-tolerant, per rule 2 of
    * [[com.worxbend.codeberg4s.codec.WireConventions]].
    *
    * '''A blank `client_secret` becomes absence, not a failure.''' The value goes through
    * [[com.worxbend.codeberg4s.users.account.ClientSecret.from]], which refuses a blank one — and a refusal here must
    * not cost the caller the application, because an instance that echoes `"client_secret": ""` on a read is saying
    * "there is no secret in this response", which is exactly what `None` means. A creation that genuinely produced no
    * usable secret is therefore reported as an application without one;
    * [[com.worxbend.codeberg4s.users.account.OAuth2Application.carriesSecret]] is how a caller checks.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, OAuth2Application] =
    Wire
      .validated(at, "id", id)(OAuth2ApplicationId.from)
      .map: identifier =>
        OAuth2Application(
          id                   = identifier,
          name                 = name,
          clientId             = clientId,
          clientSecret         = clientSecret.flatMap(secret => ClientSecret.from(secret).toOption),
          redirectUris         = redirectUris,
          isConfidentialClient = confidentialClient.getOrElse(false),
          createdAt            = Timestamps.parseOptional(created),
        )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, OAuth2Application] =
    toDomainAt(JsonPath.Root)

object OAuth2ApplicationDto:

  /** Reads an `OAuth2Application` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[OAuth2ApplicationDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): OAuth2ApplicationDto =
    OAuth2ApplicationDto(
      id                 = fields.number("id"),
      name               = fields.text("name"),
      clientId           = fields.text("client_id"),
      clientSecret       = fields.text("client_secret"),
      redirectUris       = fields.texts("redirect_uris"),
      confidentialClient = fields.boolean("confidential_client"),
      created            = fields.text("created"),
    )

  /** Converts a decoded array of applications, reporting the position of whichever element failed. */
  def toDomainAll(
      base: JsonPath,
      dtos: Vector[OAuth2ApplicationDto],
  ): Either[DecodeFailure, Vector[OAuth2Application]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
