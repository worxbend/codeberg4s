package com.worxbend.codeberg4s.organizations.actions

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RequestBody
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.organizations.OrgName
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.actions.ActionRunJob
import com.worxbend.codeberg4s.repositories.actions.ActionRunner
import com.worxbend.codeberg4s.repositories.actions.ActionSecret
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

/** An organisation's Actions surface: the runners it owns, and the secrets and variables its repositories inherit.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[OrganizationActionApi.attempt]] never
  * fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==This is the repository Actions surface with one path segment changed==
  *
  * `/orgs/{org}/actions/{runners,secrets,variables}` mirrors `/repos/{owner}/{repo}/actions/…` model for model. So
  * nothing is redefined here: [[com.worxbend.codeberg4s.repositories.actions.ActionRunner]],
  * [[com.worxbend.codeberg4s.repositories.actions.ActionSecret]],
  * [[com.worxbend.codeberg4s.repositories.actions.ActionVariable]],
  * [[com.worxbend.codeberg4s.repositories.actions.SecretValue]] and the rest are imported from the group that owns
  * them, and so are their DTOs and their query renderer. An organisation-scoped copy of any of them would be a second
  * spelling of one concept, which rule 1 of [[com.worxbend.codeberg4s.codec.WireConventions]] calls a review-blocking
  * defect.
  *
  * The one thing that could not be reused is the decoder table; see [[OrganizationActionDecoders]] for exactly why, and
  * for what would remove the duplication.
  *
  * ==What is here that is not on the repository surface, and the reverse==
  *
  * Nothing. The organisation surface is a strict '''subset''': there are no runs, no jobs by id, no tasks, no artifacts
  * and no workflow dispatch at organisation scope, because a run belongs to a repository. What an organisation owns is
  * capacity — runners — and configuration its repositories read.
  *
  * '''Scope is what these values mean.''' A secret set here is readable by every workflow in every repository of the
  * organisation, which is a considerably larger blast radius than the repository-scoped call of the same name. A runner
  * registered here executes jobs from any of them.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the organisation does not exist '''or''' is
  *     not visible to the configured credentials — Forgejo does not distinguish the two, on purpose — `401` when a
  *     token was required and none was sent, and `403` when the token lacks the scope. Actions endpoints are token-only
  *     in practice even where `spec/swagger.v1.json` marks security as optional, and `docs/HAZARDS.md` §2 records that
  *     the spec carries no per-operation security information at all. `400` is what these endpoints answer for a
  *     rejected argument; `docs/HAZARDS.md` §4 records Forgejo using `400` where a reader would expect `422`, and the
  *     organisation Actions routes declare `400` and no `422` at all.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field, at its position for a listing — `$[2].name` rather than `$`.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type, so a value that would forge a path is rejected by its own smart constructor before a
  * client is ever involved.
  *
  * ==Retries==
  *
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. Every write is decided per endpoint and
  * each decision is justified where it is made, but the rule they are all decided by is worth stating once:
  * '''[[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]] requires that N attempts leave the instance exactly
  * where one attempt would have.''' That is true when the request names something the server never reuses, or when it
  * sets a named thing to a stated value. It is '''not''' true when the request destroys something addressed by a name
  * that can be handed to a different object between the attempts.
  *
  * That rule puts [[deleteSecret]] and [[deleteVariable]] on [[com.worxbend.codeberg4s.core.RetryEligibility.Never]]
  * while [[com.worxbend.codeberg4s.repositories.actions.RepositoryActionApi.deleteSecret]] and its variable counterpart
  * are on [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]]. '''The two groups genuinely disagree, and this
  * is the deliberate choice''': an organisation secret is shared by every repository in the organisation, so the window
  * in which some other actor recreates `DEPLOY_KEY` between a lost success and its retry is wider here than anywhere
  * else in the library, and the cost of losing that race is destroying a credential this caller never asked to touch.
  * Reconciling the two surfaces means changing the repository group, which this group does not own.
  *
  * ==Secrets==
  *
  * A secret's value goes in and never comes out. [[setSecret]] takes a
  * [[com.worxbend.codeberg4s.repositories.actions.SecretValue]], which masks itself in every rendering path, and the
  * read model [[com.worxbend.codeberg4s.repositories.actions.ActionSecret]] has no value field at all.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class OrganizationActionApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: OrganizationActionApi.Attempt = OrganizationActionApi.Attempt(this)

  // --- runners --------------------------------------------------------------

  /** Lists the runners an organisation can dispatch to — `GET /orgs/{org}/actions/runners`.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5). A page past
    * the end is `200` with `[]`, not a `404`.
    *
    * '''Failures.''' The group contract above.
    *
    * @param visibility
    *   whether to include runners inherited from the instance, or only the organisation's own — see
    *   [[com.worxbend.codeberg4s.repositories.actions.RunnerVisibility]] for why this is not a `Boolean`. The parameter
    *   is always sent, so what a listing contains is a property of the request rather than of the Forgejo version
    *   answering it
    */
  def listRunners(org: OrgName, visibility: RunnerVisibility, page: PageParams): Future[Page[ActionRunner]] =
    pipeline.callPage(OrganizationActionApi.listRunnersRequest(org, visibility, page), page)(using
      OrganizationActionDecoders.runners)

  /** Reads one of an organisation's runners — `GET /orgs/{org}/actions/runners/{runner_id}`.
    *
    * '''Failures.''' The group contract above. A runner that belongs to a repository or to the instance answers `404`
    * here even though [[listRunners]] with [[com.worxbend.codeberg4s.repositories.actions.RunnerVisibility.AllVisible]]
    * may have listed it.
    */
  def runner(org: OrgName, id: RunnerId): Future[ActionRunner] =
    pipeline.call(OrganizationActionApi.runnerRequest(org, id), RetryEligibility.IdempotentOnly)(using
      OrganizationActionDecoders.runner)

  /** Registers a runner against the organisation — `POST /orgs/{org}/actions/runners`.
    *
    * '''Never retried.''' Runner names are explicitly not unique, so a repeat registers a second runner and issues a
    * second token. A transport failure therefore leaves the caller genuinely unsure whether a runner exists, which is
    * the honest state of affairs and better than two — [[listRunners]] resolves it.
    *
    * '''The result carries a credential.''' [[com.worxbend.codeberg4s.repositories.actions.RegisteredRunner.token]] is
    * what the runner binary authenticates with; it masks itself in every rendering path, but it is still a secret that
    * has to reach exactly one machine.
    *
    * '''This is the supported way to obtain a runner token.''' [[runnerRegistrationToken]] is deprecated in Forgejo 15
    * and this call is what the spec points at instead.
    *
    * '''Failures.''' The group contract above. A `400` means Forgejo rejected the payload — most often a blank name,
    * which [[com.worxbend.codeberg4s.repositories.actions.RegisterRunner.named]] has already refused.
    */
  def registerRunner(org: OrgName, command: RegisterRunner): Future[RegisteredRunner] =
    pipeline.call(OrganizationActionApi.registerRunnerRequest(org, command), RetryEligibility.Never)(using
      OrganizationActionDecoders.registeredRunner)

  /** Deletes one of an organisation's runners — `DELETE /orgs/{org}/actions/runners/{runner_id}`.
    *
    * '''Retried''', and it is the only `DELETE` in this group that is. The request names the runner by an instance-wide
    * identifier the server never reuses, so a repeat can only ever address the runner this call meant to remove: N
    * attempts leave the instance exactly where one would have, and nothing is created. The cost is one a caller has to
    * know — if the first attempt succeeded and its response was lost, the retry addresses something that no longer
    * exists and answers `404`. A `404` from this call therefore means "it is gone", not necessarily "it was never
    * there".
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def deleteRunner(org: OrgName, id: RunnerId): Future[Unit] =
    pipeline.callUnit(OrganizationActionApi.deleteRunnerRequest(org, id), RetryEligibility.AlwaysRetry)

  /** Obtains the token a new runner registers with — `GET /orgs/{org}/actions/runners/registration-token`.
    *
    * '''Deprecated by the API, not by this library.''' `spec/swagger.v1.json` marks this operation `deprecated: true`
    * and its description says to use the web UI or [[registerRunner]] instead, as of Forgejo 15. It is implemented
    * because the endpoint still exists and older instances still need it; it is '''not''' marked `@deprecated` in
    * Scala, because that annotation would make every call site — including this class's own typed rail — a compiler
    * warning, and warnings are errors in this build.
    *
    * '''A `GET` that hands out a credential.''' It is safe in the RFC 9110 sense — reading it changes nothing on the
    * instance — so it is retried like any other read. What it returns is nonetheless a secret with the authority to
    * attach a machine that will execute workflow code for every repository in the organisation, which is why it comes
    * back as a [[com.worxbend.codeberg4s.repositories.actions.RunnerRegistrationToken]] and not as a `String`.
    *
    * '''Failures.''' The group contract above. The spec declares no failure status for this operation at all, which is
    * a gap in the spec rather than a promise — `401` and `404` both occur.
    */
  def runnerRegistrationToken(org: OrgName): Future[RunnerRegistrationToken] =
    pipeline.call(OrganizationActionApi.registrationTokenRequest(org), RetryEligibility.IdempotentOnly)(using
      OrganizationActionDecoders.registrationToken)

  /** Finds the organisation's jobs waiting for a runner with the given labels — `GET /orgs/{org}/actions/runners/jobs`.
    *
    * '''Not paged, and that is the endpoint's decision.''' The spec declares no `page` or `limit` for this operation,
    * so the whole result arrives at once and it is a `Vector` rather than a [[com.worxbend.codeberg4s.paging.Page]] — a
    * page reporting a window nobody chose would be a lie about what was requested.
    *
    * '''Labels are comma-joined into one parameter''', which is the encoding Forgejo declares and which has no escape.
    * That is why the elements are [[com.worxbend.codeberg4s.repositories.actions.RunnerLabel]], which rejects a comma
    * at construction. An empty vector sends no filter and asks for every job.
    *
    * '''Failures.''' The group contract above. This is the one operation in the group whose spec declares a `403` and
    * no `404`.
    */
  def searchRunnerJobs(org: OrgName, labels: Vector[RunnerLabel]): Future[Vector[ActionRunJob]] =
    pipeline.call(OrganizationActionApi.searchRunnerJobsRequest(org, labels), RetryEligibility.IdempotentOnly)(using
      OrganizationActionDecoders.jobs)

  // --- secrets --------------------------------------------------------------

  /** Lists an organisation's secrets — `GET /orgs/{org}/actions/secrets`.
    *
    * '''Names and timestamps only.''' No endpoint returns a secret's value, so
    * [[com.worxbend.codeberg4s.repositories.actions.ActionSecret]] has no field for one.
    *
    * '''Paging.''' As [[listRunners]]: the `Link` header decides, not the number of items.
    *
    * '''Failures.''' The group contract above.
    */
  def listSecrets(org: OrgName, page: PageParams): Future[Page[ActionSecret]] =
    pipeline.callPage(OrganizationActionApi.listSecretsRequest(org, page), page)(using
      OrganizationActionDecoders.secrets)

  /** Creates or replaces an organisation secret — `PUT /orgs/{org}/actions/secrets/{secretname}`.
    *
    * '''One method for both, because the API has one endpoint for both.''' Forgejo answers `201` when the secret was
    * created and `204` when it was replaced, and both are success as far as
    * [[com.worxbend.codeberg4s.core.StatusMapping]] is concerned. Neither carries a body, so there is nothing to return
    * and nothing to tell the two apart with — [[listSecrets]] before the call is the only way to know which one will
    * happen.
    *
    * '''Retried''', unlike [[deleteSecret]], and the difference is the whole of the group's retry rule. This call sets
    * a named resource to a stated value: whatever else happens between the attempts, the end state after N of them is
    * the secret named `secret` holding `value`, which is exactly the end state one attempt would have produced. A
    * `DELETE` cannot say that, because what it removes depends on what the name pointed at when it arrived.
    *
    * '''This secret is readable by every repository in the organisation.''' That is what organisation scope means, and
    * it is worth being sure of before setting one.
    *
    * '''The value is written down exactly once''', in
    * [[com.worxbend.codeberg4s.repositories.actions.wire.SecretOptionDto]], and reaches nothing but the request bytes.
    *
    * '''Failures.''' The group contract above. A `400` means Forgejo rejected the name — it refuses one starting with a
    * digit and one using a reserved `GITHUB_` or `GITEA_` prefix.
    */
  def setSecret(org: OrgName, secret: SecretName, value: SecretValue): Future[Unit] =
    pipeline.callUnit(OrganizationActionApi.setSecretRequest(org, secret, value), RetryEligibility.AlwaysRetry)

  /** Deletes an organisation secret — `DELETE /orgs/{org}/actions/secrets/{secretname}`.
    *
    * '''Never retried, and this is where this group parts company with the repository one.''' A secret is addressed by
    * a name, and a name is reusable: if the first attempt succeeded and its response was lost, a retry issued a moment
    * later deletes whatever `secretname` points at '''then''' — which, in an organisation whose repositories and
    * automation all share these secrets, may be a credential another actor recreated in between. Losing that race
    * destroys something this caller never asked to touch, and no response tells them it happened.
    *
    * The cost of `Never` is far smaller: a transport failure leaves the caller unsure whether the secret is gone, and
    * [[listSecrets]] answers that in one call.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def deleteSecret(org: OrgName, secret: SecretName): Future[Unit] =
    pipeline.callUnit(OrganizationActionApi.deleteSecretRequest(org, secret), RetryEligibility.Never)

  // --- variables ------------------------------------------------------------

  /** Lists an organisation's variables — `GET /orgs/{org}/actions/variables`.
    *
    * '''Values included''', unlike [[listSecrets]] — a variable is configuration, not a credential. See
    * [[com.worxbend.codeberg4s.repositories.actions.ActionVariable]], and note that putting a credential in one makes
    * it readable by anyone who can read the organisation's Actions configuration.
    *
    * '''Paging.''' As [[listRunners]].
    *
    * '''Failures.''' The group contract above.
    */
  def listVariables(org: OrgName, page: PageParams): Future[Page[ActionVariable]] =
    pipeline.callPage(OrganizationActionApi.listVariablesRequest(org, page), page)(using
      OrganizationActionDecoders.variables)

  /** Reads one organisation variable — `GET /orgs/{org}/actions/variables/{variablename}`.
    *
    * '''Failures.''' The group contract above.
    */
  def variable(org: OrgName, name: VariableName): Future[ActionVariable] =
    pipeline.call(OrganizationActionApi.variableRequest(org, name), RetryEligibility.IdempotentOnly)(using
      OrganizationActionDecoders.variable)

  /** Creates an organisation variable — `POST /orgs/{org}/actions/variables/{variablename}`.
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
  def createVariable(org: OrgName, name: VariableName, command: CreateVariable): Future[Unit] =
    pipeline.callUnit(OrganizationActionApi.createVariableRequest(org, name, command), RetryEligibility.Never)

  /** Updates an organisation variable, optionally moving it to a new name —
    * `PUT /orgs/{org}/actions/variables/{variablename}`.
    *
    * '''Whether this is retried depends on the command, and that is deliberate.''' Setting an existing name to a stated
    * value leaves the same end state after any number of attempts, so a plain
    * [[com.worxbend.codeberg4s.repositories.actions.UpdateVariable.of]] is retried. A command carrying
    * [[com.worxbend.codeberg4s.repositories.actions.UpdateVariable.renamedTo]] is '''not''': the first attempt moves
    * the variable, and a repeat addresses a name that no longer exists — or, worse, one that something else has since
    * taken. Choosing per call rather than per endpoint is the only way to be right about both.
    *
    * '''Answers `201` or `204`, with no body either way'''; [[variable]] reads back what was stored, at the new name if
    * the command moved it.
    *
    * '''Failures.''' The group contract above. A `404` means the variable does not exist — this endpoint updates, it
    * does not create.
    */
  def updateVariable(org: OrgName, name: VariableName, command: UpdateVariable): Future[Unit] =
    pipeline.callUnit(
      OrganizationActionApi.updateVariableRequest(org, name, command),
      OrganizationActionApi.updateVariableEligibility(command),
    )

  /** Deletes an organisation variable — `DELETE /orgs/{org}/actions/variables/{variablename}`.
    *
    * '''Never retried''', for the reason [[deleteSecret]] gives: a variable is addressed by a reusable name, so a retry
    * after a lost success can destroy a variable something else recreated in between. [[listVariables]] is how a caller
    * finds out whether the first attempt landed.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def deleteVariable(org: OrgName, name: VariableName): Future[Unit] =
    pipeline.callUnit(OrganizationActionApi.deleteVariableRequest(org, name), RetryEligibility.Never)

/** The requests this group issues, its operation ids, and its typed rail. */
object OrganizationActionApi:

  /** The stable operation id [[OrganizationActionApi.listRunners]] copies into every failure's
    * [[com.worxbend.codeberg4s.CallContext]]. Safe to alert on.
    */
  val ListRunnersOperation: String = "orgs.actions.runners.list"

  /** The stable operation id of the single-runner read on [[OrganizationActionApi]]. */
  val GetRunnerOperation: String = "orgs.actions.runners.get"

  /** The stable operation id of [[OrganizationActionApi.registerRunner]]. */
  val RegisterRunnerOperation: String = "orgs.actions.runners.register"

  /** The stable operation id of [[OrganizationActionApi.deleteRunner]]. */
  val DeleteRunnerOperation: String = "orgs.actions.runners.delete"

  /** The stable operation id of [[OrganizationActionApi.runnerRegistrationToken]]. */
  val RunnerRegistrationTokenOperation: String = "orgs.actions.runners.registrationToken"

  /** The stable operation id of [[OrganizationActionApi.searchRunnerJobs]]. */
  val SearchRunnerJobsOperation: String = "orgs.actions.runners.jobs.search"

  /** The stable operation id of [[OrganizationActionApi.listSecrets]]. */
  val ListSecretsOperation: String = "orgs.actions.secrets.list"

  /** The stable operation id of [[OrganizationActionApi.setSecret]]. */
  val SetSecretOperation: String = "orgs.actions.secrets.set"

  /** The stable operation id of [[OrganizationActionApi.deleteSecret]]. */
  val DeleteSecretOperation: String = "orgs.actions.secrets.delete"

  /** The stable operation id of [[OrganizationActionApi.listVariables]]. */
  val ListVariablesOperation: String = "orgs.actions.variables.list"

  /** The stable operation id of the single-variable read on [[OrganizationActionApi]]. */
  val GetVariableOperation: String = "orgs.actions.variables.get"

  /** The stable operation id of [[OrganizationActionApi.createVariable]]. */
  val CreateVariableOperation: String = "orgs.actions.variables.create"

  /** The stable operation id of [[OrganizationActionApi.updateVariable]]. */
  val UpdateVariableOperation: String = "orgs.actions.variables.update"

  /** The stable operation id of [[OrganizationActionApi.deleteVariable]]. */
  val DeleteVariableOperation: String = "orgs.actions.variables.delete"

  /** The typed rail of [[OrganizationActionApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Each method is the convenience-rail method with its failure channel materialised and nothing else, so an operation
    * exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: OrganizationActionApi)(using exec: Exec[Future]):

    /** [[OrganizationActionApi.listRunners]] with its failure as a value. */
    def listRunners(
        org: OrgName,
        visibility: RunnerVisibility,
        page: PageParams,
    ): Future[Either[CodebergError, Page[ActionRunner]]] =
      exec.attempt(rail.listRunners(org, visibility, page))

    /** The single-runner read on [[OrganizationActionApi]], with its failure as a value. */
    def runner(org: OrgName, id: RunnerId): Future[Either[CodebergError, ActionRunner]] =
      exec.attempt(rail.runner(org, id))

    /** [[OrganizationActionApi.registerRunner]] with its failure as a value. */
    def registerRunner(org: OrgName, command: RegisterRunner): Future[Either[CodebergError, RegisteredRunner]] =
      exec.attempt(rail.registerRunner(org, command))

    /** [[OrganizationActionApi.deleteRunner]] with its failure as a value. */
    def deleteRunner(org: OrgName, id: RunnerId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteRunner(org, id))

    /** [[OrganizationActionApi.runnerRegistrationToken]] with its failure as a value. */
    def runnerRegistrationToken(org: OrgName): Future[Either[CodebergError, RunnerRegistrationToken]] =
      exec.attempt(rail.runnerRegistrationToken(org))

    /** [[OrganizationActionApi.searchRunnerJobs]] with its failure as a value. */
    def searchRunnerJobs(
        org: OrgName,
        labels: Vector[RunnerLabel],
    ): Future[Either[CodebergError, Vector[ActionRunJob]]] =
      exec.attempt(rail.searchRunnerJobs(org, labels))

    /** [[OrganizationActionApi.listSecrets]] with its failure as a value. */
    def listSecrets(org: OrgName, page: PageParams): Future[Either[CodebergError, Page[ActionSecret]]] =
      exec.attempt(rail.listSecrets(org, page))

    /** [[OrganizationActionApi.setSecret]] with its failure as a value. */
    def setSecret(org: OrgName, secret: SecretName, value: SecretValue): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.setSecret(org, secret, value))

    /** [[OrganizationActionApi.deleteSecret]] with its failure as a value. */
    def deleteSecret(org: OrgName, secret: SecretName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteSecret(org, secret))

    /** [[OrganizationActionApi.listVariables]] with its failure as a value. */
    def listVariables(org: OrgName, page: PageParams): Future[Either[CodebergError, Page[ActionVariable]]] =
      exec.attempt(rail.listVariables(org, page))

    /** The single-variable read on [[OrganizationActionApi]], with its failure as a value. */
    def variable(org: OrgName, name: VariableName): Future[Either[CodebergError, ActionVariable]] =
      exec.attempt(rail.variable(org, name))

    /** [[OrganizationActionApi.createVariable]] with its failure as a value. */
    def createVariable(
        org: OrgName,
        name: VariableName,
        command: CreateVariable,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.createVariable(org, name, command))

    /** [[OrganizationActionApi.updateVariable]] with its failure as a value. */
    def updateVariable(
        org: OrgName,
        name: VariableName,
        command: UpdateVariable,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.updateVariable(org, name, command))

    /** [[OrganizationActionApi.deleteVariable]] with its failure as a value. */
    def deleteVariable(org: OrgName, name: VariableName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteVariable(org, name))

  /** Whether an update may be repeated, which depends on whether it moves the variable; see
    * [[OrganizationActionApi.updateVariable]].
    *
    * The same decision `RepositoryActionApi` makes for the repository-scoped endpoint, restated rather than shared
    * because that method is private to the repository group's own package.
    */
  private[actions] def updateVariableEligibility(command: UpdateVariable): RetryEligibility =
    if command.renamedTo.isEmpty then RetryEligibility.AlwaysRetry else RetryEligibility.Never

  private def listRunnersRequest(org: OrgName, visibility: RunnerVisibility, page: PageParams): CodebergRequest =
    read(ListRunnersOperation, runnersPath(org), ActionQueries.runners(visibility) ++ ActionQueries.paging(page))

  private def runnerRequest(org: OrgName, id: RunnerId): CodebergRequest =
    read(GetRunnerOperation, runnerPath(org, id), Nil)

  private def registerRunnerRequest(org: OrgName, command: RegisterRunner): CodebergRequest =
    write(RegisterRunnerOperation, HttpMethod.Post, runnersPath(org), RegisterRunnerOptionDto.render(command))

  private def deleteRunnerRequest(org: OrgName, id: RunnerId): CodebergRequest =
    remove(DeleteRunnerOperation, runnerPath(org, id))

  private def registrationTokenRequest(org: OrgName): CodebergRequest =
    read(RunnerRegistrationTokenOperation, runnersPath(org) :+ "registration-token", Nil)

  private def searchRunnerJobsRequest(org: OrgName, labels: Vector[RunnerLabel]): CodebergRequest =
    read(SearchRunnerJobsOperation, runnersPath(org) :+ "jobs", ActionQueries.runnerJobs(labels))

  private def listSecretsRequest(org: OrgName, page: PageParams): CodebergRequest =
    read(ListSecretsOperation, secretsPath(org), ActionQueries.paging(page))

  private def setSecretRequest(org: OrgName, secret: SecretName, value: SecretValue): CodebergRequest =
    write(SetSecretOperation, HttpMethod.Put, secretPath(org, secret), SecretOptionDto.render(value))

  private def deleteSecretRequest(org: OrgName, secret: SecretName): CodebergRequest =
    remove(DeleteSecretOperation, secretPath(org, secret))

  private def listVariablesRequest(org: OrgName, page: PageParams): CodebergRequest =
    read(ListVariablesOperation, variablesPath(org), ActionQueries.paging(page))

  private def variableRequest(org: OrgName, name: VariableName): CodebergRequest =
    read(GetVariableOperation, variablePath(org, name), Nil)

  private def createVariableRequest(org: OrgName, name: VariableName, command: CreateVariable): CodebergRequest =
    write(
      CreateVariableOperation,
      HttpMethod.Post,
      variablePath(org, name),
      VariableOptionDto.renderCreate(command),
    )

  private def updateVariableRequest(org: OrgName, name: VariableName, command: UpdateVariable): CodebergRequest =
    write(
      UpdateVariableOperation,
      HttpMethod.Put,
      variablePath(org, name),
      VariableOptionDto.renderUpdate(command),
    )

  private def deleteVariableRequest(org: OrgName, name: VariableName): CodebergRequest =
    remove(DeleteVariableOperation, variablePath(org, name))

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

  private def actionsPath(org: OrgName): List[String] =
    List("orgs", org.value, "actions")

  private def runnersPath(org: OrgName): List[String] =
    actionsPath(org) :+ "runners"

  private def runnerPath(org: OrgName, id: RunnerId): List[String] =
    runnersPath(org) :+ id.value

  private def secretsPath(org: OrgName): List[String] =
    actionsPath(org) :+ "secrets"

  private def secretPath(org: OrgName, secret: SecretName): List[String] =
    secretsPath(org) :+ secret.value

  private def variablesPath(org: OrgName): List[String] =
    actionsPath(org) :+ "variables"

  private def variablePath(org: OrgName, name: VariableName): List[String] =
    variablesPath(org) :+ name.value
