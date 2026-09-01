package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.{Json, WireModel}
import com.worxbend.codeberg4s.core.{Decode, ResponseBody}
import com.worxbend.codeberg4s.miscellaneous.PlainText
import com.worxbend.codeberg4s.repositories.actions.wire.{
  ActionArtifactDto,
  ActionRunDto,
  ActionTaskDto,
  DispatchedWorkflowRunDto,
  WorkflowRunsEnvelopeDto
}

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
    WireDecode.single(Json.decoder[ActionArtifactDto])(_.toDomain)

  /** A bare array of artifact objects, as both artifact listings return it. */
  val artifacts: Decode[Vector[ActionArtifact]] =
    WireDecode.vector(Json.decoder[Vector[ActionArtifactDto]])

  /** One run object. */
  val run: Decode[ActionRun] =
    WireDecode.single(Json.decoder[ActionRunDto])(_.toDomain)

  /** The `{"total_count", "workflow_runs"}` envelope the run listing returns, unwrapped to its runs. */
  val runs: Decode[Vector[ActionRun]] =
    WireDecode.single(Json.decoder[WorkflowRunsEnvelopeDto[ActionRunDto]]): envelope =>
      WireModel.all(RepositoryActionDecoders.EntriesPath, envelope.entries)

  /** The same envelope as [[runs]], carrying tasks. The key is `workflow_runs` there too; see the envelope's note. */
  val tasks: Decode[Vector[ActionTask]] =
    WireDecode.single(Json.decoder[WorkflowRunsEnvelopeDto[ActionTaskDto]]): envelope =>
      WireModel.all(RepositoryActionDecoders.EntriesPath, envelope.entries)

  /** Every shape the organisation and account Actions surfaces answer too, decoded by [[ActionDecoders]].
    *
    * These are re-exported rather than re-derived so that this object stays the one decoder table
    * [[RepositoryActionApi]] reads, while the definitions — including the
    * [[com.worxbend.codeberg4s.core.Decode.sensitive]] marking on the two credential-carrying shapes — exist once.
    */
  export ActionDecoders.{jobs, registeredRunner, registrationToken, runner, runners, secrets, variable, variables}

  /** The dispatch acknowledgement, which is present only when the request asked for it.
    *
    * '''An empty body is a success here, not a decoding failure.''' The endpoint answers `204` with nothing unless
    * `return_run_info` was set, and both outcomes reach this decoder because both are `2xx`. Treating the empty body as
    * `None` is what lets one method serve both, rather than the caller having to know which status they will get before
    * they call. Anything non-blank is decoded as a run description, so an instance that answers `204` with whitespace
    * is still understood and one that answers with a malformed body still fails.
    */
  val dispatchedRun: Decode[Option[DispatchedWorkflowRun]] =
    val present = WireDecode.single(Json.decoder[DispatchedWorkflowRunDto])(_.toDomain)

    (body: ResponseBody) => if body.isBlank then Right(None) else present(body).map(Some.apply)

  /** A job's log, exactly as the instance sent it.
    *
    * Never parsed. `GET /repos/{owner}/{repo}/actions/jobs/{job_id}/logs` produces `text/plain`, and running a log
    * through a JSON parser would turn a perfectly good response into a decoding failure — see
    * [[com.worxbend.codeberg4s.miscellaneous.PlainText]].
    */
  val jobLog: Decode[String] =
    PlainText.decoder
