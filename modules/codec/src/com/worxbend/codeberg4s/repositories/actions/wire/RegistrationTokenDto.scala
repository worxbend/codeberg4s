package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.actions.RunnerRegistrationToken

/** Forgejo's `RegistrationToken` model — a one-key object wrapping the credential a runner registers with.
  *
  * '''Derived from the spec, not from a capture'''; see [[ActionArtifactDto]].
  *
  * Same handling note as [[RegisteredRunnerDto]]: the raw string lives here only long enough to become a
  * [[com.worxbend.codeberg4s.repositories.actions.RunnerRegistrationToken]], which masks itself.
  */
final case class RegistrationTokenDto(token: Option[String]):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `token` is required, because the object has nothing else in it: a response without one is a failure to report at
    * `$.token`, not a token-shaped absence to hand a caller.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, RunnerRegistrationToken] =
    Wire.validated(at, "token", token)(RunnerRegistrationToken.from)

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, RunnerRegistrationToken] =
    toDomainAt(JsonPath.Root)

object RegistrationTokenDto:

  /** Reads a `RegistrationToken` object. Absent and `null` are the same thing; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[RegistrationTokenDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): RegistrationTokenDto =
    RegistrationTokenDto(token = fields.text("token"))
