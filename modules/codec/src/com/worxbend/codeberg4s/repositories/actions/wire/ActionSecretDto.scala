package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Timestamps, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.actions.{ActionSecret, SecretName}

/** Forgejo's `Secret` model — two keys, and neither of them is the secret.
  *
  * '''Derived from the spec, not from a capture'''; see [[ActionArtifactDto]].
  *
  * The definition in `spec/swagger.v1.json` declares exactly `name` and `created_at`. There is no `data`, no `value`
  * and no redacted placeholder, on this endpoint or any other: the material is write-only. This DTO therefore has no
  * field for it either — adding one "for symmetry" with
  * [[com.worxbend.codeberg4s.repositories.actions.wire.SecretOptionDto]] would be a field that is always `None` and an
  * invitation to look for the value somewhere else.
  */
final case class ActionSecretDto(
    name: Option[String],
    createdAt: Option[String],
) extends WireModel[ActionSecret]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * `name` is required and goes through [[com.worxbend.codeberg4s.repositories.actions.SecretName.from]], because it
    * is the only thing that addresses the secret — a nameless secret cannot be updated or deleted. `created_at` goes
    * through [[com.worxbend.codeberg4s.codec.Timestamps]], where the zero-time sentinel is folded into absence.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, ActionSecret] =
    Wire
      .validated(at, "name", name)(SecretName.from)
      .map(secret => ActionSecret(name = secret, createdAt = Timestamps.parseOptional(createdAt)))

object ActionSecretDto:

  /** Reads a `Secret` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ActionSecretDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): ActionSecretDto =
    ActionSecretDto(
      name      = fields.text("name"),
      createdAt = fields.text("created_at"),
    )
