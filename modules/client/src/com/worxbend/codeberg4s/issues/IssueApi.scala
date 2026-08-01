package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RequestBody
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.issues.wire.CommentDto
import com.worxbend.codeberg4s.issues.wire.CreateIssueCommentOptionDto
import com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto
import com.worxbend.codeberg4s.issues.wire.CreateLabelOptionDto
import com.worxbend.codeberg4s.issues.wire.EditIssueOptionDto
import com.worxbend.codeberg4s.issues.wire.IssueDto
import com.worxbend.codeberg4s.issues.wire.IssueQueries
import com.worxbend.codeberg4s.issues.wire.LabelDto
import com.worxbend.codeberg4s.issues.wire.MilestoneDto
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName

import scala.concurrent.Future

/** Issue endpoints, together with the comments, labels and milestones that hang off them.
  *
  * Reached as `client.issues`. Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[IssueApi.attempt]] never fail and return
  * an `Either` instead. The typed rail is derived from this one by [[com.worxbend.codeberg4s.core.Exec.attempt]], so
  * the two cannot disagree about what an operation does.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository does not exist '''or''' is
  *     private to credentials the client does not have — Forgejo does not distinguish the two, on purpose — `401` when
  *     a token was required and none was sent, and `403` when the token lacks the scope. `422` '''and''' `400` both
  *     mean the request was rejected as invalid: `docs/HAZARDS.md` §4 captured Forgejo using `400` for a malformed
  *     identifier and `422` for a malformed timestamp, so a caller checking only for `422` will miss half of them.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type — [[com.worxbend.codeberg4s.repositories.Owner]], [[IssueNumber]], [[LabelName]] — so a
  * value that would forge a path or a query parameter is rejected by its own smart constructor before a client is ever
  * involved.
  *
  * ==Retries==
  *
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. Every write here uses
  * [[com.worxbend.codeberg4s.core.RetryEligibility.Never]], including the `PATCH`: Forgejo offers no idempotency key,
  * so a retried `POST` files a second issue and a retried `PATCH` re-applies an edit against whatever the resource has
  * become in the meantime. Neither is a decision this library makes on a caller's behalf.
  */
final class IssueApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: IssueApi.Attempt = IssueApi.Attempt(this)

  /** Lists a repository's issues — `GET /repos/{owner}/{repo}/issues`.
    *
    * '''The listing contains pull requests.''' Forgejo serves both from this endpoint; [[Issue.isPullRequest]] is how
    * they are told apart. `golden/issue/list-labelled.json` is a capture of this endpoint in which every element is a
    * pull request.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value, so `items.size < limit` fires on page
    * one of thirty-two (`docs/HAZARDS.md` §5). A page past the end is `200` with `[]`, not a `404`.
    *
    * '''Failures.''' The group contract above. A `422` here most often means a malformed `since` or `before`.
    *
    * @param query
    *   the filters to apply; [[IssueQuery.Empty]] asks for the instance's default, which is open issues only
    * @param page
    *   which window to fetch, and how large
    */
  def list(owner: Owner, name: RepoName, query: IssueQuery, page: PageParams): Future[Page[Issue]] =
    pipeline.callPage(IssueApi.listRequest(owner, name, query, page), page)(using IssueApi.IssuesDecoder)

  /** Reads one issue — `GET /repos/{owner}/{repo}/issues/{index}`.
    *
    * '''Failures.''' The group contract above. `404` covers both "no such issue in this repository" and "no such
    * repository"; the response body's `errors` array is the only thing that distinguishes them, and it is not always
    * present.
    *
    * @param number
    *   the per-repository issue number, '''not''' [[Issue.id]] — see [[IssueNumber]]
    */
  def get(owner: Owner, name: RepoName, number: IssueNumber): Future[Issue] =
    val request = IssueApi.getRequest(owner, name, number)

    pipeline.call(request, RetryEligibility.IdempotentOnly)(using IssueApi.IssueDecoder)

  /** Opens an issue — `POST /repos/{owner}/{repo}/issues`.
    *
    * '''Never retried.''' Repeating this call files a second issue, and Forgejo has no idempotency key that would let
    * the instance recognise the repeat. A transport failure therefore leaves the caller genuinely unsure whether the
    * issue exists, which is the honest state of affairs and better than two issues.
    *
    * '''Failures.''' The group contract above, plus `403` when issues are disabled on the repository and `423` when the
    * repository is archived. A `422` means Forgejo rejected the payload — an assignee who cannot be assigned, a
    * milestone from another repository.
    *
    * @param command
    *   what to create; built from [[CreateIssue.of]], which has already rejected a blank title
    */
  def create(owner: Owner, name: RepoName, command: CreateIssue): Future[Issue] =
    pipeline.call(IssueApi.createRequest(owner, name, command), RetryEligibility.Never)(using IssueApi.IssueDecoder)

  /** Edits an issue — `PATCH /repos/{owner}/{repo}/issues/{index}`.
    *
    * '''Never retried''', even though `PATCH` on a specific resource looks idempotent. It is not, here: the body is a
    * partial update applied to whatever the issue has become, so a repeat after a transport failure can overwrite a
    * change someone else made in between. A caller who knows their edit is safe to repeat can re-issue it themselves.
    *
    * '''Only what the command sets is sent'''; everything else keeps its current value. Clearing a deadline is
    * [[EditIssue.withoutDueDate]], and replacing assignees with none is [[EditIssue.assignedTo]] with an empty vector —
    * see [[EditIssue]] for why those are different from doing nothing.
    *
    * '''Failures.''' The group contract above. Forgejo answers a successful edit with `201`, not `200`; both are
    * success as far as [[com.worxbend.codeberg4s.core.StatusMapping]] is concerned.
    */
  def edit(owner: Owner, name: RepoName, number: IssueNumber, command: EditIssue): Future[Issue] =
    pipeline.call(IssueApi.editRequest(owner, name, number, command), RetryEligibility.Never)(using
      IssueApi.IssueDecoder)

  /** Lists an issue's comments — `GET /repos/{owner}/{repo}/issues/{index}/comments`.
    *
    * '''Paging on this endpoint is weaker than on the others, and a caller has to know it.''' The pinned spec declares
    * no `page` or `limit` parameter for this operation, and the captured response (`golden/issue/comments-list.json`)
    * carried `X-Total-Count` but '''no''' `Link` header. `page` and `limit` are still sent, because they cost nothing
    * and Forgejo honours them wherever it supports them — but two things follow that do not hold for [[list]]:
    *
    *   - the returned page always reports itself as the last one, since `nextPage` is read from `rel="next"` and there
    *     is no `Link` header to read it from;
    *   - an instance that ignores the parameters answers every request with the '''complete''' comment list, so asking
    *     for page two may return the same items as page one rather than nothing.
    *
    * Compare [[Page.totalCount]] with [[Page.size]] to find out which happened.
    *
    * '''Failures.''' The group contract above, plus `500`, which the spec declares for this operation and which arrives
    * as [[com.worxbend.codeberg4s.CodebergError.Api]] like any other status.
    */
  def listComments(owner: Owner, name: RepoName, number: IssueNumber, page: PageParams): Future[Page[Comment]] =
    pipeline.callPage(IssueApi.listCommentsRequest(owner, name, number, page), page)(using IssueApi.CommentsDecoder)

  /** Comments on an issue — `POST /repos/{owner}/{repo}/issues/{index}/comments`.
    *
    * '''Never retried''', for the reason [[create]] gives: a repeat posts the comment twice.
    *
    * '''Failures.''' The group contract above, plus `423` when the issue is locked and `403` when the token may read
    * the repository but not write to it.
    */
  def createComment(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      command: CreateComment,
  ): Future[Comment] =
    pipeline.call(IssueApi.createCommentRequest(owner, name, number, command), RetryEligibility.Never)(using
      IssueApi.CommentDecoder)

  /** Lists a repository's labels — `GET /repos/{owner}/{repo}/labels`.
    *
    * These are the labels the repository '''offers''', not the ones on any particular issue; an issue's own labels
    * arrive on [[Issue.labels]].
    *
    * '''Paging.''' The endpoint declares `page` and `limit` and reports `X-Total-Count`, but `golden/MANIFEST.md`
    * records that it sends no `Link` header, so — as with [[listComments]] — the returned page reports itself as the
    * last one whatever the total says. Compare [[Page.totalCount]] with [[Page.size]] to decide whether to ask for
    * another window.
    *
    * '''Failures.''' The group contract above.
    */
  def listLabels(owner: Owner, name: RepoName, page: PageParams): Future[Page[Label]] =
    pipeline.callPage(IssueApi.listLabelsRequest(owner, name, page), page)(using IssueApi.LabelsDecoder)

  /** Creates a label on a repository — `POST /repos/{owner}/{repo}/labels`.
    *
    * '''Never retried''', for the reason [[create]] gives: Forgejo does not reject a duplicate label name, so a repeat
    * leaves two identical labels with different ids.
    *
    * '''Failures.''' The group contract above. A `422` means Forgejo rejected the name or the colour — the colour is
    * sent in the `#rrggbb` form it documents, so a `422` on colour is unlikely.
    */
  def createLabel(owner: Owner, name: RepoName, command: CreateLabel): Future[Label] =
    val request = IssueApi.createLabelRequest(owner, name, command)

    pipeline.call(request, RetryEligibility.Never)(using IssueApi.LabelDecoder)

  /** Lists a repository's milestones — `GET /repos/{owner}/{repo}/milestones`.
    *
    * '''Paging.''' As [[listLabels]]: `page` and `limit` are declared and `X-Total-Count` is sent, but no `Link`
    * header, so the returned page always reports itself as the last one.
    *
    * '''Failures.''' The group contract above.
    *
    * @param state
    *   which milestones to include. Explicit rather than optional here, unlike [[IssueQuery.state]], because the
    *   endpoint has no other filter worth naming and Forgejo's silent default of open-only surprises callers
    */
  def listMilestones(owner: Owner, name: RepoName, state: StateFilter, page: PageParams): Future[Page[Milestone]] =
    pipeline.callPage(IssueApi.listMilestonesRequest(owner, name, state, page), page)(using IssueApi.MilestonesDecoder)

  /** Reads one milestone — `GET /repos/{owner}/{repo}/milestones/{id}`.
    *
    * '''Failures.''' The group contract above. Forgejo accepts either a milestone id or a milestone title in this path
    * segment; this library sends only the id, because a title is not unique and is freely renamed.
    */
  def getMilestone(owner: Owner, name: RepoName, id: MilestoneId): Future[Milestone] =
    pipeline.call(IssueApi.getMilestoneRequest(owner, name, id), RetryEligibility.IdempotentOnly)(using
      IssueApi.MilestoneDecoder)

/** The requests this group issues, its operation ids, and its typed rail. */
object IssueApi:

  /** The stable operation id [[IssueApi.list]] copies into every failure's [[com.worxbend.codeberg4s.CallContext]].
    * Safe to alert on.
    */
  val ListOperation: String = "issues.list"

  /** The stable operation id of the single-issue read on [[IssueApi]]. */
  val GetOperation: String = "issues.get"

  /** The stable operation id of [[IssueApi.create]]. */
  val CreateOperation: String = "issues.create"

  /** The stable operation id of [[IssueApi.edit]]. */
  val EditOperation: String = "issues.edit"

  /** The stable operation id of [[IssueApi.listComments]]. */
  val ListCommentsOperation: String = "issues.comments.list"

  /** The stable operation id of [[IssueApi.createComment]]. */
  val CreateCommentOperation: String = "issues.comments.create"

  /** The stable operation id of [[IssueApi.listLabels]]. */
  val ListLabelsOperation: String = "issues.labels.list"

  /** The stable operation id of [[IssueApi.createLabel]]. */
  val CreateLabelOperation: String = "issues.labels.create"

  /** The stable operation id of [[IssueApi.listMilestones]]. */
  val ListMilestonesOperation: String = "issues.milestones.list"

  /** The stable operation id of [[IssueApi.getMilestone]]. */
  val GetMilestoneOperation: String = "issues.milestones.get"

  /** The typed rail of [[IssueApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.issues.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: IssueApi)(using exec: Exec[Future]):

    /** [[IssueApi.list]] with its failure as a value. */
    def list(
        owner: Owner,
        name: RepoName,
        query: IssueQuery,
        page: PageParams,
    ): Future[Either[CodebergError, Page[Issue]]] =
      exec.attempt(rail.list(owner, name, query, page))

    /** The single-issue read on [[IssueApi]], with its failure as a value. */
    def get(owner: Owner, name: RepoName, number: IssueNumber): Future[Either[CodebergError, Issue]] =
      exec.attempt(rail.get(owner, name, number))

    /** [[IssueApi.create]] with its failure as a value. */
    def create(owner: Owner, name: RepoName, command: CreateIssue): Future[Either[CodebergError, Issue]] =
      exec.attempt(rail.create(owner, name, command))

    /** [[IssueApi.edit]] with its failure as a value. */
    def edit(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        command: EditIssue,
    ): Future[Either[CodebergError, Issue]] =
      exec.attempt(rail.edit(owner, name, number, command))

    /** [[IssueApi.listComments]] with its failure as a value. */
    def listComments(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        page: PageParams,
    ): Future[Either[CodebergError, Page[Comment]]] =
      exec.attempt(rail.listComments(owner, name, number, page))

    /** [[IssueApi.createComment]] with its failure as a value. */
    def createComment(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        command: CreateComment,
    ): Future[Either[CodebergError, Comment]] =
      exec.attempt(rail.createComment(owner, name, number, command))

    /** [[IssueApi.listLabels]] with its failure as a value. */
    def listLabels(owner: Owner, name: RepoName, page: PageParams): Future[Either[CodebergError, Page[Label]]] =
      exec.attempt(rail.listLabels(owner, name, page))

    /** [[IssueApi.createLabel]] with its failure as a value. */
    def createLabel(owner: Owner, name: RepoName, command: CreateLabel): Future[Either[CodebergError, Label]] =
      exec.attempt(rail.createLabel(owner, name, command))

    /** [[IssueApi.listMilestones]] with its failure as a value. */
    def listMilestones(
        owner: Owner,
        name: RepoName,
        state: StateFilter,
        page: PageParams,
    ): Future[Either[CodebergError, Page[Milestone]]] =
      exec.attempt(rail.listMilestones(owner, name, state, page))

    /** [[IssueApi.getMilestone]] with its failure as a value. */
    def getMilestone(owner: Owner, name: RepoName, id: MilestoneId): Future[Either[CodebergError, Milestone]] =
      exec.attempt(rail.getMilestone(owner, name, id))

  private def listRequest(owner: Owner, name: RepoName, query: IssueQuery, page: PageParams): CodebergRequest =
    read(ListOperation, issuesPath(owner, name), IssueQueries.issues(query) ++ IssueQueries.paging(page))

  private def getRequest(owner: Owner, name: RepoName, number: IssueNumber): CodebergRequest =
    read(GetOperation, issuePath(owner, name, number), Nil)

  private def createRequest(owner: Owner, name: RepoName, command: CreateIssue): CodebergRequest =
    write(CreateOperation, HttpMethod.Post, issuesPath(owner, name), CreateIssueOptionDto.render(command))

  private def editRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      command: EditIssue,
  ): CodebergRequest =
    write(EditOperation, HttpMethod.Patch, issuePath(owner, name, number), EditIssueOptionDto.render(command))

  private def listCommentsRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      page: PageParams,
  ): CodebergRequest =
    read(ListCommentsOperation, issuePath(owner, name, number) :+ "comments", IssueQueries.paging(page))

  private def createCommentRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      command: CreateComment,
  ): CodebergRequest =
    write(
      CreateCommentOperation,
      HttpMethod.Post,
      issuePath(owner, name, number) :+ "comments",
      CreateIssueCommentOptionDto.render(command),
    )

  private def listLabelsRequest(owner: Owner, name: RepoName, page: PageParams): CodebergRequest =
    read(ListLabelsOperation, labelsPath(owner, name), IssueQueries.paging(page))

  private def createLabelRequest(owner: Owner, name: RepoName, command: CreateLabel): CodebergRequest =
    write(CreateLabelOperation, HttpMethod.Post, labelsPath(owner, name), CreateLabelOptionDto.render(command))

  private def listMilestonesRequest(
      owner: Owner,
      name: RepoName,
      state: StateFilter,
      page: PageParams,
  ): CodebergRequest =
    read(
      ListMilestonesOperation,
      milestonesPath(owner, name),
      IssueQueries.milestones(state) ++ IssueQueries.paging(page),
    )

  private def getMilestoneRequest(owner: Owner, name: RepoName, id: MilestoneId): CodebergRequest =
    read(GetMilestoneOperation, milestonesPath(owner, name) :+ id.value.toString, Nil)

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

  private def issuesPath(owner: Owner, name: RepoName): List[String] =
    List("repos", owner.value, name.value, "issues")

  private def issuePath(owner: Owner, name: RepoName, number: IssueNumber): List[String] =
    issuesPath(owner, name) :+ number.value.toString

  private def labelsPath(owner: Owner, name: RepoName): List[String] =
    List("repos", owner.value, name.value, "labels")

  private def milestonesPath(owner: Owner, name: RepoName): List[String] =
    List("repos", owner.value, name.value, "milestones")

  private val IssueDecoder: Decode[Issue] =
    WireDecode.of(Json.decoder[IssueDto])(_.toDomain)

  private val IssuesDecoder: Decode[Vector[Issue]] =
    WireDecode.of(Json.decoder[Vector[IssueDto]])(dtos => IssueDto.toDomainAll(JsonPath.Root, dtos))

  private val CommentDecoder: Decode[Comment] =
    WireDecode.of(Json.decoder[CommentDto])(_.toDomain)

  private val CommentsDecoder: Decode[Vector[Comment]] =
    WireDecode.of(Json.decoder[Vector[CommentDto]])(dtos => CommentDto.toDomainAll(JsonPath.Root, dtos))

  private val LabelDecoder: Decode[Label] =
    WireDecode.of(Json.decoder[LabelDto])(_.toDomain)

  private val LabelsDecoder: Decode[Vector[Label]] =
    WireDecode.of(Json.decoder[Vector[LabelDto]])(dtos => LabelDto.toDomainAll(JsonPath.Root, dtos))

  private val MilestoneDecoder: Decode[Milestone] =
    WireDecode.of(Json.decoder[MilestoneDto])(_.toDomain)

  private val MilestonesDecoder: Decode[Vector[Milestone]] =
    WireDecode.of(Json.decoder[Vector[MilestoneDto]])(dtos => MilestoneDto.toDomainAll(JsonPath.Root, dtos))
