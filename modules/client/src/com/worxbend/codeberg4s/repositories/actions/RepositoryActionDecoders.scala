package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.core.ResponseBody
import com.worxbend.codeberg4s.miscellaneous.PlainText
import com.worxbend.codeberg4s.repositories.actions.wire.ActionArtifactDto
import com.worxbend.codeberg4s.repositories.actions.wire.ActionRunDto
import com.worxbend.codeberg4s.repositories.actions.wire.ActionRunJobDto
import com.worxbend.codeberg4s.repositories.actions.wire.ActionRunnerDto
import com.worxbend.codeberg4s.repositories.actions.wire.ActionSecretDto
import com.worxbend.codeberg4s.repositories.actions.wire.ActionTaskDto
import com.worxbend.codeberg4s.repositories.actions.wire.ActionVariableDto
import com.worxbend.codeberg4s.repositories.actions.wire.DispatchedWorkflowRunDto
import com.worxbend.codeberg4s.repositories.actions.wire.RegisteredRunnerDto
import com.worxbend.codeberg4s.repositories.actions.wire.RegistrationTokenDto
import com.worxbend.codeberg4s.repositories.actions.wire.WorkflowRunsEnvelopeDto

/** Every response shape [[RepositoryActionApi]] can receive, decoded once and shared.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call, exactly as
  * [[com.worxbend.codeberg4s.repositories.RepositoryDecoders]] does.
  *
  * ==Three envelope shapes, and one of them is not JSON==
  *
  * Most listings here are the bare JSON array the rest of the API returns. The run and task listings are not: they wrap
  * their results in `{"total_count", "workflow_runs"}` — see
  * [[com.worxbend.codeberg4s.repositories.actions.wire.WorkflowRunsEnvelopeDto]] — which is why their elements are
  * reported at `$.workflow_runs[n]` and not at `$[n]`. And the job-logs endpoint answers plain text, so it is read by
  * [[com.worxbend.codeberg4s.miscellaneous.PlainText]] and never by a JSON parser.
  */
private[actions] object RepositoryActionDecoders:

  /** Where the elements of a `{"total_count", "workflow_runs"}` envelope sit, so a bad element reports
    * `$.workflow_runs[2].id` rather than `$[2].id`.
    */
  private val EntriesPath: JsonPath =
    JsonPath.Root.field(WorkflowRunsEnvelopeDto.EntriesKey)

  /** One artifact object. */
  val artifact: Decode[ActionArtifact] =
    WireDecode.of(Json.decoder[ActionArtifactDto])(_.toDomain)

  /** A bare array of artifact objects, as both artifact listings return it. */
  val artifacts: Decode[Vector[ActionArtifact]] =
    WireDecode.of(Json.decoder[Vector[ActionArtifactDto]])(dtos => ActionArtifactDto.toDomainAll(JsonPath.Root, dtos))

  /** One run object. */
  val run: Decode[ActionRun] =
    WireDecode.of(Json.decoder[ActionRunDto])(_.toDomain)

  /** The `{"total_count", "workflow_runs"}` envelope the run listing returns, unwrapped to its runs. */
  val runs: Decode[Vector[ActionRun]] =
    WireDecode.of(Json.decoder[WorkflowRunsEnvelopeDto[ActionRunDto]]): envelope =>
      ActionRunDto.toDomainAll(RepositoryActionDecoders.EntriesPath, envelope.entries)

  /** A bare array of job objects, as both the run's job listing and the runner job search return it. */
  val jobs: Decode[Vector[ActionRunJob]] =
    WireDecode.of(Json.decoder[Vector[ActionRunJobDto]])(dtos => ActionRunJobDto.toDomainAll(JsonPath.Root, dtos))

  /** The same envelope as [[runs]], carrying tasks. The key is `workflow_runs` there too; see the envelope's note. */
  val tasks: Decode[Vector[ActionTask]] =
    WireDecode.of(Json.decoder[WorkflowRunsEnvelopeDto[ActionTaskDto]]): envelope =>
      ActionTaskDto.toDomainAll(RepositoryActionDecoders.EntriesPath, envelope.entries)

  /** One runner object. */
  val runner: Decode[ActionRunner] =
    WireDecode.of(Json.decoder[ActionRunnerDto])(_.toDomain)

  /** A bare array of runner objects. */
  val runners: Decode[Vector[ActionRunner]] =
    WireDecode.of(Json.decoder[Vector[ActionRunnerDto]])(dtos => ActionRunnerDto.toDomainAll(JsonPath.Root, dtos))

  /** The `{id, uuid, token}` object a runner registration returns. */
  val registeredRunner: Decode[RegisteredRunner] =
    WireDecode.of(Json.decoder[RegisteredRunnerDto])(_.toDomain)

  /** The one-key object the registration-token endpoint returns. */
  val registrationToken: Decode[RunnerRegistrationToken] =
    WireDecode.of(Json.decoder[RegistrationTokenDto])(_.toDomain)

  /** A bare array of secret objects — names and timestamps, never values. */
  val secrets: Decode[Vector[ActionSecret]] =
    WireDecode.of(Json.decoder[Vector[ActionSecretDto]])(dtos => ActionSecretDto.toDomainAll(JsonPath.Root, dtos))

  /** One variable object. */
  val variable: Decode[ActionVariable] =
    WireDecode.of(Json.decoder[ActionVariableDto])(_.toDomain)

  /** A bare array of variable objects. */
  val variables: Decode[Vector[ActionVariable]] =
    WireDecode.of(Json.decoder[Vector[ActionVariableDto]])(dtos => ActionVariableDto.toDomainAll(JsonPath.Root, dtos))

  /** The dispatch acknowledgement, which is present only when the request asked for it.
    *
    * '''An empty body is a success here, not a decoding failure.''' The endpoint answers `204` with nothing unless
    * `return_run_info` was set, and both outcomes reach this decoder because both are `2xx`. Treating the empty body as
    * `None` is what lets one method serve both, rather than the caller having to know which status they will get before
    * they call. Anything non-blank is decoded as a run description, so an instance that answers `204` with whitespace
    * is still understood and one that answers with a malformed body still fails.
    */
  val dispatchedRun: Decode[Option[DispatchedWorkflowRun]] =
    val present = WireDecode.of(Json.decoder[DispatchedWorkflowRunDto])(_.toDomain)

    (body: ResponseBody) => if body.isBlank then Right(None) else present(body).map(Some.apply)

  /** A job's log, exactly as the instance sent it.
    *
    * Never parsed. `GET /repos/{owner}/{repo}/actions/jobs/{job_id}/logs` produces `text/plain`, and running a log
    * through a JSON parser would turn a perfectly good response into a decoding failure — see
    * [[com.worxbend.codeberg4s.miscellaneous.PlainText]].
    */
  val jobLog: Decode[String] =
    PlainText.decoder
