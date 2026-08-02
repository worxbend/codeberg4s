package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.actions.ActionRunJob
import com.worxbend.codeberg4s.repositories.actions.ActionRunner
import com.worxbend.codeberg4s.repositories.actions.ActionVariable
import com.worxbend.codeberg4s.repositories.actions.CreateVariable
import com.worxbend.codeberg4s.repositories.actions.RegisterRunner
import com.worxbend.codeberg4s.repositories.actions.RegisteredRunner
import com.worxbend.codeberg4s.repositories.actions.RunnerId
import com.worxbend.codeberg4s.repositories.actions.RunnerLabel
import com.worxbend.codeberg4s.repositories.actions.RunnerRegistrationToken
import com.worxbend.codeberg4s.repositories.actions.RunnerVisibility
import com.worxbend.codeberg4s.repositories.actions.SecretName
import com.worxbend.codeberg4s.repositories.actions.SecretValue
import com.worxbend.codeberg4s.repositories.actions.UpdateVariable
import com.worxbend.codeberg4s.repositories.actions.VariableName
import com.worxbend.codeberg4s.repositories.actions.wire.ActionQueries
import com.worxbend.codeberg4s.repositories.actions.wire.RegisterRunnerOptionDto
import com.worxbend.codeberg4s.repositories.actions.wire.SecretOptionDto
import com.worxbend.codeberg4s.repositories.actions.wire.VariableOptionDto

import scala.concurrent.Future

/** The authenticated account's own Actions configuration: its runners, its secrets and its variables.
  *
  * Reached as `client.users.account.actions`. Both error rails are here (ADR-0005): the methods on this class fail the
  * `Future` with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[UserActionApi.attempt]]
  * never fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==This is the repository Actions surface, scoped to an account==
  *
  * `/user/actions` answers the same models as its `/repos/{owner}/{repo}/actions` twin — an `ActionRunner` is an
  * `ActionRunner`, a `Secret` is a `Secret` — so this class reuses
  * [[com.worxbend.codeberg4s.repositories.actions.ActionRunner]],
  * [[com.worxbend.codeberg4s.repositories.actions.ActionVariable]],
  * [[com.worxbend.codeberg4s.repositories.actions.SecretValue]] and the rest rather than defining twins of them. Only
  * the path differs. What a runner or a variable '''means''' does differ: one registered here belongs to the account
  * and is inherited by every repository it owns, which is why deleting one is a much larger act than deleting a
  * repository's own.
  *
  * '''There is no secret listing.''' `spec/swagger.v1.json` declares `PUT` and `DELETE` on
  * `/user/actions/secrets/{secretname}` and no `GET` of any kind — unlike the repository and organisation surfaces,
  * which do list their secrets' names. A caller cannot enumerate an account's secrets through this API, and this class
  * does not pretend otherwise by offering a method that would always fail.
  *
  * ==Evidence==
  *
  * '''Every payload this class reads is derived from `spec/swagger.v1.json`, not from a captured response.''' The
  * harvest behind `modules/codec/test/resources/golden` was anonymous and every endpoint here requires a token, so no
  * fixture exists for any of them; see [[com.worxbend.codeberg4s.repositories.actions.RepositoryActionApi]], whose
  * models these are.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `401` when no credentials were configured or the token
  *     was rejected — every path in this group is `/user/…` and has no anonymous reading — `403` when the token lacks
  *     the scope or the instance has Actions disabled, and `404` when the named runner, secret or variable does not
  *     exist. `422` '''and''' `400` both mean the request was rejected as invalid; `docs/HAZARDS.md` §4 records Forgejo
  *     using `400` where a reader would expect `422`.
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
  * decision is justified where it is made; the shape of the argument is the one earlier waves set:
  *
  *   - every `POST` uses [[com.worxbend.codeberg4s.core.RetryEligibility.Never]], because Forgejo offers no idempotency
  *     key and a repeat creates a second thing;
  *   - a `PUT` or `DELETE` that names one resource and states the value it should end up with is retried, because
  *     applying it twice leaves the account where applying it once would and creates nothing.
  *
  * ==Secrets==
  *
  * A secret's value goes in and never comes out — and on this surface it cannot even be enumerated. [[setSecret]] takes
  * a [[com.worxbend.codeberg4s.repositories.actions.SecretValue]], which masks itself in every rendering path.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class UserActionApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: UserActionApi.Attempt = UserActionApi.Attempt(this)

  // --- runners --------------------------------------------------------------

  /** Lists the runners the account can dispatch to — `GET /user/actions/runners`.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5). A page past
    * the end is `200` with `[]`, not a `404`.
    *
    * '''Failures.''' The group contract above.
    *
    * @param visibility
    *   whether to include runners inherited from the instance, or only the account's own — see
    *   [[com.worxbend.codeberg4s.repositories.actions.RunnerVisibility]] for why this is not a `Boolean`
    */
  def listRunners(visibility: RunnerVisibility, page: PageParams): Future[Page[ActionRunner]] =
    pipeline.callPage(UserActionApi.listRunnersRequest(visibility, page), page)(using UserAccountDecoders.runners)

  /** Reads one of the account's runners — `GET /user/actions/runners/{runner_id}`.
    *
    * '''Failures.''' The group contract above.
    */
  def runner(id: RunnerId): Future[ActionRunner] =
    pipeline.call(UserActionApi.runnerRequest(id), RetryEligibility.IdempotentOnly)(using UserAccountDecoders.runner)

  /** Registers a runner against the account — `POST /user/actions/runners`.
    *
    * '''Never retried.''' Runner names are explicitly not unique, so a repeat registers a second runner and issues a
    * second token. A transport failure therefore leaves the caller genuinely unsure whether a runner exists, which is
    * the honest state of affairs and better than two — [[listRunners]] resolves it.
    *
    * '''The result carries a credential.''' [[com.worxbend.codeberg4s.repositories.actions.RegisteredRunner.token]] is
    * what the runner binary authenticates with; it masks itself in every rendering path, but it is still a secret that
    * has to reach exactly one machine.
    *
    * '''An account-level runner is inherited.''' Every repository the account owns can dispatch to it, so this is a
    * broader grant than registering the same machine against one repository.
    *
    * '''Failures.''' The group contract above.
    */
  def registerRunner(command: RegisterRunner): Future[RegisteredRunner] =
    pipeline.call(UserActionApi.registerRunnerRequest(command), RetryEligibility.Never)(using
      UserAccountDecoders.registeredRunner)

  /** Deletes one of the account's runners — `DELETE /user/actions/runners/{runner_id}`.
    *
    * '''Retried''', because the request names one instance-wide identifier the server never reuses — a runner id is a
    * database row id — so the end state after any number of attempts is the one attempt would have produced, and
    * nothing is created. The one cost a caller has to know: if the first attempt succeeded and its response was lost,
    * the retry addresses something that no longer exists and answers `404`. A `404` from a delete therefore means "it
    * is gone", not necessarily "it was never there".
    *
    * '''Answers `204`.''' Nothing comes back.
    *
    * '''Failures.''' The group contract above.
    */
  def deleteRunner(id: RunnerId): Future[Unit] =
    pipeline.callUnit(UserActionApi.deleteRunnerRequest(id), RetryEligibility.AlwaysRetry)

  /** Obtains the token a new account-level runner registers with — `GET /user/actions/runners/registration-token`.
    *
    * '''A `GET` that hands out a credential.''' It is safe in the RFC 9110 sense — reading it changes nothing on the
    * instance — so it is retried like any other read. What it returns is nonetheless a secret with the authority to
    * attach a machine that will execute workflow code for every repository the account owns, which is why it comes back
    * as a [[com.worxbend.codeberg4s.repositories.actions.RunnerRegistrationToken]] and not as a `String`.
    *
    * Distinct from [[registerRunner]]: that call creates the runner and hands back its token, this one hands back a
    * token the runner binary uses to create itself.
    *
    * '''Failures.''' The group contract above. The spec declares `401` and `403` for this operation and no `404`.
    */
  def runnerRegistrationToken(): Future[RunnerRegistrationToken] =
    pipeline.call(UserActionApi.runnerRegistrationTokenRequest, RetryEligibility.IdempotentOnly)(using
      UserAccountDecoders.registrationToken)

  /** Finds the account's jobs waiting for a runner with the given labels — `GET /user/actions/runners/jobs`.
    *
    * '''Not paged, and that is the endpoint's decision.''' The spec declares no `page` or `limit` for this operation,
    * so the whole result arrives at once and it is a `Vector` rather than a [[com.worxbend.codeberg4s.paging.Page]] — a
    * page reporting a window nobody chose would be a lie about what was requested.
    *
    * '''Labels are comma-joined into one parameter''', which is the encoding Forgejo declares and which has no escape.
    * That is why the elements are [[com.worxbend.codeberg4s.repositories.actions.RunnerLabel]], which rejects a comma
    * at construction. An empty vector sends no filter and asks for every job.
    *
    * '''Failures.''' The group contract above.
    */
  def searchRunnerJobs(labels: Vector[RunnerLabel]): Future[Vector[ActionRunJob]] =
    pipeline.call(UserActionApi.searchRunnerJobsRequest(labels), RetryEligibility.IdempotentOnly)(using
      UserAccountDecoders.jobs)

  // --- secrets --------------------------------------------------------------

  /** Creates or replaces one of the account's secrets — `PUT /user/actions/secrets/{secretname}`.
    *
    * '''One method for both, because the API has one endpoint for both.''' Forgejo answers `201` when the secret was
    * created and `204` when it was replaced, and both are success as far as
    * [[com.worxbend.codeberg4s.core.StatusMapping]] is concerned. Neither carries a body, so there is nothing to return
    * and — since this surface has no secret listing at all — no way to tell the two apart.
    *
    * '''Retried''', because the call is an assignment: it names one secret and states the value it should hold, so
    * applying it twice leaves the account exactly as applying it once would and creates nothing. A secret name is
    * reusable, so a retry after a lost success may be assigning to a secret that was deleted and recreated in between —
    * and the end state is still the one the caller asked for, which is what makes this different from a delete.
    *
    * '''The value is written down exactly once''', in
    * [[com.worxbend.codeberg4s.repositories.actions.wire.SecretOptionDto]], and reaches nothing but the request bytes.
    *
    * '''Failures.''' The group contract above. A `400` means Forgejo rejected the name — it refuses one starting with a
    * digit and one using a reserved `GITHUB_` or `GITEA_` prefix.
    */
  def setSecret(secret: SecretName, value: SecretValue): Future[Unit] =
    pipeline.callUnit(UserActionApi.setSecretRequest(secret, value), RetryEligibility.AlwaysRetry)

  /** Deletes one of the account's secrets — `DELETE /user/actions/secrets/{secretname}`.
    *
    * '''Never retried''', and this is the decision that differs from [[setSecret]]. What the request names is a
    * '''reusable''' name, not an identifier the server never hands out again: if the first attempt succeeded, its
    * response was lost, and the same name was set again in between, the retry deletes a secret the caller never asked
    * to delete — and a secret that is gone cannot be read back to notice. An assignment survives that race because it
    * ends in the requested state; a deletion does not, so this library will not repeat it. A caller who knows no one
    * else writes their secrets can re-issue the call themselves.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def deleteSecret(secret: SecretName): Future[Unit] =
    pipeline.callUnit(UserActionApi.deleteSecretRequest(secret), RetryEligibility.Never)

  // --- variables ------------------------------------------------------------

  /** Lists the account's variables — `GET /user/actions/variables`.
    *
    * '''Values included''', unlike a secret — a variable is configuration, not a credential. See
    * [[com.worxbend.codeberg4s.repositories.actions.ActionVariable]], and note that an account-level variable is
    * readable by every workflow in every repository the account owns.
    *
    * '''Paging.''' As [[listRunners]].
    *
    * '''Failures.''' The group contract above.
    */
  def listVariables(page: PageParams): Future[Page[ActionVariable]] =
    pipeline.callPage(UserActionApi.listVariablesRequest(page), page)(using UserAccountDecoders.variables)

  /** Reads one of the account's variables — `GET /user/actions/variables/{variablename}`.
    *
    * '''Failures.''' The group contract above.
    */
  def variable(name: VariableName): Future[ActionVariable] =
    pipeline.call(UserActionApi.variableRequest(name), RetryEligibility.IdempotentOnly)(using
      UserAccountDecoders.variable)

  /** Creates one of the account's variables — `POST /user/actions/variables/{variablename}`.
    *
    * '''Never retried''', because it is a `POST` and this library never repeats one. The practical effect of a repeat
    * would be a `400` rather than a duplicate — the name is a path segment, so there is only ever one variable under it
    * — but that is Forgejo's behaviour to change, not a promise this library makes on its behalf. [[updateVariable]] is
    * the call that is safe to repeat.
    *
    * '''Answers `201` or `204`, with no body either way''', so there is nothing to return; [[variable]] reads back what
    * was stored.
    *
    * '''Failures.''' The group contract above. A `400` is what an already-existing name produces.
    */
  def createVariable(name: VariableName, command: CreateVariable): Future[Unit] =
    pipeline.callUnit(UserActionApi.createVariableRequest(name, command), RetryEligibility.Never)

  /** Updates one of the account's variables, optionally moving it to a new name —
    * `PUT /user/actions/variables/{variablename}`.
    *
    * '''Whether this is retried depends on the command, and that is deliberate.''' Setting an existing name to a stated
    * value is an assignment and is retried, on the same argument [[setSecret]] gives. A command carrying
    * [[com.worxbend.codeberg4s.repositories.actions.UpdateVariable.renamedTo]] is '''not''': the first attempt moves
    * the variable, and a repeat addresses a name that no longer exists. Choosing per call rather than per endpoint is
    * the only way to be right about both.
    *
    * '''Answers `201` or `204`, with no body either way'''; [[variable]] reads back what was stored, at the new name if
    * the command moved it.
    *
    * '''Failures.''' The group contract above. A `404` means the variable does not exist — this endpoint updates, it
    * does not create.
    */
  def updateVariable(name: VariableName, command: UpdateVariable): Future[Unit] =
    pipeline.callUnit(
      UserActionApi.updateVariableRequest(name, command),
      UserActionApi.updateVariableEligibility(command),
    )

  /** Deletes one of the account's variables — `DELETE /user/actions/variables/{variablename}`.
    *
    * '''Never retried''', for the reason [[deleteSecret]] gives: the name is reusable, so a retry after a lost success
    * can destroy a variable that was recreated in between. Unlike a secret, a variable can at least be read back —
    * [[variable]] and [[listVariables]] are how a caller resolves the uncertainty a transport failure leaves.
    *
    * '''Answers `201` or `204`''', which is what the spec declares for a deletion; both are success.
    *
    * '''Failures.''' The group contract above.
    */
  def deleteVariable(name: VariableName): Future[Unit] =
    pipeline.callUnit(UserActionApi.deleteVariableRequest(name), RetryEligibility.Never)

/** The requests this group issues, its operation ids, and its typed rail. */
object UserActionApi:

  /** The stable operation id of [[UserActionApi.listRunners]]. Safe to alert on. */
  val ListRunnersOperation: String = "users.account.actions.runners.list"

  /** The stable operation id of the single-runner read on [[UserActionApi]]. */
  val GetRunnerOperation: String = "users.account.actions.runners.get"

  /** The stable operation id of [[UserActionApi.registerRunner]]. */
  val RegisterRunnerOperation: String = "users.account.actions.runners.register"

  /** The stable operation id of [[UserActionApi.deleteRunner]]. */
  val DeleteRunnerOperation: String = "users.account.actions.runners.delete"

  /** The stable operation id of [[UserActionApi.runnerRegistrationToken]]. */
  val RunnerRegistrationTokenOperation: String = "users.account.actions.runners.registrationToken"

  /** The stable operation id of [[UserActionApi.searchRunnerJobs]]. */
  val SearchRunnerJobsOperation: String = "users.account.actions.runners.jobs.search"

  /** The stable operation id of [[UserActionApi.setSecret]]. */
  val SetSecretOperation: String = "users.account.actions.secrets.set"

  /** The stable operation id of [[UserActionApi.deleteSecret]]. */
  val DeleteSecretOperation: String = "users.account.actions.secrets.delete"

  /** The stable operation id of [[UserActionApi.listVariables]]. */
  val ListVariablesOperation: String = "users.account.actions.variables.list"

  /** The stable operation id of the single-variable read on [[UserActionApi]]. */
  val GetVariableOperation: String = "users.account.actions.variables.get"

  /** The stable operation id of [[UserActionApi.createVariable]]. */
  val CreateVariableOperation: String = "users.account.actions.variables.create"

  /** The stable operation id of [[UserActionApi.updateVariable]]. */
  val UpdateVariableOperation: String = "users.account.actions.variables.update"

  /** The stable operation id of [[UserActionApi.deleteVariable]]. */
  val DeleteVariableOperation: String = "users.account.actions.variables.delete"

  /** The typed rail of [[UserActionApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.users.account.actions.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: UserActionApi)(using exec: Exec[Future]):

    /** [[UserActionApi.listRunners]] with its failure as a value. */
    def listRunners(
        visibility: RunnerVisibility,
        page: PageParams,
    ): Future[Either[CodebergError, Page[ActionRunner]]] =
      exec.attempt(rail.listRunners(visibility, page))

    /** The single-runner read on [[UserActionApi]], with its failure as a value. */
    def runner(id: RunnerId): Future[Either[CodebergError, ActionRunner]] =
      exec.attempt(rail.runner(id))

    /** [[UserActionApi.registerRunner]] with its failure as a value. */
    def registerRunner(command: RegisterRunner): Future[Either[CodebergError, RegisteredRunner]] =
      exec.attempt(rail.registerRunner(command))

    /** [[UserActionApi.deleteRunner]] with its failure as a value. */
    def deleteRunner(id: RunnerId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteRunner(id))

    /** [[UserActionApi.runnerRegistrationToken]] with its failure as a value. */
    def runnerRegistrationToken(): Future[Either[CodebergError, RunnerRegistrationToken]] =
      exec.attempt(rail.runnerRegistrationToken())

    /** [[UserActionApi.searchRunnerJobs]] with its failure as a value. */
    def searchRunnerJobs(labels: Vector[RunnerLabel]): Future[Either[CodebergError, Vector[ActionRunJob]]] =
      exec.attempt(rail.searchRunnerJobs(labels))

    /** [[UserActionApi.setSecret]] with its failure as a value. */
    def setSecret(secret: SecretName, value: SecretValue): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.setSecret(secret, value))

    /** [[UserActionApi.deleteSecret]] with its failure as a value. */
    def deleteSecret(secret: SecretName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteSecret(secret))

    /** [[UserActionApi.listVariables]] with its failure as a value. */
    def listVariables(page: PageParams): Future[Either[CodebergError, Page[ActionVariable]]] =
      exec.attempt(rail.listVariables(page))

    /** The single-variable read on [[UserActionApi]], with its failure as a value. */
    def variable(name: VariableName): Future[Either[CodebergError, ActionVariable]] =
      exec.attempt(rail.variable(name))

    /** [[UserActionApi.createVariable]] with its failure as a value. */
    def createVariable(name: VariableName, command: CreateVariable): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.createVariable(name, command))

    /** [[UserActionApi.updateVariable]] with its failure as a value. */
    def updateVariable(name: VariableName, command: UpdateVariable): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.updateVariable(name, command))

    /** [[UserActionApi.deleteVariable]] with its failure as a value. */
    def deleteVariable(name: VariableName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteVariable(name))

  /** Whether an update may be repeated, which depends on whether it moves the variable; see
    * [[UserActionApi.updateVariable]].
    *
    * The same two-line policy `RepositoryActionApi` applies to the repository-scoped endpoint, restated rather than
    * shared because that one is `private[actions]` and widening another group's internal to reach it would be a worse
    * coupling than repeating a conditional. It is a decision, not a model — nothing here forks a type.
    */
  private[account] def updateVariableEligibility(command: UpdateVariable): RetryEligibility =
    if command.renamedTo.isEmpty then RetryEligibility.AlwaysRetry else RetryEligibility.Never

  private def listRunnersRequest(visibility: RunnerVisibility, page: PageParams): CodebergRequest =
    AccountRequests.read(
      ListRunnersOperation,
      runnersPath,
      ActionQueries.runners(visibility) ++ ActionQueries.paging(page),
    )

  private def runnerRequest(id: RunnerId): CodebergRequest =
    AccountRequests.read(GetRunnerOperation, runnerPath(id), Nil)

  private def registerRunnerRequest(command: RegisterRunner): CodebergRequest =
    AccountRequests.write(
      RegisterRunnerOperation,
      HttpMethod.Post,
      runnersPath,
      RegisterRunnerOptionDto.render(command),
    )

  private def deleteRunnerRequest(id: RunnerId): CodebergRequest =
    AccountRequests.remove(DeleteRunnerOperation, runnerPath(id))

  private def runnerRegistrationTokenRequest: CodebergRequest =
    AccountRequests.read(RunnerRegistrationTokenOperation, runnersPath :+ "registration-token", Nil)

  private def searchRunnerJobsRequest(labels: Vector[RunnerLabel]): CodebergRequest =
    AccountRequests.read(SearchRunnerJobsOperation, runnersPath :+ "jobs", ActionQueries.runnerJobs(labels))

  private def setSecretRequest(secret: SecretName, value: SecretValue): CodebergRequest =
    AccountRequests.write(SetSecretOperation, HttpMethod.Put, secretPath(secret), SecretOptionDto.render(value))

  private def deleteSecretRequest(secret: SecretName): CodebergRequest =
    AccountRequests.remove(DeleteSecretOperation, secretPath(secret))

  private def listVariablesRequest(page: PageParams): CodebergRequest =
    AccountRequests.read(ListVariablesOperation, variablesPath, ActionQueries.paging(page))

  private def variableRequest(name: VariableName): CodebergRequest =
    AccountRequests.read(GetVariableOperation, variablePath(name), Nil)

  private def createVariableRequest(name: VariableName, command: CreateVariable): CodebergRequest =
    AccountRequests.write(
      CreateVariableOperation,
      HttpMethod.Post,
      variablePath(name),
      VariableOptionDto.renderCreate(command),
    )

  private def updateVariableRequest(name: VariableName, command: UpdateVariable): CodebergRequest =
    AccountRequests.write(
      UpdateVariableOperation,
      HttpMethod.Put,
      variablePath(name),
      VariableOptionDto.renderUpdate(command),
    )

  private def deleteVariableRequest(name: VariableName): CodebergRequest =
    AccountRequests.remove(DeleteVariableOperation, variablePath(name))

  private def runnersPath: List[String] =
    AccountRequests.path("actions", "runners")

  private def runnerPath(id: RunnerId): List[String] =
    runnersPath :+ id.value

  private def secretPath(secret: SecretName): List[String] =
    AccountRequests.path("actions", "secrets", secret.value)

  private def variablesPath: List[String] =
    AccountRequests.path("actions", "variables")

  private def variablePath(name: VariableName): List[String] =
    variablesPath :+ name.value
