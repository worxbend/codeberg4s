package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.actions.{RegisteredRunner, RunnerId, RunnerRegistrationToken}

/** Forgejo's `RegisterRunnerResponse` model — what a successful runner registration hands back.
  *
  * '''Derived from the spec, not from a capture'''; see [[ActionArtifactDto]].
  *
  * '''This DTO holds a credential in the clear.''' `token` stays a raw `String` here, because a DTO is the shape of the
  * wire and the wire carries a string; it becomes a
  * [[com.worxbend.codeberg4s.repositories.actions.RunnerRegistrationToken]] — which masks itself — in conversion, and
  * nothing between the two logs, renders or copies it. The DTO is `private[codeberg4s]` in effect: no caller of this
  * library ever holds one.
  */
final case class RegisteredRunnerDto(
    id: Option[Long],
    uuid: Option[String],
    token: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `token` is the one required field, and it is required for a reason worth stating: the entire point of the call is
    * to obtain it, and a `RegisteredRunner` with an absent token would be a value whose only use is to be checked for
    * emptiness. A blank token is rejected by
    * [[com.worxbend.codeberg4s.repositories.actions.RunnerRegistrationToken.from]] and reported at `$.token`.
    *
    * `id` is optional and becomes a [[com.worxbend.codeberg4s.repositories.actions.RunnerId]] when present, per the
    * argument on that type.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, RegisteredRunner] =
    Wire
      .validated(at, "token", token)(RunnerRegistrationToken.from)
      .map: credential =>
        RegisteredRunner(
          id    = id.map(RunnerId.of),
          uuid  = uuid,
          token = credential,
        )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, RegisteredRunner] =
    toDomainAt(JsonPath.Root)

object RegisteredRunnerDto:

  /** Reads a `RegisterRunnerResponse` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[RegisteredRunnerDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): RegisteredRunnerDto =
    RegisteredRunnerDto(
      id    = fields.number("id"),
      uuid  = fields.text("uuid"),
      token = fields.text("token"),
    )
