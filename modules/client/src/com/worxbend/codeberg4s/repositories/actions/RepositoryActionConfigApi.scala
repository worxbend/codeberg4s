package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.core.CodebergRequest.{read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.repositories.actions.ActionRequests.actionsPath
import com.worxbend.codeberg4s.repositories.actions.wire.{
  ActionQueries,
  RegisterRunnerOptionDto,
  SecretOptionDto,
  VariableOptionDto
}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName}

import scala.concurrent.Future

/** What a repository's workflows run on and with: its runners, its secrets and its variables.
  *
  * Reached as `client.repos.actions.config`. It is a group of its own rather than more methods on
  * [[RepositoryActionApi]] because that class had grown past what a reader can hold in their head, and because the
  * split falls on a real line: everything here exists '''before''' a run does and outlives it, while runs, jobs, tasks
  * and artifacts are the record of runs that happened. The endpoints, the models and the retry decisions are unchanged
  * by the move.
  *
  * These are the same fourteen operations [[com.worxbend.codeberg4s.organizations.actions.OrganizationActionApi]]
  * serves one path segment higher, with the same names and the same models; the difference is scope, and scope is what
  * these values mean. A secret set here is readable by the workflows of this repository, where the organisation-scoped
  * call of the same name reaches every repository the organisation owns.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[RepositoryActionConfigApi.attempt]]
  * never fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Secrets are write-only==
  *
  * [[secrets]] lists the names of a repository's secrets and never their values — Forgejo does not send them back, and
  * [[com.worxbend.codeberg4s.repositories.actions.SecretValue]] is marked sensitive so a value on its way out cannot
  * reach a log. Variables are not secret and [[variable]] does return the value.
  *
  * ==Evidence==
  *
  * '''Every model in this group is derived from `spec/swagger.v1.json`, not from a captured response.''' The harvest
  * behind `modules/codec/test/resources/golden` was anonymous and every endpoint here requires a token, so no fixture
  * exists for any of them. Where a shape is asserted in a test, the payload was written by hand to match the spec's
  * definition — it is not evidence that Forgejo sends exactly this.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository, the runner, the secret or
  *     the variable does not exist '''or''' is invisible to the credentials in use, `401` when a token was required and
  *     none was sent, and `403` when the token lacks the scope or the account lacks the permission. `422` '''and'''
  *     `400` both mean the request was rejected as invalid.
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
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. A write is retried only when its request
  * names one resource and states its whole intended state, so that N attempts leave the instance as one attempt would;
  * each write says which case it is on its own method, and [[updateVariable]] decides per call because a rename moves
  * the target and a plain value change does not.
  */

final class RepositoryActionConfigApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryActionConfigApi.Attempt = RepositoryActionConfigApi.Attempt(this)

  /** Lists the runners a repository can dispatch to — `GET /repos/{owner}/{repo}/actions/runners`.
    *
    * '''Failures.''' The group contract above.
    *
    * @param visibility
    *   whether to include runners inherited from the owner and the instance, or only the repository's own — see
    *   [[RunnerVisibility]] for why this is not a `Boolean`
    */
  def runners(
      owner: Owner,
      name: RepoName,
      visibility: RunnerVisibility,
      params: PageParams,
  ): Future[Page[ActionRunner]] =
    pipeline.callPage(RepositoryActionConfigApi.runnersRequest(owner, name, visibility, params), params)(using
      RepositoryActionDecoders.runners)

  /** Reads one runner — `GET /repos/{owner}/{repo}/actions/runners/{runner_id}`.
    *
    * '''Failures.''' The group contract above.
    */
  def runner(owner: Owner, name: RepoName, id: RunnerId): Future[ActionRunner] =
    pipeline.call(RepositoryActionConfigApi.runnerRequest(owner, name, id), RetryEligibility.IdempotentOnly)(using
      RepositoryActionDecoders.runner)

  /** Registers a runner against the repository — `POST /repos/{owner}/{repo}/actions/runners`.
    *
    * '''Never retried.''' Runner names are explicitly not unique, so a repeat registers a second runner and issues a
    * second token. A transport failure therefore leaves the caller genuinely unsure whether a runner exists, which is
    * the honest state of affairs and better than two — [[runners]] resolves it.
    *
    * '''The result carries a credential.''' [[RegisteredRunner.token]] is what the runner binary authenticates with; it
    * masks itself in every rendering path, but it is still a secret that has to reach exactly one machine.
    *
    * '''Failures.''' The group contract above. A `400` means Forgejo rejected the payload — most often a blank name,
    * which [[RegisterRunner.named]] has already refused.
    */
  def registerRunner(owner: Owner, name: RepoName, command: RegisterRunner): Future[RegisteredRunner] =
    pipeline.call(RepositoryActionConfigApi.registerRunnerRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryActionDecoders.registeredRunner)

  /** Deletes a runner — `DELETE /repos/{owner}/{repo}/actions/runners/{runner_id}`.
    *
    * '''Retried''', for the reason [[deleteArtifact]] gives, and with the same `404`-after-a-lost-success consequence.
    *
    * '''Failures.''' The group contract above.
    */
  def deleteRunner(owner: Owner, name: RepoName, id: RunnerId): Future[Unit] =
    pipeline.callUnit(RepositoryActionConfigApi.deleteRunnerRequest(owner, name, id), RetryEligibility.AlwaysRetry)

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
    pipeline.call(RepositoryActionConfigApi.runnerRegistrationTokenRequest(owner, name), RetryEligibility.IdempotentOnly)(
      using RepositoryActionDecoders.registrationToken
    )

  /** Finds jobs waiting for a runner with the given labels — `GET /repos/{owner}/{repo}/actions/runners/jobs`.
    *
    * '''Not paged''', for the reason [[runJobs]] gives: the spec declares no `page` or `limit`.
    *
    * '''Labels are comma-joined into one parameter''', which is the encoding Forgejo declares and which has no escape.
    * That is why the elements are [[RunnerLabel]], which rejects a comma at construction. An empty vector sends no
    * filter and asks for every job.
    *
    * '''Failures.''' The group contract above.
    */
  def searchRunnerJobs(owner: Owner, name: RepoName, labels: Vector[RunnerLabel]): Future[Vector[ActionRunJob]] =
    pipeline.call(
      RepositoryActionConfigApi.searchRunnerJobsRequest(owner, name, labels),
      RetryEligibility.IdempotentOnly
    )(
      using RepositoryActionDecoders.jobs
    )

  // --- tasks ----------------------------------------------------------------

  /** Lists a repository's secrets — `GET /repos/{owner}/{repo}/actions/secrets`.
    *
    * '''Names and timestamps only.''' No endpoint returns a secret's value, so [[ActionSecret]] has no field for one.
    *
    * '''Failures.''' The group contract above.
    */
  def secrets(owner: Owner, name: RepoName, params: PageParams): Future[Page[ActionSecret]] =
    pipeline.callPage(RepositoryActionConfigApi.secretsRequest(owner, name, params), params)(using
      RepositoryActionDecoders.secrets)

  /** Creates or replaces a secret — `PUT /repos/{owner}/{repo}/actions/secrets/{secretname}`.
    *
    * '''One method for both, because the API has one endpoint for both.''' Forgejo answers `201` when the secret was
    * created and `204` when it was replaced, and both are success as far as
    * [[com.worxbend.codeberg4s.core.StatusMapping]] is concerned. Neither carries a body, so there is nothing to return
    * and nothing to tell the two apart with — [[secrets]] before the call is the only way to know which one will
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
    pipeline.callUnit(
      RepositoryActionConfigApi.setSecretRequest(owner, name, secret, value),
      RetryEligibility.AlwaysRetry
    )

  /** Deletes a secret — `DELETE /repos/{owner}/{repo}/actions/secrets/{secretname}`.
    *
    * '''Retried''', for the reason [[deleteArtifact]] gives, and with the same `404`-after-a-lost-success consequence.
    *
    * '''Failures.''' The group contract above.
    */
  def deleteSecret(owner: Owner, name: RepoName, secret: SecretName): Future[Unit] =
    pipeline.callUnit(RepositoryActionConfigApi.deleteSecretRequest(owner, name, secret), RetryEligibility.AlwaysRetry)

  // --- variables ------------------------------------------------------------

  /** Lists a repository's variables — `GET /repos/{owner}/{repo}/actions/variables`.
    *
    * '''Values included''', unlike [[secrets]] — a variable is configuration, not a credential. See [[ActionVariable]].
    *
    * '''Failures.''' The group contract above.
    */
  def variables(owner: Owner, name: RepoName, params: PageParams): Future[Page[ActionVariable]] =
    pipeline.callPage(RepositoryActionConfigApi.variablesRequest(owner, name, params), params)(using
      RepositoryActionDecoders.variables)

  /** Reads one variable — `GET /repos/{owner}/{repo}/actions/variables/{variablename}`.
    *
    * '''Failures.''' The group contract above.
    */
  def variable(owner: Owner, name: RepoName, variableName: VariableName): Future[ActionVariable] =
    pipeline.call(
      RepositoryActionConfigApi.variableRequest(owner, name, variableName),
      RetryEligibility.IdempotentOnly
    )(using RepositoryActionDecoders.variable)

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
      RepositoryActionConfigApi.createVariableRequest(owner, name, variableName, command),
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
      RepositoryActionConfigApi.updateVariableRequest(owner, name, variableName, command),
      RepositoryActionConfigApi.updateVariableEligibility(command),
    )

  /** Deletes a variable — `DELETE /repos/{owner}/{repo}/actions/variables/{variablename}`.
    *
    * '''Retried''', for the reason [[deleteArtifact]] gives, and with the same `404`-after-a-lost-success consequence.
    *
    * '''Failures.''' The group contract above.
    */
  def deleteVariable(owner: Owner, name: RepoName, variableName: VariableName): Future[Unit] =
    pipeline.callUnit(
      RepositoryActionConfigApi.deleteVariableRequest(owner, name, variableName),
      RetryEligibility.AlwaysRetry,
    )

  // --- workflows ------------------------------------------------------------

/** The requests this group issues, its operation ids, and its typed rail. */
object RepositoryActionConfigApi:

  /** The stable operation id of [[RepositoryActionConfigApi.runners]]. */
  val ListRunnersOperation: String = "repos.actions.runners.list"

  /** The stable operation id of the single-runner read on [[RepositoryActionApi]]. */
  val GetRunnerOperation: String = "repos.actions.runners.get"

  /** The stable operation id of [[RepositoryActionConfigApi.registerRunner]]. */
  val RegisterRunnerOperation: String = "repos.actions.runners.register"

  /** The stable operation id of [[RepositoryActionConfigApi.deleteRunner]]. */
  val DeleteRunnerOperation: String = "repos.actions.runners.delete"

  /** The stable operation id of [[RepositoryActionConfigApi.runnerRegistrationToken]]. */
  val RunnerRegistrationTokenOperation: String = "repos.actions.runners.registrationToken"

  /** The stable operation id of [[RepositoryActionConfigApi.searchRunnerJobs]]. */
  val SearchRunnerJobsOperation: String = "repos.actions.runners.jobs.search"

  /** The stable operation id of [[RepositoryActionConfigApi.secrets]]. */
  val ListSecretsOperation: String = "repos.actions.secrets.list"

  /** The stable operation id of [[RepositoryActionConfigApi.setSecret]]. */
  val SetSecretOperation: String = "repos.actions.secrets.set"

  /** The stable operation id of [[RepositoryActionConfigApi.deleteSecret]]. */
  val DeleteSecretOperation: String = "repos.actions.secrets.delete"

  /** The stable operation id of [[RepositoryActionConfigApi.variables]]. */
  val ListVariablesOperation: String = "repos.actions.variables.list"

  /** The stable operation id of the single-variable read on [[RepositoryActionApi]]. */
  val GetVariableOperation: String = "repos.actions.variables.get"

  /** The stable operation id of [[RepositoryActionConfigApi.createVariable]]. */
  val CreateVariableOperation: String = "repos.actions.variables.create"

  /** The stable operation id of [[RepositoryActionConfigApi.updateVariable]]. */
  val UpdateVariableOperation: String = "repos.actions.variables.update"

  /** The stable operation id of [[RepositoryActionConfigApi.deleteVariable]]. */
  val DeleteVariableOperation: String = "repos.actions.variables.delete"

  /** The typed rail of [[RepositoryActionConfigApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]]
    * as a value.
    *
    * Obtained as `client.repos.actions.config.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryActionConfigApi)(using exec: Exec[Future]):

    /** [[RepositoryActionConfigApi.runners]] with its failure as a value. */
    def runners(
        owner: Owner,
        name: RepoName,
        visibility: RunnerVisibility,
        params: PageParams,
    ): Future[Either[CodebergError, Page[ActionRunner]]] =
      exec.attempt(rail.runners(owner, name, visibility, params))

    /** The single-runner read on [[RepositoryActionApi]], with its failure as a value. */
    def runner(owner: Owner, name: RepoName, id: RunnerId): Future[Either[CodebergError, ActionRunner]] =
      exec.attempt(rail.runner(owner, name, id))

    /** [[RepositoryActionConfigApi.registerRunner]] with its failure as a value. */
    def registerRunner(
        owner: Owner,
        name: RepoName,
        command: RegisterRunner,
    ): Future[Either[CodebergError, RegisteredRunner]] =
      exec.attempt(rail.registerRunner(owner, name, command))

    /** [[RepositoryActionConfigApi.deleteRunner]] with its failure as a value. */
    def deleteRunner(owner: Owner, name: RepoName, id: RunnerId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteRunner(owner, name, id))

    /** [[RepositoryActionConfigApi.runnerRegistrationToken]] with its failure as a value. */
    def runnerRegistrationToken(
        owner: Owner,
        name: RepoName,
    ): Future[Either[CodebergError, RunnerRegistrationToken]] =
      exec.attempt(rail.runnerRegistrationToken(owner, name))

    /** [[RepositoryActionConfigApi.searchRunnerJobs]] with its failure as a value. */
    def searchRunnerJobs(
        owner: Owner,
        name: RepoName,
        labels: Vector[RunnerLabel],
    ): Future[Either[CodebergError, Vector[ActionRunJob]]] =
      exec.attempt(rail.searchRunnerJobs(owner, name, labels))

    /** [[RepositoryActionConfigApi.secrets]] with its failure as a value. */
    def secrets(
        owner: Owner,
        name: RepoName,
        params: PageParams,
    ): Future[Either[CodebergError, Page[ActionSecret]]] =
      exec.attempt(rail.secrets(owner, name, params))

    /** [[RepositoryActionConfigApi.setSecret]] with its failure as a value. */
    def setSecret(
        owner: Owner,
        name: RepoName,
        secret: SecretName,
        value: SecretValue,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.setSecret(owner, name, secret, value))

    /** [[RepositoryActionConfigApi.deleteSecret]] with its failure as a value. */
    def deleteSecret(owner: Owner, name: RepoName, secret: SecretName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteSecret(owner, name, secret))

    /** [[RepositoryActionConfigApi.variables]] with its failure as a value. */
    def variables(
        owner: Owner,
        name: RepoName,
        params: PageParams,
    ): Future[Either[CodebergError, Page[ActionVariable]]] =
      exec.attempt(rail.variables(owner, name, params))

    /** The single-variable read on [[RepositoryActionApi]], with its failure as a value. */
    def variable(
        owner: Owner,
        name: RepoName,
        variableName: VariableName,
    ): Future[Either[CodebergError, ActionVariable]] =
      exec.attempt(rail.variable(owner, name, variableName))

    /** [[RepositoryActionConfigApi.createVariable]] with its failure as a value. */
    def createVariable(
        owner: Owner,
        name: RepoName,
        variableName: VariableName,
        command: CreateVariable,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.createVariable(owner, name, variableName, command))

    /** [[RepositoryActionConfigApi.updateVariable]] with its failure as a value. */
    def updateVariable(
        owner: Owner,
        name: RepoName,
        variableName: VariableName,
        command: UpdateVariable,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.updateVariable(owner, name, variableName, command))

    /** [[RepositoryActionConfigApi.deleteVariable]] with its failure as a value. */
    def deleteVariable(
        owner: Owner,
        name: RepoName,
        variableName: VariableName,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteVariable(owner, name, variableName))

  /** Whether an update may be repeated, which depends on whether it moves the variable; see
    * [[RepositoryActionConfigApi.updateVariable]].
    */
  private[actions] def updateVariableEligibility(command: UpdateVariable): RetryEligibility =
    if command.renamedTo.isEmpty then RetryEligibility.AlwaysRetry else RetryEligibility.Never

  private def runnersRequest(
      owner: Owner,
      name: RepoName,
      visibility: RunnerVisibility,
      params: PageParams,
  ): CodebergRequest =
    read(
      ListRunnersOperation,
      runnersPath(owner, name),
      ActionQueries.runners(visibility) ++ PagingQuery.window(params),
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

  private def secretsRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(ListSecretsOperation, secretsPath(owner, name), PagingQuery.window(params))

  private def setSecretRequest(
      owner: Owner,
      name: RepoName,
      secret: SecretName,
      value: SecretValue,
  ): CodebergRequest =
    write(SetSecretOperation, HttpMethod.Put, secretPath(owner, name, secret), SecretOptionDto.render(value))

  private def deleteSecretRequest(owner: Owner, name: RepoName, secret: SecretName): CodebergRequest =
    remove(DeleteSecretOperation, secretPath(owner, name, secret))

  private def variablesRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(ListVariablesOperation, variablesPath(owner, name), PagingQuery.window(params))

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
