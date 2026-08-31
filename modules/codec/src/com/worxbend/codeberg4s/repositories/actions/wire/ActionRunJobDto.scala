package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{ArrayElements, JsonDecoder, JsonFields, Wire}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.actions.{ActionRunJob, JobAttempt, JobId, RunId}

/** Forgejo's `ActionRunJob` model, field for field.
  *
  * '''Derived from the spec, not from a capture'''; see [[ActionArtifactDto]].
  *
  * The model carries no timestamps at all — not a creation time, not a start time — which is why nothing here goes
  * through [[com.worxbend.codeberg4s.codec.Timestamps]]. A caller who needs to know when a job ran reads the run it
  * belongs to, or the task listing.
  */
final case class ActionRunJobDto(
    id: Option[Long],
    name: Option[String],
    runId: Option[Long],
    status: Option[String],
    needs: Vector[String],
    runsOn: Vector[String],
    attempt: Option[Long],
    taskId: Option[Long],
    handle: Option[String],
    ownerId: Option[Long],
    repoId: Option[Long],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Only `id` is required: it is what the job-logs endpoint takes. `needs` and `runs_on` are declared as arrays and
    * are read as such, which — per `docs/HAZARDS.md` §1 — means a JSON `null` in either position becomes an empty
    * `Vector` rather than aborting the read.
    *
    * `attempt` goes through [[com.worxbend.codeberg4s.repositories.actions.JobAttempt]] and is dropped when it is not a
    * positive number, because `0` would be a value the logs endpoint rejects. `owner_id` and `repo_id` are dropped when
    * they are `0`, which is Forgejo's spelling of "not this kind of owner" — see
    * [[com.worxbend.codeberg4s.repositories.actions.wire.ActionWire.identifier]].
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, ActionRunJob] =
    Wire
      .validated(at, "id", id)(JobId.from)
      .map: identifier =>
        ActionRunJob(
          id      = identifier,
          name    = name,
          runId   = runId.flatMap(value => RunId.from(value).toOption),
          status  = ActionWire.status(status),
          needs   = needs,
          runsOn  = runsOn,
          attempt = attempt.flatMap(value => JobAttempt.from(value).toOption),
          taskId  = ActionWire.identifier(taskId),
          handle  = handle,
          ownerId = ActionWire.identifier(ownerId),
          repoId  = ActionWire.identifier(repoId),
        )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, ActionRunJob] =
    toDomainAt(JsonPath.Root)

object ActionRunJobDto:

  /** Reads an `ActionRunJob` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ActionRunJobDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): ActionRunJobDto =
    ActionRunJobDto(
      id      = fields.number("id"),
      name    = fields.text("name"),
      runId   = fields.number("run_id"),
      status  = fields.text("status"),
      needs   = ActionWire.strings(fields, "needs"),
      runsOn  = ActionWire.strings(fields, "runs_on"),
      attempt = fields.number("attempt"),
      taskId  = fields.number("task_id"),
      handle  = fields.text("handle"),
      ownerId = fields.number("owner_id"),
      repoId  = fields.number("repo_id"),
    )

  /** Converts a decoded array of jobs, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[ActionRunJobDto]): Either[DecodeFailure, Vector[ActionRunJob]] =
    ArrayElements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))
