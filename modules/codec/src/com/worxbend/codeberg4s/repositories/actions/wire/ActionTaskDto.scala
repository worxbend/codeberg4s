package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.ArrayElements
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.actions.ActionTask
import com.worxbend.codeberg4s.repositories.actions.TaskId

/** Forgejo's `ActionTask` model, field for field.
  *
  * '''Derived from the spec, not from a capture'''; see [[ActionArtifactDto]].
  */
final case class ActionTaskDto(
    id: Option[Long],
    name: Option[String],
    status: Option[String],
    workflowId: Option[String],
    headBranch: Option[String],
    headSha: Option[String],
    event: Option[String],
    runNumber: Option[Long],
    url: Option[String],
    displayTitle: Option[String],
    runStartedAt: Option[String],
    createdAt: Option[String],
    updatedAt: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Only `id` is required. `head_sha` and `workflow_id` are dropped when they fail their smart constructors rather
    * than failing the task, and every timestamp goes through [[com.worxbend.codeberg4s.codec.Timestamps]], where
    * Forgejo's zero-time sentinel is folded into absence — which is what a task that has not started yet reports for
    * `run_started_at`.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, ActionTask] =
    Wire
      .validated(at, "id", id)(TaskId.from)
      .map: identifier =>
        ActionTask(
          id           = identifier,
          name         = name,
          status       = ActionWire.status(status),
          workflowId   = ActionWire.workflow(workflowId),
          headBranch   = headBranch,
          headSha      = ActionWire.commit(headSha),
          event        = event,
          runNumber    = runNumber,
          url          = url,
          displayTitle = displayTitle,
          runStartedAt = Timestamps.parseOptional(runStartedAt),
          createdAt    = Timestamps.parseOptional(createdAt),
          updatedAt    = Timestamps.parseOptional(updatedAt),
        )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, ActionTask] =
    toDomainAt(JsonPath.Root)

object ActionTaskDto:

  /** Reads an `ActionTask` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ActionTaskDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): ActionTaskDto =
    ActionTaskDto(
      id           = fields.number("id"),
      name         = fields.text("name"),
      status       = fields.text("status"),
      workflowId   = fields.text("workflow_id"),
      headBranch   = fields.text("head_branch"),
      headSha      = fields.text("head_sha"),
      event        = fields.text("event"),
      runNumber    = fields.number("run_number"),
      url          = fields.text("url"),
      displayTitle = fields.text("display_title"),
      runStartedAt = fields.text("run_started_at"),
      createdAt    = fields.text("created_at"),
      updatedAt    = fields.text("updated_at"),
    )

  /** Converts a decoded array of tasks, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[ActionTaskDto]): Either[DecodeFailure, Vector[ActionTask]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
