package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName

import scala.concurrent.Future

/** What a repository tells a contributor who is about to open an issue: its issue config and its issue templates.
  *
  * Reached as `client.repos.issueConfig`. Both error rails are here (ADR-0005), on the same terms as
  * [[RepositoryHookApi]]: the typed rail is derived from this one by [[com.worxbend.codeberg4s.core.Exec.attempt]], so
  * the two cannot disagree.
  *
  * ==Every operation here reads a file, not a database row==
  *
  * All three routes report what Forgejo found in the repository's default branch under `.forgejo/ISSUE_TEMPLATE` (or
  * one of the several directory and file names it accepts). Nothing here is stored in the forge's database, and nothing
  * here can be written through the API — a caller changes an issue config by committing a file, which is
  * `RepositoryApi`'s business and not this class's. That is why this class is read-only, and why it has no `Never`
  * retry decision to justify: there is no write to decide about.
  *
  * It is also why [[validate]] exists. A file a human wrote can be malformed, and a malformed one is reported as data
  * rather than as a failure — see [[IssueConfigValidation]], and read [[IssueConfigValidation.isValid]] rather than
  * treating a successful call as a pass.
  *
  * ==Evidence==
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response'''; see [[RepositoryHookApi]] for why no
  * fixture exists.
  *
  * ==Failures==
  *
  * The four remote failures are exactly those [[RepositoryHookApi]] lists, and are not repeated here. The only status
  * the spec declares for these three operations is `404`, which covers a repository that does not exist, one that is
  * invisible to these credentials, and one whose issue tracker is disabled.
  *
  * ==Retries==
  *
  * Everything here is a `GET` and uses [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]].
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class RepositoryIssueConfigApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryIssueConfigApi.Attempt = RepositoryIssueConfigApi.Attempt(this)

  /** Reads a repository's issue config — `GET /repos/{owner}/{repo}/issue_config`.
    *
    * '''A repository with no config file still answers `200`.''' Forgejo reports the effective configuration, so the
    * result of this call is not evidence that a config file exists — every field of [[IssueConfig]] is optional for
    * exactly that reason.
    *
    * '''Failures.''' The group contract above.
    */
  def get(owner: Owner, name: RepoName): Future[IssueConfig] =
    pipeline.call(RepositoryIssueConfigApi.getRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryHookDecoders.issueConfig)

  /** Asks whether a repository's issue config parses — `GET /repos/{owner}/{repo}/issue_config/validate`.
    *
    * '''A `200` does not mean valid.''' The route answers `200` with `{"valid": false, "message": "..."}` for a config
    * it could not parse; invalidity is the answer, not a failure. Read [[IssueConfigValidation.isValid]].
    *
    * '''Failures.''' The group contract above. A payload with no `valid` key is a
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at `$.valid` rather than a silent `false`; see
    * [[IssueConfigValidation]] for why that is the safer reading.
    */
  def validate(owner: Owner, name: RepoName): Future[IssueConfigValidation] =
    pipeline.call(RepositoryIssueConfigApi.validateRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryHookDecoders.issueConfigValidation)

  /** Lists a repository's issue templates — `GET /repos/{owner}/{repo}/issue_templates`.
    *
    * '''Not paged, and that is the endpoint's decision.''' The spec declares no `page` or `limit`, so every template
    * arrives at once and the result is a `Vector` rather than a [[com.worxbend.codeberg4s.paging.Page]] — a page
    * reporting a window nobody chose would be a lie about what was requested. The count is bounded by the repository's
    * own `ISSUE_TEMPLATE` directory.
    *
    * '''A template is either Markdown or a form'''; [[IssueTemplate.isForm]] is how to tell, and [[IssueFormField]]
    * explains why a form field's attributes are text rather than a structure.
    *
    * '''Failures.''' The group contract above. A template file that Forgejo could not parse is simply absent from the
    * list — [[validate]] is what reports that something was wrong, and it reports it about the config rather than about
    * a template.
    */
  def templates(owner: Owner, name: RepoName): Future[Vector[IssueTemplate]] =
    pipeline.call(RepositoryIssueConfigApi.templatesRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryHookDecoders.issueTemplates)

/** The requests this group issues, its operation ids, and its typed rail. */
object RepositoryIssueConfigApi:

  /** The stable operation id of the issue-config read on [[RepositoryIssueConfigApi]]. Safe to alert on. */
  val GetOperation: String = "repos.issueConfig.get"

  /** The stable operation id of [[RepositoryIssueConfigApi.validate]]. */
  val ValidateOperation: String = "repos.issueConfig.validate"

  /** The stable operation id of [[RepositoryIssueConfigApi.templates]]. */
  val TemplatesOperation: String = "repos.issueTemplates.list"

  /** The typed rail of [[RepositoryIssueConfigApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as
    * a value.
    *
    * Obtained as `client.repos.issueConfig.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryIssueConfigApi)(using exec: Exec[Future]):

    /** The issue-config read on [[RepositoryIssueConfigApi]], with its failure as a value. */
    def get(owner: Owner, name: RepoName): Future[Either[CodebergError, IssueConfig]] =
      exec.attempt(rail.get(owner, name))

    /** [[RepositoryIssueConfigApi.validate]] with its failure as a value. */
    def validate(owner: Owner, name: RepoName): Future[Either[CodebergError, IssueConfigValidation]] =
      exec.attempt(rail.validate(owner, name))

    /** [[RepositoryIssueConfigApi.templates]] with its failure as a value. */
    def templates(owner: Owner, name: RepoName): Future[Either[CodebergError, Vector[IssueTemplate]]] =
      exec.attempt(rail.templates(owner, name))

  private def getRequest(owner: Owner, name: RepoName): CodebergRequest =
    HookRequests.read(GetOperation, issueConfigPath(owner, name), Nil)

  private def validateRequest(owner: Owner, name: RepoName): CodebergRequest =
    HookRequests.read(ValidateOperation, issueConfigPath(owner, name) :+ "validate", Nil)

  private def templatesRequest(owner: Owner, name: RepoName): CodebergRequest =
    HookRequests.read(TemplatesOperation, HookRequests.repositoryPath(owner, name) :+ "issue_templates", Nil)

  private def issueConfigPath(owner: Owner, name: RepoName): List[String] =
    HookRequests.repositoryPath(owner, name) :+ "issue_config"
