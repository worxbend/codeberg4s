package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.actions.{ActionRunner, RunnerId, RunnerStatus}

/** Forgejo's `ActionRunner` model, field for field.
  *
  * '''Derived from the spec, not from a capture'''; see [[ActionArtifactDto]].
  *
  * `status` is one of the few Forgejo fields the spec enumerates — `offline`, `idle`, `active` — which is why
  * [[com.worxbend.codeberg4s.repositories.actions.RunnerStatus]] exists at all.
  */
final case class ActionRunnerDto(
    id: Option[Long],
    uuid: Option[String],
    name: Option[String],
    description: Option[String],
    status: Option[String],
    labels: Vector[String],
    ephemeral: Option[Boolean],
    ownerId: Option[Long],
    repoId: Option[Long],
) extends WireModel[ActionRunner]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Only `id` is required, and it is turned into a [[com.worxbend.codeberg4s.repositories.actions.RunnerId]] — a
    * string — because that is what the runner path takes; see that type for the argument. A runner without one could
    * not be read again or deleted.
    *
    * `owner_id` and `repo_id` are dropped when they are `0`, which is how the spec spells "this runner is not owned
    * that way". `status` that is outside the enumerated set becomes `None` rather than failing, per
    * [[com.worxbend.codeberg4s.repositories.actions.RunnerStatus.parse]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, ActionRunner] =
    Wire
      .required(at, "id", id)
      .map: identifier =>
        ActionRunner(
          id          = RunnerId.of(identifier),
          uuid        = uuid,
          name        = name,
          description = description,
          status      = status.flatMap(RunnerStatus.parse),
          labels      = labels,
          isEphemeral = ephemeral.getOrElse(false),
          ownerId     = ActionWire.identifier(ownerId),
          repoId      = ActionWire.identifier(repoId),
        )

object ActionRunnerDto:

  /** Reads an `ActionRunner` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ActionRunnerDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): ActionRunnerDto =
    ActionRunnerDto(
      id          = fields.number("id"),
      uuid        = fields.text("uuid"),
      name        = fields.text("name"),
      description = fields.text("description"),
      status      = fields.text("status"),
      labels      = ActionWire.strings(fields, "labels"),
      ephemeral   = fields.boolean("ephemeral"),
      ownerId     = fields.number("owner_id"),
      repoId      = fields.number("repo_id"),
    )
