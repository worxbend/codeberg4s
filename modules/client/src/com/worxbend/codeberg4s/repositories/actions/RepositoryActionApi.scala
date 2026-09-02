package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.core.CodebergRequest.{bodiless, read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, CodebergResponse, Exec, RetryEligibility}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.repositories.actions.ActionRequests.actionsPath
import com.worxbend.codeberg4s.repositories.actions.wire.{ActionQueries, DispatchWorkflowOptionDto}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName}

import scala.concurrent.Future

/** A repository's Actions surface: runs, the jobs and tasks that make them up, and the artifacts they produce.
  *
  * The runners that execute them and the secrets and variables they read are on [[config]], a group of its own: those
  * exist before a run does and outlive it, while everything here is the record of runs that happened.
  *
  * Reached as `client.repos.actions`. Both error rails are here (ADR-0005): the methods on this class fail the `Future`
  * with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[RepositoryActionApi.attempt]] never
  * fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Evidence==
  *
  * '''Every model in this group is derived from `spec/swagger.v1.json`, not from a captured response.''' The harvest
  * behind `modules/codec/test/resources/golden` was anonymous and every endpoint here requires a token, so no fixture
  * exists for any of them. The field sets, the envelope shapes and the status vocabulary are the spec read literally;
  * the nullability treatment is the conservative one `docs/HAZARDS.md` §1 mandates for the whole API. Where a shape is
  * asserted in a test, the payload was written by hand to match that definition — it is not evidence that Forgejo sends
  * exactly this.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository does not exist '''or''' is
  *     private to credentials the client does not have — Forgejo does not distinguish the two, on purpose — `401` when
  *     a token was required and none was sent, and `403` when the token lacks the scope. Actions endpoints are
  *     token-only in practice even where `spec/swagger.v1.json` marks security as optional, and `403` is also what a
  *     repository with Actions disabled answers. `422` '''and''' `400` both mean the request was rejected as invalid;
  *     `docs/HAZARDS.md` §4 records Forgejo using `400` where a reader would expect `422`.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type, so a value that would forge a path or a query parameter is rejected by its own smart
  * constructor before a client is ever involved.
  *
  * ==Retries==
  *
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. Writes are decided per endpoint and each
  * decision is justified where it is made:
  *
  *   - every `POST` uses [[com.worxbend.codeberg4s.core.RetryEligibility.Never]]. Forgejo offers no idempotency key, so
  *     a repeated registration produces a second runner and a repeated dispatch produces a second run;
  *   - `PUT` of a secret, and `PUT` of a variable that does not rename it, use
  *     [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]]: they set a named resource to a stated value, so
  *     doing it twice leaves the instance in the state doing it once would have;
  *   - every `DELETE` uses [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]] for the same reason, with one
  *     consequence a caller has to know: if the first attempt succeeded and its response was lost, the retry addresses
  *     something that no longer exists and answers `404`. A `404` from a delete therefore means "it is gone", not
  *     necessarily "it was never there".
  *
  * ==Secrets==
  *
  * A secret's value goes in and never comes out. [[setSecret]] takes a [[SecretValue]], which masks itself in every
  * rendering path, and the read model [[ActionSecret]] has no value field at all — see both types for why that is
  * modelled rather than documented.
  *
  * ==The two archive endpoints==
  *
  * Two operations here answer a ZIP rather than JSON, and hand the body back undecoded:
  *
  *   - [[downloadArtifact]] — `GET /repos/{owner}/{repo}/actions/artifacts/{artifact_id}/zip`;
  *   - [[downloadRunLogs]] — `GET /repos/{owner}/{repo}/actions/runs/{run_id}/logs`.
  *
  * Both answer a [[com.worxbend.codeberg4s.core.CodebergResponse]] whose `body.bytes` is the archive verbatim, and both
  * hold the whole thing in memory — this library does not stream. They read under
  * [[com.worxbend.codeberg4s.CodebergConfig.maxDownloadBodyBytes]], 50 MiB by default rather than the 16 MiB every
  * other operation gets, because an artifact is whatever a workflow uploaded. An archive past the bound fails as
  * [[com.worxbend.codeberg4s.TransportCause.ResponseTooLarge]] and is not retried, since a second attempt would
  * download it again. [[ActionArtifact.archiveDownloadUrl]] remains available for a caller who would rather stream the
  * archive with their own HTTP client. [[jobLogs]] is genuinely text and needs no archive handling at all.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class RepositoryActionApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryActionApi.Attempt = RepositoryActionApi.Attempt(this)

  /** The runners, secrets and variables a run needs before it can happen. */
  val config: RepositoryActionConfigApi = RepositoryActionConfigApi(pipeline)

  // --- artifacts ------------------------------------------------------------

  /** Lists a repository's artifacts — `GET /repos/{owner}/{repo}/actions/artifacts`.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5). A page past
    * the end is `200` with `[]`, not a `404`.
    *
    * '''Failures.''' The group contract above.
    *
    * @param query
    *   the artifact-name filter; [[ArtifactQuery.Empty]] asks for all of them
    */
  def artifacts(
      owner: Owner,
      name: RepoName,
      query: ArtifactQuery,
      params: PageParams,
  ): Future[Page[ActionArtifact]] =
    pipeline.callPage(RepositoryActionApi.artifactsRequest(owner, name, query, params), params)(using
      RepositoryActionDecoders.artifacts)

  /** Reads one artifact's metadata — `GET /repos/{owner}/{repo}/actions/artifacts/{artifact_id}`.
    *
    * '''This does not download anything.''' See the class note on what is missing and why
    * [[ActionArtifact.archiveDownloadUrl]] is the supported route.
    *
    * '''Failures.''' The group contract above.
    */
  def artifact(owner: Owner, name: RepoName, id: ArtifactId): Future[ActionArtifact] =
    pipeline.call(RepositoryActionApi.artifactRequest(owner, name, id), RetryEligibility.IdempotentOnly)(using
      RepositoryActionDecoders.artifact)

  /** Deletes an artifact — `DELETE /repos/{owner}/{repo}/actions/artifacts/{artifact_id}`.
    *
    * '''Retried''', because deleting a resource named by its own identifier is idempotent: doing it twice leaves the
    * instance where doing it once would have. The cost is stated in the class note — a retry after a lost success
    * answers `404`.
    *
    * '''Answers `204`''', which the spec describes as "artifact marked for deletion": the bytes may be removed
    * asynchronously, so an artifact can still appear in a listing briefly after this returns.
    *
    * '''Failures.''' The group contract above.
    */
  def deleteArtifact(owner: Owner, name: RepoName, id: ArtifactId): Future[Unit] =
    pipeline.callUnit(RepositoryActionApi.deleteArtifactRequest(owner, name, id), RetryEligibility.AlwaysRetry)

  /** Downloads an artifact's ZIP — `GET /repos/{owner}/{repo}/actions/artifacts/{artifact_id}/zip`.
    *
    * '''Bytes, not a model.''' The returned [[com.worxbend.codeberg4s.core.CodebergResponse]] carries the archive
    * verbatim — read `response.body.bytes` — along with the response headers, so `content-disposition` is there for the
    * server's own file name. Nothing is decoded, so no [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] is
    * possible. See the class note on the two archive endpoints for the memory cost and the larger body bound.
    *
    * '''Failures.''' The group contract above, plus `410` when Forgejo has garbage-collected the artifact.
    */
  def downloadArtifact(owner: Owner, name: RepoName, id: ArtifactId): Future[CodebergResponse] =
    pipeline.callDownload(RepositoryActionApi.downloadArtifactRequest(owner, name, id))

  // --- runs -----------------------------------------------------------------

  /** Lists a repository's workflow runs — `GET /repos/{owner}/{repo}/actions/runs`.
    *
    * '''The body is an envelope, not an array.''' This endpoint answers `{"total_count", "workflow_runs"}`, unlike
    * every other listing in this group; see
    * [[com.worxbend.codeberg4s.repositories.actions.wire.WorkflowRunsEnvelopeDto]]. The body's own `total_count` is
    * '''not''' what [[com.worxbend.codeberg4s.paging.Page.totalCount]] reports — that comes from the `X-Total-Count`
    * header, as it does for every other paged call.
    *
    * '''Paging.''' As [[artifacts]]: the `Link` header decides, not the number of items.
    *
    * '''Failures.''' The group contract above.
    *
    * @param query
    *   the filters to apply; [[ActionRunQuery.Empty]] asks for every run
    */
  def runs(owner: Owner, name: RepoName, query: ActionRunQuery, params: PageParams): Future[Page[ActionRun]] =
    pipeline.callPage(RepositoryActionApi.runsRequest(owner, name, query, params), params)(using
      RepositoryActionDecoders.runs)

  /** Reads one run — `GET /repos/{owner}/{repo}/actions/runs/{run_id}`.
    *
    * '''Failures.''' The group contract above. `404` covers both "no such run in this repository" and "no such
    * repository".
    *
    * @param id
    *   the run's instance-wide identifier, '''not''' [[ActionRun.indexInRepo]] — see [[RunId]]
    */
  def run(owner: Owner, name: RepoName, id: RunId): Future[ActionRun] =
    pipeline.call(RepositoryActionApi.runRequest(owner, name, id), RetryEligibility.IdempotentOnly)(using
      RepositoryActionDecoders.run)

  /** Deletes a run and everything it produced — `DELETE /repos/{owner}/{repo}/actions/runs/{run_id}`.
    *
    * '''Retried''', for the reason [[deleteArtifact]] gives, and with the same `404`-after-a-lost-success consequence.
    *
    * '''Failures.''' The group contract above. Forgejo refuses to remove a run that has not finished, which arrives as
    * a `400`.
    */
  def deleteRun(owner: Owner, name: RepoName, id: RunId): Future[Unit] =
    pipeline.callUnit(RepositoryActionApi.deleteRunRequest(owner, name, id), RetryEligibility.AlwaysRetry)

  /** Cancels a run — `POST /repos/{owner}/{repo}/actions/runs/{run_id}/cancel`.
    *
    * '''Never retried''', even though cancelling twice is harmless: this is a `POST`, Forgejo offers no idempotency
    * key, and this library does not decide on a caller's behalf that repeating a mutating call is safe. A caller who
    * knows their cancel is safe to repeat can re-issue it themselves — the second one answers `400` if the run has
    * already stopped, which is information rather than damage.
    *
    * '''Answers `204`.''' Cancellation is a request to the runner, not an instant state change: the run reaches
    * [[ActionStatus.Cancelled]] some time after this returns, and [[run]] is how a caller finds out that it has.
    *
    * '''Failures.''' The group contract above.
    */
  def cancelRun(owner: Owner, name: RepoName, id: RunId): Future[Unit] =
    pipeline.callUnit(RepositoryActionApi.cancelRunRequest(owner, name, id), RetryEligibility.Never)

  /** Lists the artifacts one run produced — `GET /repos/{owner}/{repo}/actions/runs/{run_id}/artifacts`.
    *
    * The same model and the same filter as [[artifacts]], scoped to one run. A bare array here, not the envelope
    * [[runs]] returns.
    *
    * '''Failures.''' The group contract above.
    */
  def runArtifacts(
      owner: Owner,
      name: RepoName,
      id: RunId,
      query: ArtifactQuery,
      params: PageParams,
  ): Future[Page[ActionArtifact]] =
    pipeline.callPage(RepositoryActionApi.runArtifactsRequest(owner, name, id, query, params), params)(using
      RepositoryActionDecoders.artifacts)

  /** Lists the jobs of one run — `GET /repos/{owner}/{repo}/actions/runs/{run_id}/jobs`.
    *
    * '''Not paged, and that is the endpoint's decision.''' The spec declares no `page` or `limit` for this operation,
    * so the whole job list arrives at once and the result is a `Vector` rather than a
    * [[com.worxbend.codeberg4s.paging.Page]] — a page reporting a window nobody chose would be a lie about what was
    * requested. A workflow's job count is bounded by its own file, so this is not the unbounded read that would make
    * paging necessary.
    *
    * '''Failures.''' The group contract above.
    */
  def runJobs(owner: Owner, name: RepoName, id: RunId): Future[Vector[ActionRunJob]] =
    pipeline.call(RepositoryActionApi.runJobsRequest(owner, name, id), RetryEligibility.IdempotentOnly)(using
      RepositoryActionDecoders.jobs)

  /** Downloads a run's logs as a ZIP — `GET /repos/{owner}/{repo}/actions/runs/{run_id}/logs`.
    *
    * '''Bytes, not a model''', exactly as [[downloadArtifact]]. For one job's logs as plain text, use [[jobLogs]]
    * instead; it needs no archive handling.
    *
    * '''Failures.''' The group contract above.
    */
  def downloadRunLogs(owner: Owner, name: RepoName, id: RunId): Future[CodebergResponse] =
    pipeline.callDownload(RepositoryActionApi.downloadRunLogsRequest(owner, name, id))

  // --- jobs -----------------------------------------------------------------

  /** Reads one job's log — `GET /repos/{owner}/{repo}/actions/jobs/{job_id}/logs`.
    *
    * '''Text, not JSON.''' The endpoint produces `text/plain`, so the body is handed back verbatim: no trimming, no
    * parsing, no interpretation. It is a `String` rather than a wrapper type because a log genuinely is one, and a
    * one-field wrapper would add a `.value` and nothing else.
    *
    * '''`206` is a success.''' The spec declares both `200` and `206` for this operation, and
    * [[com.worxbend.codeberg4s.core.StatusMapping]] treats every `2xx` alike — so a partial log arrives as a partial
    * `String` and not as a failure. There is no way to tell the two apart from the return value; a caller who needs to
    * know reads the log's own tail.
    *
    * '''This is the supported way to read a run's logs.''' The whole-run log endpoint answers a ZIP and is not
    * implemented; see the class note.
    *
    * '''Failures.''' The group contract above.
    *
    * @param attempt
    *   which execution of the job to read, one-based; `None` asks for the latest — see [[JobAttempt]] for why that is
    *   not spelled as a zero
    */
  def jobLogs(owner: Owner, name: RepoName, id: JobId, attempt: Option[JobAttempt]): Future[String] =
    pipeline.call(RepositoryActionApi.jobLogsRequest(owner, name, id, attempt), RetryEligibility.IdempotentOnly)(using
      RepositoryActionDecoders.jobLog)

  // --- runners --------------------------------------------------------------

  /** Lists the runner tasks of a repository — `GET /repos/{owner}/{repo}/actions/tasks`.
    *
    * '''The body is the same envelope [[runs]] returns''', down to the `workflow_runs` key, even though its elements
    * are tasks; see [[com.worxbend.codeberg4s.repositories.actions.wire.WorkflowRunsEnvelopeDto]].
    *
    * '''Failures.''' The group contract above. This is the one operation in the group for which the spec declares a
    * `409`, and it arrives as [[com.worxbend.codeberg4s.CodebergError.Api]] like any other status.
    */
  def tasks(owner: Owner, name: RepoName, query: ActionTaskQuery, params: PageParams): Future[Page[ActionTask]] =
    pipeline.callPage(RepositoryActionApi.tasksRequest(owner, name, query, params), params)(using
      RepositoryActionDecoders.tasks)

  // --- secrets --------------------------------------------------------------

  /** Starts a workflow by hand — `POST /repos/{owner}/{repo}/actions/workflows/{workflowfilename}/dispatches`.
    *
    * '''Never retried.''' A repeat starts a second run, and Forgejo has no idempotency key that would let the instance
    * recognise the repeat. A transport failure therefore leaves the caller unsure whether a run started, which [[runs]]
    * resolves.
    *
    * '''Whether a run is described back is the caller's choice.''' Without [[DispatchWorkflow.returningRunInfo]] the
    * instance answers `204` with an empty body and this yields `None`; with it, `201` and a [[DispatchedWorkflowRun]].
    * Both are success, and the `Option` is that difference rather than a failure mode — see
    * [[com.worxbend.codeberg4s.repositories.actions.RepositoryActionDecoders]] for how an empty body is read.
    *
    * '''The workflow must declare `workflow_dispatch`''', or Forgejo answers `404` — the same status as a missing file,
    * which makes a typo and an undispatchable workflow indistinguishable from the response alone.
    *
    * '''Failures.''' The group contract above.
    *
    * @param workflow
    *   the workflow's file name, for example `release.yml` — see [[WorkflowFileName]]
    */
  def dispatchWorkflow(
      owner: Owner,
      name: RepoName,
      workflow: WorkflowFileName,
      command: DispatchWorkflow,
  ): Future[Option[DispatchedWorkflowRun]] =
    pipeline.call(
      RepositoryActionApi.dispatchWorkflowRequest(owner, name, workflow, command),
      RetryEligibility.Never,
    )(using RepositoryActionDecoders.dispatchedRun)

/** The requests this group issues, its operation ids, and its typed rail. */
object RepositoryActionApi:

  /** The stable operation id of [[RepositoryActionApi.artifacts]]. Safe to alert on. */
  val ListArtifactsOperation: String = "repos.actions.artifacts.list"

  /** The stable operation id of the single-artifact read on [[RepositoryActionApi]]. */
  val GetArtifactOperation: String = "repos.actions.artifacts.get"

  /** The stable operation id of [[RepositoryActionApi.deleteArtifact]]. */
  val DeleteArtifactOperation: String = "repos.actions.artifacts.delete"

  /** The stable operation id of [[RepositoryActionApi.downloadArtifact]]. */
  val DownloadArtifactOperation: String = "repos.actions.artifacts.download"

  /** The stable operation id of [[RepositoryActionApi.downloadRunLogs]]. */
  val DownloadRunLogsOperation: String = "repos.actions.runs.logs.download"

  /** The stable operation id of [[RepositoryActionApi.runs]]. */
  val ListRunsOperation: String = "repos.actions.runs.list"

  /** The stable operation id of the single-run read on [[RepositoryActionApi]]. */
  val GetRunOperation: String = "repos.actions.runs.get"

  /** The stable operation id of [[RepositoryActionApi.deleteRun]]. */
  val DeleteRunOperation: String = "repos.actions.runs.delete"

  /** The stable operation id of [[RepositoryActionApi.cancelRun]]. */
  val CancelRunOperation: String = "repos.actions.runs.cancel"

  /** The stable operation id of [[RepositoryActionApi.runArtifacts]]. */
  val ListRunArtifactsOperation: String = "repos.actions.runs.artifacts.list"

  /** The stable operation id of [[RepositoryActionApi.runJobs]]. */
  val ListRunJobsOperation: String = "repos.actions.runs.jobs.list"

  /** The stable operation id of [[RepositoryActionApi.jobLogs]]. */
  val JobLogsOperation: String = "repos.actions.jobs.logs"

  /** The stable operation id of [[RepositoryActionApi.tasks]]. */
  val ListTasksOperation: String = "repos.actions.tasks.list"

  /** The stable operation id of [[RepositoryActionApi.dispatchWorkflow]]. */
  val DispatchWorkflowOperation: String = "repos.actions.workflows.dispatch"

  /** The typed rail of [[RepositoryActionApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.repos.actions.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryActionApi)(using exec: Exec[Future]):

    /** [[RepositoryActionApi.artifacts]] with its failure as a value. */
    def artifacts(
        owner: Owner,
        name: RepoName,
        query: ArtifactQuery,
        params: PageParams,
    ): Future[Either[CodebergError, Page[ActionArtifact]]] =
      exec.attempt(rail.artifacts(owner, name, query, params))

    /** The single-artifact read on [[RepositoryActionApi]], with its failure as a value. */
    def artifact(owner: Owner, name: RepoName, id: ArtifactId): Future[Either[CodebergError, ActionArtifact]] =
      exec.attempt(rail.artifact(owner, name, id))

    /** [[RepositoryActionApi.deleteArtifact]] with its failure as a value. */
    def deleteArtifact(owner: Owner, name: RepoName, id: ArtifactId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteArtifact(owner, name, id))

    /** [[RepositoryActionApi.downloadArtifact]] with its failure as a value. */
    def downloadArtifact(
        owner: Owner,
        name: RepoName,
        id: ArtifactId,
    ): Future[Either[CodebergError, CodebergResponse]] =
      exec.attempt(rail.downloadArtifact(owner, name, id))

    /** [[RepositoryActionApi.downloadRunLogs]] with its failure as a value. */
    def downloadRunLogs(owner: Owner, name: RepoName, id: RunId): Future[Either[CodebergError, CodebergResponse]] =
      exec.attempt(rail.downloadRunLogs(owner, name, id))

    /** [[RepositoryActionApi.runs]] with its failure as a value. */
    def runs(
        owner: Owner,
        name: RepoName,
        query: ActionRunQuery,
        params: PageParams,
    ): Future[Either[CodebergError, Page[ActionRun]]] =
      exec.attempt(rail.runs(owner, name, query, params))

    /** The single-run read on [[RepositoryActionApi]], with its failure as a value. */
    def run(owner: Owner, name: RepoName, id: RunId): Future[Either[CodebergError, ActionRun]] =
      exec.attempt(rail.run(owner, name, id))

    /** [[RepositoryActionApi.deleteRun]] with its failure as a value. */
    def deleteRun(owner: Owner, name: RepoName, id: RunId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteRun(owner, name, id))

    /** [[RepositoryActionApi.cancelRun]] with its failure as a value. */
    def cancelRun(owner: Owner, name: RepoName, id: RunId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.cancelRun(owner, name, id))

    /** [[RepositoryActionApi.runArtifacts]] with its failure as a value. */
    def runArtifacts(
        owner: Owner,
        name: RepoName,
        id: RunId,
        query: ArtifactQuery,
        params: PageParams,
    ): Future[Either[CodebergError, Page[ActionArtifact]]] =
      exec.attempt(rail.runArtifacts(owner, name, id, query, params))

    /** [[RepositoryActionApi.runJobs]] with its failure as a value. */
    def runJobs(owner: Owner, name: RepoName, id: RunId): Future[Either[CodebergError, Vector[ActionRunJob]]] =
      exec.attempt(rail.runJobs(owner, name, id))

    /** [[RepositoryActionApi.jobLogs]] with its failure as a value. */
    def jobLogs(
        owner: Owner,
        name: RepoName,
        id: JobId,
        attempt: Option[JobAttempt],
    ): Future[Either[CodebergError, String]] =
      exec.attempt(rail.jobLogs(owner, name, id, attempt))

    /** [[RepositoryActionApi.tasks]] with its failure as a value. */
    def tasks(
        owner: Owner,
        name: RepoName,
        query: ActionTaskQuery,
        params: PageParams,
    ): Future[Either[CodebergError, Page[ActionTask]]] =
      exec.attempt(rail.tasks(owner, name, query, params))

    /** [[RepositoryActionApi.dispatchWorkflow]] with its failure as a value. */
    def dispatchWorkflow(
        owner: Owner,
        name: RepoName,
        workflow: WorkflowFileName,
        command: DispatchWorkflow,
    ): Future[Either[CodebergError, Option[DispatchedWorkflowRun]]] =
      exec.attempt(rail.dispatchWorkflow(owner, name, workflow, command))

  private def artifactsRequest(
      owner: Owner,
      name: RepoName,
      query: ArtifactQuery,
      params: PageParams,
  ): CodebergRequest =
    read(
      ListArtifactsOperation,
      artifactsPath(owner, name),
      ActionQueries.artifacts(query) ++ PagingQuery.window(params),
    )

  private def artifactRequest(owner: Owner, name: RepoName, id: ArtifactId): CodebergRequest =
    read(GetArtifactOperation, artifactPath(owner, name, id), Nil)

  private def deleteArtifactRequest(owner: Owner, name: RepoName, id: ArtifactId): CodebergRequest =
    remove(DeleteArtifactOperation, artifactPath(owner, name, id))

  private def downloadArtifactRequest(owner: Owner, name: RepoName, id: ArtifactId): CodebergRequest =
    read(DownloadArtifactOperation, artifactPath(owner, name, id) :+ "zip", Nil)

  private def downloadRunLogsRequest(owner: Owner, name: RepoName, id: RunId): CodebergRequest =
    read(DownloadRunLogsOperation, runPath(owner, name, id) :+ "logs", Nil)

  private def runsRequest(
      owner: Owner,
      name: RepoName,
      query: ActionRunQuery,
      params: PageParams,
  ): CodebergRequest =
    read(ListRunsOperation, runsPath(owner, name), ActionQueries.runs(query) ++ PagingQuery.window(params))

  private def runRequest(owner: Owner, name: RepoName, id: RunId): CodebergRequest =
    read(GetRunOperation, runPath(owner, name, id), Nil)

  private def deleteRunRequest(owner: Owner, name: RepoName, id: RunId): CodebergRequest =
    remove(DeleteRunOperation, runPath(owner, name, id))

  private def cancelRunRequest(owner: Owner, name: RepoName, id: RunId): CodebergRequest =
    bodiless(CancelRunOperation, HttpMethod.Post, runPath(owner, name, id) :+ "cancel")

  private def runArtifactsRequest(
      owner: Owner,
      name: RepoName,
      id: RunId,
      query: ArtifactQuery,
      params: PageParams,
  ): CodebergRequest =
    read(
      ListRunArtifactsOperation,
      runPath(owner, name, id) :+ "artifacts",
      ActionQueries.artifacts(query) ++ PagingQuery.window(params),
    )

  private def runJobsRequest(owner: Owner, name: RepoName, id: RunId): CodebergRequest =
    read(ListRunJobsOperation, runPath(owner, name, id) :+ "jobs", Nil)

  private def jobLogsRequest(
      owner: Owner,
      name: RepoName,
      id: JobId,
      attempt: Option[JobAttempt],
  ): CodebergRequest =
    read(
      JobLogsOperation,
      actionsPath(owner, name) ++ List("jobs", id.value.toString, "logs"),
      ActionQueries.jobLogs(attempt),
    )

  private def tasksRequest(
      owner: Owner,
      name: RepoName,
      query: ActionTaskQuery,
      params: PageParams,
  ): CodebergRequest =
    read(
      ListTasksOperation,
      actionsPath(owner, name) :+ "tasks",
      ActionQueries.tasks(query) ++ PagingQuery.window(params),
    )

  private def dispatchWorkflowRequest(
      owner: Owner,
      name: RepoName,
      workflow: WorkflowFileName,
      command: DispatchWorkflow,
  ): CodebergRequest =
    write(
      DispatchWorkflowOperation,
      HttpMethod.Post,
      actionsPath(owner, name) ++ List("workflows", workflow.value, "dispatches"),
      DispatchWorkflowOptionDto.render(command),
    )

  private def artifactsPath(owner: Owner, name: RepoName): List[String] =
    actionsPath(owner, name) :+ "artifacts"

  private def artifactPath(owner: Owner, name: RepoName, id: ArtifactId): List[String] =
    artifactsPath(owner, name) :+ id.value.toString

  private def runsPath(owner: Owner, name: RepoName): List[String] =
    actionsPath(owner, name) :+ "runs"

  private def runPath(owner: Owner, name: RepoName, id: RunId): List[String] =
    runsPath(owner, name) :+ id.value.toString
