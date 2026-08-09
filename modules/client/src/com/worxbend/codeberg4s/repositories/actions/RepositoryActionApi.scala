package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RequestBody
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName
import com.worxbend.codeberg4s.repositories.actions.wire.ActionQueries
import com.worxbend.codeberg4s.repositories.actions.wire.DispatchWorkflowOptionDto
import com.worxbend.codeberg4s.repositories.actions.wire.RegisterRunnerOptionDto
import com.worxbend.codeberg4s.repositories.actions.wire.SecretOptionDto
import com.worxbend.codeberg4s.repositories.actions.wire.VariableOptionDto

import scala.concurrent.Future

/** A repository's Actions surface: runs, the jobs and tasks that make them up, the artifacts they produce, the runners
  * that execute them, and the secrets and variables they read.
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
  * ==What is not here==
  *
  * Two spec operations are deliberately absent, because this library cannot implement them honestly:
  *
  *   - `GET /repos/{owner}/{repo}/actions/artifacts/{artifact_id}/zip` (`DownloadActionArtifact`);
  *   - `GET /repos/{owner}/{repo}/actions/runs/{run_id}/logs` (`repoGetActionRunLogs`).
  *
  * Both answer a ZIP archive, which is not text, so neither belongs on a class whose every other operation decodes one.
  * They are '''implemented''', on [[ActionDownloadApi]] — reached as `client.repos.actions.downloads` — which reads a
  * body as bytes. [[ActionArtifact.archiveDownloadUrl]] remains available for a caller who would rather stream the
  * archive with their own HTTP client, since nothing in this library streams. [[jobLogs]] is genuinely text and is
  * implemented here.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class RepositoryActionApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryActionApi.Attempt = RepositoryActionApi.Attempt(this)

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
  def listArtifacts(
      owner: Owner,
      name: RepoName,
      query: ArtifactQuery,
      page: PageParams,
  ): Future[Page[ActionArtifact]] =
    pipeline.callPage(RepositoryActionApi.listArtifactsRequest(owner, name, query, page), page)(using
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

  // --- runs -----------------------------------------------------------------

  /** Lists a repository's workflow runs — `GET /repos/{owner}/{repo}/actions/runs`.
    *
    * '''The body is an envelope, not an array.''' This endpoint answers `{"total_count", "workflow_runs"}`, unlike
    * every other listing in this group; see
    * [[com.worxbend.codeberg4s.repositories.actions.wire.WorkflowRunsEnvelopeDto]]. The body's own `total_count` is
    * '''not''' what [[com.worxbend.codeberg4s.paging.Page.totalCount]] reports — that comes from the `X-Total-Count`
    * header, as it does for every other paged call.
    *
    * '''Paging.''' As [[listArtifacts]]: the `Link` header decides, not the number of items.
    *
    * '''Failures.''' The group contract above.
    *
    * @param query
    *   the filters to apply; [[ActionRunQuery.Empty]] asks for every run
    */
  def listRuns(owner: Owner, name: RepoName, query: ActionRunQuery, page: PageParams): Future[Page[ActionRun]] =
    pipeline.callPage(RepositoryActionApi.listRunsRequest(owner, name, query, page), page)(using
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
    * The same model and the same filter as [[listArtifacts]], scoped to one run. A bare array here, not the envelope
    * [[listRuns]] returns.
    *
    * '''Failures.''' The group contract above.
    */
  def listRunArtifacts(
      owner: Owner,
      name: RepoName,
      id: RunId,
      query: ArtifactQuery,
      page: PageParams,
  ): Future[Page[ActionArtifact]] =
    pipeline.callPage(RepositoryActionApi.listRunArtifactsRequest(owner, name, id, query, page), page)(using
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
  def listRunJobs(owner: Owner, name: RepoName, id: RunId): Future[Vector[ActionRunJob]] =
    pipeline.call(RepositoryActionApi.listRunJobsRequest(owner, name, id), RetryEligibility.IdempotentOnly)(using
      RepositoryActionDecoders.jobs)

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

  /** Lists the runners a repository can dispatch to — `GET /repos/{owner}/{repo}/actions/runners`.
    *
    * '''Failures.''' The group contract above.
    *
    * @param visibility
    *   whether to include runners inherited from the owner and the instance, or only the repository's own — see
    *   [[RunnerVisibility]] for why this is not a `Boolean`
    */
  def listRunners(
      owner: Owner,
      name: RepoName,
      visibility: RunnerVisibility,
      page: PageParams,
  ): Future[Page[ActionRunner]] =
    pipeline.callPage(RepositoryActionApi.listRunnersRequest(owner, name, visibility, page), page)(using
      RepositoryActionDecoders.runners)

  /** Reads one runner — `GET /repos/{owner}/{repo}/actions/runners/{runner_id}`.
    *
    * '''Failures.''' The group contract above.
    */
  def runner(owner: Owner, name: RepoName, id: RunnerId): Future[ActionRunner] =
    pipeline.call(RepositoryActionApi.runnerRequest(owner, name, id), RetryEligibility.IdempotentOnly)(using
      RepositoryActionDecoders.runner)

  /** Registers a runner against the repository — `POST /repos/{owner}/{repo}/actions/runners`.
    *
    * '''Never retried.''' Runner names are explicitly not unique, so a repeat registers a second runner and issues a
    * second token. A transport failure therefore leaves the caller genuinely unsure whether a runner exists, which is
    * the honest state of affairs and better than two — [[listRunners]] resolves it.
    *
    * '''The result carries a credential.''' [[RegisteredRunner.token]] is what the runner binary authenticates with; it
    * masks itself in every rendering path, but it is still a secret that has to reach exactly one machine.
    *
    * '''Failures.''' The group contract above. A `400` means Forgejo rejected the payload — most often a blank name,
    * which [[RegisterRunner.named]] has already refused.
    */
  def registerRunner(owner: Owner, name: RepoName, command: RegisterRunner): Future[RegisteredRunner] =
    pipeline.call(RepositoryActionApi.registerRunnerRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryActionDecoders.registeredRunner)

  /** Deletes a runner — `DELETE /repos/{owner}/{repo}/actions/runners/{runner_id}`.
    *
    * '''Retried''', for the reason [[deleteArtifact]] gives, and with the same `404`-after-a-lost-success consequence.
    *
    * '''Failures.''' The group contract above.
    */
  def deleteRunner(owner: Owner, name: RepoName, id: RunnerId): Future[Unit] =
    pipeline.callUnit(RepositoryActionApi.deleteRunnerRequest(owner, name, id), RetryEligibility.AlwaysRetry)

  /** Obtains the token a new runner registers with — `GET /repos/{owner}/{repo}/actions/runners/registration-token`.
    *
    * '''A `GET` that hands out a credential.''' It is safe in the RFC 9110 sense — reading it changes nothing on the
    * instance — so it is retried like any other read. What it returns is nonetheless a secret with the authority to
    * attach a machine that will execute workflow code, which is why it comes back as a [[RunnerRegistrationToken]] and
    * not as a `String`.
    *
    * Distinct from [[registerRunner]]: that call creates the runner and hands back its token, this one hands back a
    * token the runner binary uses to create itself.
    *
    * '''Failures.''' The group contract above. The spec declares no failure status for this operation at all, which is
    * a gap in the spec rather than a promise — `401` and `404` both occur.
    */
  def runnerRegistrationToken(owner: Owner, name: RepoName): Future[RunnerRegistrationToken] =
    pipeline.call(RepositoryActionApi.runnerRegistrationTokenRequest(owner, name), RetryEligibility.IdempotentOnly)(
      using RepositoryActionDecoders.registrationToken
    )

  /** Finds jobs waiting for a runner with the given labels — `GET /repos/{owner}/{repo}/actions/runners/jobs`.
    *
    * '''Not paged''', for the reason [[listRunJobs]] gives: the spec declares no `page` or `limit`.
    *
    * '''Labels are comma-joined into one parameter''', which is the encoding Forgejo declares and which has no escape.
    * That is why the elements are [[RunnerLabel]], which rejects a comma at construction. An empty vector sends no
    * filter and asks for every job.
    *
    * '''Failures.''' The group contract above.
    */
  def searchRunnerJobs(owner: Owner, name: RepoName, labels: Vector[RunnerLabel]): Future[Vector[ActionRunJob]] =
    pipeline.call(RepositoryActionApi.searchRunnerJobsRequest(owner, name, labels), RetryEligibility.IdempotentOnly)(
      using RepositoryActionDecoders.jobs
    )

  // --- tasks ----------------------------------------------------------------

  /** Lists the runner tasks of a repository — `GET /repos/{owner}/{repo}/actions/tasks`.
    *
    * '''The body is the same envelope [[listRuns]] returns''', down to the `workflow_runs` key, even though its
    * elements are tasks; see [[com.worxbend.codeberg4s.repositories.actions.wire.WorkflowRunsEnvelopeDto]].
    *
    * '''Failures.''' The group contract above. This is the one operation in the group for which the spec declares a
    * `409`, and it arrives as [[com.worxbend.codeberg4s.CodebergError.Api]] like any other status.
    */
  def listTasks(owner: Owner, name: RepoName, query: ActionTaskQuery, page: PageParams): Future[Page[ActionTask]] =
    pipeline.callPage(RepositoryActionApi.listTasksRequest(owner, name, query, page), page)(using
      RepositoryActionDecoders.tasks)

  // --- secrets --------------------------------------------------------------

  /** Lists a repository's secrets — `GET /repos/{owner}/{repo}/actions/secrets`.
    *
    * '''Names and timestamps only.''' No endpoint returns a secret's value, so [[ActionSecret]] has no field for one.
    *
    * '''Failures.''' The group contract above.
    */
  def listSecrets(owner: Owner, name: RepoName, page: PageParams): Future[Page[ActionSecret]] =
    pipeline.callPage(RepositoryActionApi.listSecretsRequest(owner, name, page), page)(using
      RepositoryActionDecoders.secrets)

  /** Creates or replaces a secret — `PUT /repos/{owner}/{repo}/actions/secrets/{secretname}`.
    *
    * '''One method for both, because the API has one endpoint for both.''' Forgejo answers `201` when the secret was
    * created and `204` when it was replaced, and both are success as far as
    * [[com.worxbend.codeberg4s.core.StatusMapping]] is concerned. Neither carries a body, so there is nothing to return
    * and nothing to tell the two apart with — [[listSecrets]] before the call is the only way to know which one will
    * happen.
    *
    * '''Retried''', because the call sets a named resource to a stated value: repeating it leaves the secret exactly as
    * one attempt would have. This is the one write in the group where a retry is unambiguously harmless.
    *
    * '''The value is written down exactly once''', in
    * [[com.worxbend.codeberg4s.repositories.actions.wire.SecretOptionDto]], and reaches nothing but the request bytes.
    *
    * '''Failures.''' The group contract above. A `400` means Forgejo rejected the name — it refuses one starting with a
    * digit and one using a reserved `GITHUB_` or `GITEA_` prefix.
    */
  def setSecret(owner: Owner, name: RepoName, secret: SecretName, value: SecretValue): Future[Unit] =
    pipeline.callUnit(RepositoryActionApi.setSecretRequest(owner, name, secret, value), RetryEligibility.AlwaysRetry)

  /** Deletes a secret — `DELETE /repos/{owner}/{repo}/actions/secrets/{secretname}`.
    *
    * '''Retried''', for the reason [[deleteArtifact]] gives, and with the same `404`-after-a-lost-success consequence.
    *
    * '''Failures.''' The group contract above.
    */
  def deleteSecret(owner: Owner, name: RepoName, secret: SecretName): Future[Unit] =
    pipeline.callUnit(RepositoryActionApi.deleteSecretRequest(owner, name, secret), RetryEligibility.AlwaysRetry)

  // --- variables ------------------------------------------------------------

  /** Lists a repository's variables — `GET /repos/{owner}/{repo}/actions/variables`.
    *
    * '''Values included''', unlike [[listSecrets]] — a variable is configuration, not a credential. See
    * [[ActionVariable]].
    *
    * '''Failures.''' The group contract above.
    */
  def listVariables(owner: Owner, name: RepoName, page: PageParams): Future[Page[ActionVariable]] =
    pipeline.callPage(RepositoryActionApi.listVariablesRequest(owner, name, page), page)(using
      RepositoryActionDecoders.variables)

  /** Reads one variable — `GET /repos/{owner}/{repo}/actions/variables/{variablename}`.
    *
    * '''Failures.''' The group contract above.
    */
  def variable(owner: Owner, name: RepoName, variableName: VariableName): Future[ActionVariable] =
    pipeline.call(RepositoryActionApi.variableRequest(owner, name, variableName), RetryEligibility.IdempotentOnly)(using
      RepositoryActionDecoders.variable)

  /** Creates a variable — `POST /repos/{owner}/{repo}/actions/variables/{variablename}`.
    *
    * '''Never retried''', because it is a `POST` and this library never repeats one; see the class note. The practical
    * effect of a repeat would be a `400` rather than a duplicate — the name is a path segment, so there is only ever
    * one variable under it — but that is Forgejo's behaviour to change, not a promise this library makes on its behalf.
    * [[updateVariable]] is the call that is safe to repeat.
    *
    * '''Answers `201` or `204`, with no body either way''', so there is nothing to return; [[variable]] reads back what
    * was stored.
    *
    * '''Failures.''' The group contract above. A `400` is what an already-existing name produces.
    */
  def createVariable(
      owner: Owner,
      name: RepoName,
      variableName: VariableName,
      command: CreateVariable,
  ): Future[Unit] =
    pipeline.callUnit(
      RepositoryActionApi.createVariableRequest(owner, name, variableName, command),
      RetryEligibility.Never,
    )

  /** Updates a variable, optionally moving it to a new name —
    * `PUT /repos/{owner}/{repo}/actions/variables/{variablename}`.
    *
    * '''Whether this is retried depends on the command, and that is deliberate.''' Setting an existing name to a stated
    * value is idempotent, so a plain [[UpdateVariable.of]] is retried. A command carrying [[UpdateVariable.renamedTo]]
    * is '''not''': the first attempt moves the variable, and a repeat addresses a name that no longer exists. Choosing
    * per call rather than per endpoint is the only way to be right about both.
    *
    * '''Answers `201` or `204`, with no body either way'''; [[variable]] reads back what was stored, at the new name if
    * the command moved it.
    *
    * '''Failures.''' The group contract above. A `404` means the variable does not exist — this endpoint updates, it
    * does not create.
    */
  def updateVariable(
      owner: Owner,
      name: RepoName,
      variableName: VariableName,
      command: UpdateVariable,
  ): Future[Unit] =
    pipeline.callUnit(
      RepositoryActionApi.updateVariableRequest(owner, name, variableName, command),
      RepositoryActionApi.updateVariableEligibility(command),
    )

  /** Deletes a variable — `DELETE /repos/{owner}/{repo}/actions/variables/{variablename}`.
    *
    * '''Retried''', for the reason [[deleteArtifact]] gives, and with the same `404`-after-a-lost-success consequence.
    *
    * '''Failures.''' The group contract above.
    */
  def deleteVariable(owner: Owner, name: RepoName, variableName: VariableName): Future[Unit] =
    pipeline.callUnit(
      RepositoryActionApi.deleteVariableRequest(owner, name, variableName),
      RetryEligibility.AlwaysRetry,
    )

  // --- workflows ------------------------------------------------------------

  /** Starts a workflow by hand — `POST /repos/{owner}/{repo}/actions/workflows/{workflowfilename}/dispatches`.
    *
    * '''Never retried.''' A repeat starts a second run, and Forgejo has no idempotency key that would let the instance
    * recognise the repeat. A transport failure therefore leaves the caller unsure whether a run started, which
    * [[listRuns]] resolves.
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

  /** The stable operation id of [[RepositoryActionApi.listArtifacts]]. Safe to alert on. */
  val ListArtifactsOperation: String = "actions.artifacts.list"

  /** The stable operation id of the single-artifact read on [[RepositoryActionApi]]. */
  val GetArtifactOperation: String = "actions.artifacts.get"

  /** The stable operation id of [[RepositoryActionApi.deleteArtifact]]. */
  val DeleteArtifactOperation: String = "actions.artifacts.delete"

  /** The stable operation id of [[RepositoryActionApi.listRuns]]. */
  val ListRunsOperation: String = "actions.runs.list"

  /** The stable operation id of the single-run read on [[RepositoryActionApi]]. */
  val GetRunOperation: String = "actions.runs.get"

  /** The stable operation id of [[RepositoryActionApi.deleteRun]]. */
  val DeleteRunOperation: String = "actions.runs.delete"

  /** The stable operation id of [[RepositoryActionApi.cancelRun]]. */
  val CancelRunOperation: String = "actions.runs.cancel"

  /** The stable operation id of [[RepositoryActionApi.listRunArtifacts]]. */
  val ListRunArtifactsOperation: String = "actions.runs.artifacts.list"

  /** The stable operation id of [[RepositoryActionApi.listRunJobs]]. */
  val ListRunJobsOperation: String = "actions.runs.jobs.list"

  /** The stable operation id of [[RepositoryActionApi.jobLogs]]. */
  val JobLogsOperation: String = "actions.jobs.logs"

  /** The stable operation id of [[RepositoryActionApi.listRunners]]. */
  val ListRunnersOperation: String = "actions.runners.list"

  /** The stable operation id of the single-runner read on [[RepositoryActionApi]]. */
  val GetRunnerOperation: String = "actions.runners.get"

  /** The stable operation id of [[RepositoryActionApi.registerRunner]]. */
  val RegisterRunnerOperation: String = "actions.runners.register"

  /** The stable operation id of [[RepositoryActionApi.deleteRunner]]. */
  val DeleteRunnerOperation: String = "actions.runners.delete"

  /** The stable operation id of [[RepositoryActionApi.runnerRegistrationToken]]. */
  val RunnerRegistrationTokenOperation: String = "actions.runners.registrationToken"

  /** The stable operation id of [[RepositoryActionApi.searchRunnerJobs]]. */
  val SearchRunnerJobsOperation: String = "actions.runners.jobs.search"

  /** The stable operation id of [[RepositoryActionApi.listTasks]]. */
  val ListTasksOperation: String = "actions.tasks.list"

  /** The stable operation id of [[RepositoryActionApi.listSecrets]]. */
  val ListSecretsOperation: String = "actions.secrets.list"

  /** The stable operation id of [[RepositoryActionApi.setSecret]]. */
  val SetSecretOperation: String = "actions.secrets.set"

  /** The stable operation id of [[RepositoryActionApi.deleteSecret]]. */
  val DeleteSecretOperation: String = "actions.secrets.delete"

  /** The stable operation id of [[RepositoryActionApi.listVariables]]. */
  val ListVariablesOperation: String = "actions.variables.list"

  /** The stable operation id of the single-variable read on [[RepositoryActionApi]]. */
  val GetVariableOperation: String = "actions.variables.get"

  /** The stable operation id of [[RepositoryActionApi.createVariable]]. */
  val CreateVariableOperation: String = "actions.variables.create"

  /** The stable operation id of [[RepositoryActionApi.updateVariable]]. */
  val UpdateVariableOperation: String = "actions.variables.update"

  /** The stable operation id of [[RepositoryActionApi.deleteVariable]]. */
  val DeleteVariableOperation: String = "actions.variables.delete"

  /** The stable operation id of [[RepositoryActionApi.dispatchWorkflow]]. */
  val DispatchWorkflowOperation: String = "actions.workflows.dispatch"

  /** The typed rail of [[RepositoryActionApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.repos.actions.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryActionApi)(using exec: Exec[Future]):

    /** [[RepositoryActionApi.listArtifacts]] with its failure as a value. */
    def listArtifacts(
        owner: Owner,
        name: RepoName,
        query: ArtifactQuery,
        page: PageParams,
    ): Future[Either[CodebergError, Page[ActionArtifact]]] =
      exec.attempt(rail.listArtifacts(owner, name, query, page))

    /** The single-artifact read on [[RepositoryActionApi]], with its failure as a value. */
    def artifact(owner: Owner, name: RepoName, id: ArtifactId): Future[Either[CodebergError, ActionArtifact]] =
      exec.attempt(rail.artifact(owner, name, id))

    /** [[RepositoryActionApi.deleteArtifact]] with its failure as a value. */
    def deleteArtifact(owner: Owner, name: RepoName, id: ArtifactId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteArtifact(owner, name, id))

    /** [[RepositoryActionApi.listRuns]] with its failure as a value. */
    def listRuns(
        owner: Owner,
        name: RepoName,
        query: ActionRunQuery,
        page: PageParams,
    ): Future[Either[CodebergError, Page[ActionRun]]] =
      exec.attempt(rail.listRuns(owner, name, query, page))

    /** The single-run read on [[RepositoryActionApi]], with its failure as a value. */
    def run(owner: Owner, name: RepoName, id: RunId): Future[Either[CodebergError, ActionRun]] =
      exec.attempt(rail.run(owner, name, id))

    /** [[RepositoryActionApi.deleteRun]] with its failure as a value. */
    def deleteRun(owner: Owner, name: RepoName, id: RunId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteRun(owner, name, id))

    /** [[RepositoryActionApi.cancelRun]] with its failure as a value. */
    def cancelRun(owner: Owner, name: RepoName, id: RunId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.cancelRun(owner, name, id))

    /** [[RepositoryActionApi.listRunArtifacts]] with its failure as a value. */
    def listRunArtifacts(
        owner: Owner,
        name: RepoName,
        id: RunId,
        query: ArtifactQuery,
        page: PageParams,
    ): Future[Either[CodebergError, Page[ActionArtifact]]] =
      exec.attempt(rail.listRunArtifacts(owner, name, id, query, page))

    /** [[RepositoryActionApi.listRunJobs]] with its failure as a value. */
    def listRunJobs(owner: Owner, name: RepoName, id: RunId): Future[Either[CodebergError, Vector[ActionRunJob]]] =
      exec.attempt(rail.listRunJobs(owner, name, id))

    /** [[RepositoryActionApi.jobLogs]] with its failure as a value. */
    def jobLogs(
        owner: Owner,
        name: RepoName,
        id: JobId,
        attempt: Option[JobAttempt],
    ): Future[Either[CodebergError, String]] =
      exec.attempt(rail.jobLogs(owner, name, id, attempt))

    /** [[RepositoryActionApi.listRunners]] with its failure as a value. */
    def listRunners(
        owner: Owner,
        name: RepoName,
        visibility: RunnerVisibility,
        page: PageParams,
    ): Future[Either[CodebergError, Page[ActionRunner]]] =
      exec.attempt(rail.listRunners(owner, name, visibility, page))

    /** The single-runner read on [[RepositoryActionApi]], with its failure as a value. */
    def runner(owner: Owner, name: RepoName, id: RunnerId): Future[Either[CodebergError, ActionRunner]] =
      exec.attempt(rail.runner(owner, name, id))

    /** [[RepositoryActionApi.registerRunner]] with its failure as a value. */
    def registerRunner(
        owner: Owner,
        name: RepoName,
        command: RegisterRunner,
    ): Future[Either[CodebergError, RegisteredRunner]] =
      exec.attempt(rail.registerRunner(owner, name, command))

    /** [[RepositoryActionApi.deleteRunner]] with its failure as a value. */
    def deleteRunner(owner: Owner, name: RepoName, id: RunnerId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteRunner(owner, name, id))

    /** [[RepositoryActionApi.runnerRegistrationToken]] with its failure as a value. */
    def runnerRegistrationToken(
        owner: Owner,
        name: RepoName,
    ): Future[Either[CodebergError, RunnerRegistrationToken]] =
      exec.attempt(rail.runnerRegistrationToken(owner, name))

    /** [[RepositoryActionApi.searchRunnerJobs]] with its failure as a value. */
    def searchRunnerJobs(
        owner: Owner,
        name: RepoName,
        labels: Vector[RunnerLabel],
    ): Future[Either[CodebergError, Vector[ActionRunJob]]] =
      exec.attempt(rail.searchRunnerJobs(owner, name, labels))

    /** [[RepositoryActionApi.listTasks]] with its failure as a value. */
    def listTasks(
        owner: Owner,
        name: RepoName,
        query: ActionTaskQuery,
        page: PageParams,
    ): Future[Either[CodebergError, Page[ActionTask]]] =
      exec.attempt(rail.listTasks(owner, name, query, page))

    /** [[RepositoryActionApi.listSecrets]] with its failure as a value. */
    def listSecrets(
        owner: Owner,
        name: RepoName,
        page: PageParams,
    ): Future[Either[CodebergError, Page[ActionSecret]]] =
      exec.attempt(rail.listSecrets(owner, name, page))

    /** [[RepositoryActionApi.setSecret]] with its failure as a value. */
    def setSecret(
        owner: Owner,
        name: RepoName,
        secret: SecretName,
        value: SecretValue,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.setSecret(owner, name, secret, value))

    /** [[RepositoryActionApi.deleteSecret]] with its failure as a value. */
    def deleteSecret(owner: Owner, name: RepoName, secret: SecretName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteSecret(owner, name, secret))

    /** [[RepositoryActionApi.listVariables]] with its failure as a value. */
    def listVariables(
        owner: Owner,
        name: RepoName,
        page: PageParams,
    ): Future[Either[CodebergError, Page[ActionVariable]]] =
      exec.attempt(rail.listVariables(owner, name, page))

    /** The single-variable read on [[RepositoryActionApi]], with its failure as a value. */
    def variable(
        owner: Owner,
        name: RepoName,
        variableName: VariableName,
    ): Future[Either[CodebergError, ActionVariable]] =
      exec.attempt(rail.variable(owner, name, variableName))

    /** [[RepositoryActionApi.createVariable]] with its failure as a value. */
    def createVariable(
        owner: Owner,
        name: RepoName,
        variableName: VariableName,
        command: CreateVariable,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.createVariable(owner, name, variableName, command))

    /** [[RepositoryActionApi.updateVariable]] with its failure as a value. */
    def updateVariable(
        owner: Owner,
        name: RepoName,
        variableName: VariableName,
        command: UpdateVariable,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.updateVariable(owner, name, variableName, command))

    /** [[RepositoryActionApi.deleteVariable]] with its failure as a value. */
    def deleteVariable(
        owner: Owner,
        name: RepoName,
        variableName: VariableName,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteVariable(owner, name, variableName))

    /** [[RepositoryActionApi.dispatchWorkflow]] with its failure as a value. */
    def dispatchWorkflow(
        owner: Owner,
        name: RepoName,
        workflow: WorkflowFileName,
        command: DispatchWorkflow,
    ): Future[Either[CodebergError, Option[DispatchedWorkflowRun]]] =
      exec.attempt(rail.dispatchWorkflow(owner, name, workflow, command))

  /** Whether an update may be repeated, which depends on whether it moves the variable; see
    * [[RepositoryActionApi.updateVariable]].
    */
  private[actions] def updateVariableEligibility(command: UpdateVariable): RetryEligibility =
    if command.renamedTo.isEmpty then RetryEligibility.AlwaysRetry else RetryEligibility.Never

  private def listArtifactsRequest(
      owner: Owner,
      name: RepoName,
      query: ArtifactQuery,
      page: PageParams,
  ): CodebergRequest =
    read(
      ListArtifactsOperation,
      artifactsPath(owner, name),
      ActionQueries.artifacts(query) ++ ActionQueries.paging(page),
    )

  private def artifactRequest(owner: Owner, name: RepoName, id: ArtifactId): CodebergRequest =
    read(GetArtifactOperation, artifactPath(owner, name, id), Nil)

  private def deleteArtifactRequest(owner: Owner, name: RepoName, id: ArtifactId): CodebergRequest =
    remove(DeleteArtifactOperation, artifactPath(owner, name, id))

  private def listRunsRequest(
      owner: Owner,
      name: RepoName,
      query: ActionRunQuery,
      page: PageParams,
  ): CodebergRequest =
    read(ListRunsOperation, runsPath(owner, name), ActionQueries.runs(query) ++ ActionQueries.paging(page))

  private def runRequest(owner: Owner, name: RepoName, id: RunId): CodebergRequest =
    read(GetRunOperation, runPath(owner, name, id), Nil)

  private def deleteRunRequest(owner: Owner, name: RepoName, id: RunId): CodebergRequest =
    remove(DeleteRunOperation, runPath(owner, name, id))

  private def cancelRunRequest(owner: Owner, name: RepoName, id: RunId): CodebergRequest =
    CodebergRequest(
      operation = CancelRunOperation,
      method    = HttpMethod.Post,
      path      = runPath(owner, name, id) :+ "cancel",
      query     = Nil,
      headers   = Nil,
      body      = None,
    )

  private def listRunArtifactsRequest(
      owner: Owner,
      name: RepoName,
      id: RunId,
      query: ArtifactQuery,
      page: PageParams,
  ): CodebergRequest =
    read(
      ListRunArtifactsOperation,
      runPath(owner, name, id) :+ "artifacts",
      ActionQueries.artifacts(query) ++ ActionQueries.paging(page),
    )

  private def listRunJobsRequest(owner: Owner, name: RepoName, id: RunId): CodebergRequest =
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

  private def listRunnersRequest(
      owner: Owner,
      name: RepoName,
      visibility: RunnerVisibility,
      page: PageParams,
  ): CodebergRequest =
    read(
      ListRunnersOperation,
      runnersPath(owner, name),
      ActionQueries.runners(visibility) ++ ActionQueries.paging(page),
    )

  private def runnerRequest(owner: Owner, name: RepoName, id: RunnerId): CodebergRequest =
    read(GetRunnerOperation, runnerPath(owner, name, id), Nil)

  private def registerRunnerRequest(owner: Owner, name: RepoName, command: RegisterRunner): CodebergRequest =
    write(
      RegisterRunnerOperation,
      HttpMethod.Post,
      runnersPath(owner, name),
      RegisterRunnerOptionDto.render(command),
    )

  private def deleteRunnerRequest(owner: Owner, name: RepoName, id: RunnerId): CodebergRequest =
    remove(DeleteRunnerOperation, runnerPath(owner, name, id))

  private def runnerRegistrationTokenRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(RunnerRegistrationTokenOperation, runnersPath(owner, name) :+ "registration-token", Nil)

  private def searchRunnerJobsRequest(
      owner: Owner,
      name: RepoName,
      labels: Vector[RunnerLabel],
  ): CodebergRequest =
    read(SearchRunnerJobsOperation, runnersPath(owner, name) :+ "jobs", ActionQueries.runnerJobs(labels))

  private def listTasksRequest(
      owner: Owner,
      name: RepoName,
      query: ActionTaskQuery,
      page: PageParams,
  ): CodebergRequest =
    read(
      ListTasksOperation,
      actionsPath(owner, name) :+ "tasks",
      ActionQueries.tasks(query) ++ ActionQueries.paging(page),
    )

  private def listSecretsRequest(owner: Owner, name: RepoName, page: PageParams): CodebergRequest =
    read(ListSecretsOperation, secretsPath(owner, name), ActionQueries.paging(page))

  private def setSecretRequest(
      owner: Owner,
      name: RepoName,
      secret: SecretName,
      value: SecretValue,
  ): CodebergRequest =
    write(SetSecretOperation, HttpMethod.Put, secretPath(owner, name, secret), SecretOptionDto.render(value))

  private def deleteSecretRequest(owner: Owner, name: RepoName, secret: SecretName): CodebergRequest =
    remove(DeleteSecretOperation, secretPath(owner, name, secret))

  private def listVariablesRequest(owner: Owner, name: RepoName, page: PageParams): CodebergRequest =
    read(ListVariablesOperation, variablesPath(owner, name), ActionQueries.paging(page))

  private def variableRequest(owner: Owner, name: RepoName, variableName: VariableName): CodebergRequest =
    read(GetVariableOperation, variablePath(owner, name, variableName), Nil)

  private def createVariableRequest(
      owner: Owner,
      name: RepoName,
      variableName: VariableName,
      command: CreateVariable,
  ): CodebergRequest =
    write(
      CreateVariableOperation,
      HttpMethod.Post,
      variablePath(owner, name, variableName),
      VariableOptionDto.renderCreate(command),
    )

  private def updateVariableRequest(
      owner: Owner,
      name: RepoName,
      variableName: VariableName,
      command: UpdateVariable,
  ): CodebergRequest =
    write(
      UpdateVariableOperation,
      HttpMethod.Put,
      variablePath(owner, name, variableName),
      VariableOptionDto.renderUpdate(command),
    )

  private def deleteVariableRequest(owner: Owner, name: RepoName, variableName: VariableName): CodebergRequest =
    remove(DeleteVariableOperation, variablePath(owner, name, variableName))

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

  private def read(operation: String, path: List[String], query: List[(String, String)]): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Get,
      path      = path,
      query     = query,
      headers   = Nil,
      body      = None,
    )

  private def write(operation: String, method: HttpMethod, path: List[String], body: String): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = method,
      path      = path,
      query     = Nil,
      headers   = Nil,
      body      = Some(RequestBody.Json(body)),
    )

  private def remove(operation: String, path: List[String]): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Delete,
      path      = path,
      query     = Nil,
      headers   = Nil,
      body      = None,
    )

  private def actionsPath(owner: Owner, name: RepoName): List[String] =
    List("repos", owner.value, name.value, "actions")

  private def artifactsPath(owner: Owner, name: RepoName): List[String] =
    actionsPath(owner, name) :+ "artifacts"

  private def artifactPath(owner: Owner, name: RepoName, id: ArtifactId): List[String] =
    artifactsPath(owner, name) :+ id.value.toString

  private def runsPath(owner: Owner, name: RepoName): List[String] =
    actionsPath(owner, name) :+ "runs"

  private def runPath(owner: Owner, name: RepoName, id: RunId): List[String] =
    runsPath(owner, name) :+ id.value.toString

  private def runnersPath(owner: Owner, name: RepoName): List[String] =
    actionsPath(owner, name) :+ "runners"

  private def runnerPath(owner: Owner, name: RepoName, id: RunnerId): List[String] =
    runnersPath(owner, name) :+ id.value

  private def secretsPath(owner: Owner, name: RepoName): List[String] =
    actionsPath(owner, name) :+ "secrets"

  private def secretPath(owner: Owner, name: RepoName, secret: SecretName): List[String] =
    secretsPath(owner, name) :+ secret.value

  private def variablesPath(owner: Owner, name: RepoName): List[String] =
    actionsPath(owner, name) :+ "variables"

  private def variablePath(owner: Owner, name: RepoName, variableName: VariableName): List[String] =
    variablesPath(owner, name) :+ variableName.value
