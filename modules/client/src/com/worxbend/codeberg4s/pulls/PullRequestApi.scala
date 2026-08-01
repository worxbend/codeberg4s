package com.worxbend.codeberg4s.pulls

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
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.pulls.wire.ChangedFileDto
import com.worxbend.codeberg4s.pulls.wire.CreatePullRequestOptionDto
import com.worxbend.codeberg4s.pulls.wire.EditPullRequestOptionDto
import com.worxbend.codeberg4s.pulls.wire.MergePullRequestOptionDto
import com.worxbend.codeberg4s.pulls.wire.PullRequestDto
import com.worxbend.codeberg4s.pulls.wire.PullRequestQueries
import com.worxbend.codeberg4s.pulls.wire.ReviewDto
import com.worxbend.codeberg4s.repositories.Commit
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName
import com.worxbend.codeberg4s.repositories.wire.CommitDto
import com.worxbend.codeberg4s.repositories.wire.Elements

import scala.concurrent.Future

/** Pull-request endpoints, together with the reviews, commits and changed files that hang off them.
  *
  * Reached as `client.pulls`. Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[PullRequestApi.attempt]] never fail and
  * return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository or the pull request does not
  *     exist '''or''' is private to credentials the client does not have — Forgejo does not distinguish the two, on
  *     purpose — `401` when a token was required and none was sent, and `403` when the token lacks the scope. `422`
  *     '''and''' `400` both mean the request was rejected as invalid: `docs/HAZARDS.md` §4 captured Forgejo using `400`
  *     for a malformed identifier and `422` for a malformed timestamp, so a caller checking only for `422` will miss
  *     half of them.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type — [[com.worxbend.codeberg4s.repositories.Owner]], [[PullRequestNumber]],
  * [[PullRequestHead]], [[com.worxbend.codeberg4s.repositories.CommitSha]] — so a value that would forge a path or a
  * query parameter is rejected by its own smart constructor before a client is ever involved.
  *
  * ==Retries==
  *
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. Every write here uses
  * [[com.worxbend.codeberg4s.core.RetryEligibility.Never]], and for [[merge]] that is the most consequential decision
  * in this class — see its own note.
  *
  * ==Paging==
  *
  * Only [[list]] gets a `Link` header. `golden/MANIFEST.md` records that `/pulls/{n}/reviews`, `/pulls/{n}/commits` and
  * `/pulls/{n}/files` send `X-Total-Count` and '''no''' `Link`, so the pages those three return always report
  * themselves as the last one. Each method below says so again where it matters.
  */
final class PullRequestApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: PullRequestApi.Attempt = PullRequestApi.Attempt(this)

  /** Lists a repository's pull requests — `GET /repos/{owner}/{repo}/pulls`.
    *
    * '''This is not the issue listing.''' `GET /repos/{owner}/{repo}/issues` also returns pull requests, but as
    * issue-shaped projections without a base, a head or a merge; this endpoint returns the real thing. Going the other
    * way, a pull request's ordinary comments live on the '''issue''' endpoints, because Forgejo stores them there.
    *
    * '''`state = closed` includes merged pull requests.''' Forgejo has three lifecycle outcomes and two `state`
    * spellings — `golden/pull/list-closed.json` holds one merged pull request beside two rejected ones — so a caller
    * who wants only rejections filters the result on [[PullRequestState]] rather than on the query.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value, so `items.size < limit` fires on page
    * one of thirty-two (`docs/HAZARDS.md` §5). A page past the end is `200` with `[]`, not a `404`.
    *
    * '''Failures.''' The group contract above. This operation also declares `500`, which arrives as
    * [[com.worxbend.codeberg4s.CodebergError.Api]] like any other status.
    *
    * @param query
    *   the filters to apply; [[PullRequestQuery.Empty]] asks for the instance's default, which is open pull requests
    *   only
    * @param page
    *   which window to fetch, and how large
    */
  def list(owner: Owner, name: RepoName, query: PullRequestQuery, page: PageParams): Future[Page[PullRequest]] =
    pipeline.callPage(PullRequestApi.listRequest(owner, name, query, page), page)(using PullRequestApi.PullsDecoder)

  /** Reads one pull request — `GET /repos/{owner}/{repo}/pulls/{index}`.
    *
    * '''Worth preferring over the listing when the merge matters.''' `merged_by` is populated here and `null` on the
    * listing for the very same pull request — `golden/pull/single-merged.json` against `golden/pull/list-closed.json` —
    * so a caller that needs to know who merged something has to read it individually.
    *
    * '''Failures.''' The group contract above. `404` covers "no such pull request in this repository", "no such
    * repository", and "that index is a plain issue, not a pull request"; the response body's `errors` array is the only
    * thing that distinguishes them, and it is not always present.
    *
    * @param number
    *   the per-repository index, '''not''' [[PullRequest.id]] — see [[PullRequestNumber]]
    */
  def get(owner: Owner, name: RepoName, number: PullRequestNumber): Future[PullRequest] =
    pipeline.call(PullRequestApi.getRequest(owner, name, number), RetryEligibility.IdempotentOnly)(using
      PullRequestApi.PullDecoder)

  /** Opens a pull request — `POST /repos/{owner}/{repo}/pulls`.
    *
    * '''Never retried.''' Repeating this call opens a second pull request, and Forgejo has no idempotency key that
    * would let the instance recognise the repeat. A transport failure therefore leaves the caller genuinely unsure
    * whether the pull request exists, which is the honest state of affairs and better than two of them.
    *
    * '''Failures.''' The group contract above, plus `409` when a pull request for that head and base is already open —
    * which is also what a blind retry runs into — `413` when the diff exceeds the instance's limit, and `423` when the
    * repository is archived. A `422` means Forgejo rejected the payload: an unknown head branch, a base that does not
    * exist, or a head and base that are the same.
    *
    * @param command
    *   what to open; built from [[CreatePullRequest.of]], which has already rejected a blank title
    */
  def create(owner: Owner, name: RepoName, command: CreatePullRequest): Future[PullRequest] =
    pipeline.call(PullRequestApi.createRequest(owner, name, command), RetryEligibility.Never)(using
      PullRequestApi.PullDecoder)

  /** Edits a pull request — `PATCH /repos/{owner}/{repo}/pulls/{index}`.
    *
    * '''Never retried''', even though `PATCH` on a specific resource looks idempotent. It is not, here: the body is a
    * partial update applied to whatever the pull request has become, so a repeat after a transport failure can
    * overwrite a change someone else made in between.
    *
    * '''Only what the command sets is sent'''; everything else keeps its current value. Clearing a deadline is
    * [[EditPullRequest.withoutDueDate]], and clearing labels or assignees is the corresponding builder with an empty
    * vector — see [[EditPullRequest]] for why those are different from doing nothing.
    *
    * '''This cannot merge anything.''' [[EditPullRequest.close]] closes a pull request without merging it; merging is
    * [[merge]] and a different endpoint. Reopening a '''merged''' pull request is refused with a `422`.
    *
    * '''Failures.''' The group contract above, plus `409` and `412` when the pull request moved under the edit. Forgejo
    * answers a successful edit with `201`, not `200`; both are success as far as
    * [[com.worxbend.codeberg4s.core.StatusMapping]] is concerned.
    */
  def edit(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      command: EditPullRequest,
  ): Future[PullRequest] =
    pipeline.call(PullRequestApi.editRequest(owner, name, number, command), RetryEligibility.Never)(using
      PullRequestApi.PullDecoder)

  /** Merges a pull request — `POST /repos/{owner}/{repo}/pulls/{index}/merge`.
    *
    * '''The most destructive operation in this library.''' It writes to the repository's default history, and with
    * [[MergePullRequest.deletingSourceBranch]] it deletes a branch as well. Read the next two paragraphs before calling
    * it from anything automated.
    *
    * ==Never retried, and what a retry would do==
    *
    * This call uses [[com.worxbend.codeberg4s.core.RetryEligibility.Never]], so a `503`, a read timeout or a dropped
    * connection is reported to the caller and not repeated. That is deliberate, because the request is '''not'''
    * idempotent and the failure mode is silent:
    *
    *   - a merge that succeeded on the instance and whose response was lost would, on a repeat, be answered `405`
    *     ("pull request is already merged") — which a caller would read as the merge having failed;
    *   - worse, if the branch moved between the two attempts — a maintainer pushes, an auto-update runs — the second
    *     attempt merges commits the caller never saw and never approved. Forgejo has no idempotency key that would let
    *     it recognise the repeat, so nothing on the server side prevents this;
    *   - with [[MergePullRequest.deletingSourceBranch]], the repeat can also delete a branch that had been recreated in
    *     between.
    *
    * A caller who wants the retry anyway should make it safe first with [[MergePullRequest.expecting]], which sends
    * `head_commit_id` and makes Forgejo refuse the merge if the head has moved, and then re-issue the call themselves.
    * That is a decision this library will not make on anyone's behalf.
    *
    * ==What success means==
    *
    * The endpoint answers `200` with an empty body, so this returns `Unit` rather than the merged pull request; read it
    * back with [[get]] if the merged state is needed. And a success does '''not''' always mean "merged now": with
    * [[MergePullRequest.whenChecksSucceed]] Forgejo records a scheduled merge and answers successfully, then merges
    * later or never.
    *
    * '''Failures.''' The group contract above, plus `405` when the merge is refused — the pull request is already
    * merged, is a draft, has conflicts, fails a required check, or asks for a [[MergeStyle]] the repository forbids —
    * `409` when the head moved and [[MergePullRequest.expecting]] caught it, `413` when the diff is too large, and
    * `423` when the repository or the pull request is locked. A `405` is the ordinary "no" here and deserves handling,
    * not logging.
    */
  def merge(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      command: MergePullRequest,
  ): Future[Unit] =
    pipeline.callUnit(PullRequestApi.mergeRequest(owner, name, number, command), RetryEligibility.Never)

  /** Lists a pull request's reviews — `GET /repos/{owner}/{repo}/pulls/{index}/reviews`.
    *
    * '''The listing contains review requests.''' Asking someone to review produces a row here with state
    * [[ReviewState.RequestReview]] and no commit — two of the three rows of `golden/pull/reviews-list.json` are of that
    * kind — so a caller counting approvals filters on [[Review.state]] rather than counting rows.
    *
    * '''Paging is weaker here than on [[list]], and a caller has to know it.''' `golden/MANIFEST.md` records that this
    * endpoint sends `X-Total-Count` but '''no''' `Link` header. `page` and `limit` are still sent, because the spec
    * declares them and they cost nothing, but two things follow:
    *
    *   - the returned page always reports itself as the last one, since `nextPage` is read from `rel="next"` and there
    *     is no `Link` header to read it from;
    *   - an instance that ignores the parameters answers with the '''complete''' review list, so asking for page two
    *     may return the same rows as page one rather than nothing.
    *
    * Compare [[Page.totalCount]] with [[Page.size]] to find out which happened.
    *
    * '''Failures.''' The group contract above.
    */
  def listReviews(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      page: PageParams,
  ): Future[Page[Review]] =
    pipeline.callPage(PullRequestApi.reviewsRequest(owner, name, number, page), page)(using
      PullRequestApi.ReviewsDecoder)

  /** Lists the commits a pull request would bring — `GET /repos/{owner}/{repo}/pulls/{index}/commits`.
    *
    * The elements are the repository wave's [[com.worxbend.codeberg4s.repositories.Commit]], the same model
    * `GET /repos/{owner}/{repo}/commits` returns, reused rather than forked per `docs/LEDGER.md`. Note the two views of
    * authorship it carries: `author` and `committer` are instance accounts, while `details.author` and
    * `details.committer` are what the Git objects record, and on a backported commit they differ —
    * `golden/pull/commits-list.json` shows a commit written by `erik` and committed by `forgejo-backport-action`.
    *
    * '''`files` and `stats` are empty here.''' The endpoint accepts `files=true` and `verification=true` query
    * parameters to populate them; this library does not send either, because both multiply the response size and
    * neither is what a caller listing commits usually wants. An empty
    * [[com.worxbend.codeberg4s.repositories.Commit.files]] therefore means "not requested", not "touched nothing" — the
    * changed files are [[listFiles]].
    *
    * '''Paging.''' As [[listReviews]]: `X-Total-Count` but no `Link`, so the page always reports itself as the last
    * one.
    *
    * '''Failures.''' The group contract above.
    */
  def listCommits(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      page: PageParams,
  ): Future[Page[Commit]] =
    pipeline.callPage(PullRequestApi.commitsRequest(owner, name, number, page), page)(using
      PullRequestApi.CommitsDecoder)

  /** Lists the files a pull request changes — `GET /repos/{owner}/{repo}/pulls/{index}/files`.
    *
    * Counts and URLs, never diff text: the patch is a separate, non-JSON document (`GET
    * /repos/{owner}/{repo}/pulls/{index}.diff`) that this library does not fetch. See [[ChangedFile]].
    *
    * The endpoint also accepts `skip-to` and `whitespace` parameters, which this library does not send. `skip-to`
    * resumes a listing from a named path rather than from a page number, and `whitespace` changes how the instance
    * computes the counts; both would need a modelled type of their own, and neither has a fixture to model it from.
    *
    * '''Paging.''' As [[listReviews]]: `X-Total-Count` but no `Link`, so the page always reports itself as the last
    * one. Compare [[Page.totalCount]] with [[PullRequest.changedFileCount]] to tell whether the listing is complete.
    *
    * '''Failures.''' The group contract above.
    */
  def listFiles(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      page: PageParams,
  ): Future[Page[ChangedFile]] =
    pipeline.callPage(PullRequestApi.filesRequest(owner, name, number, page), page)(using PullRequestApi.FilesDecoder)

/** The requests this group issues, its operation ids, and its typed rail. */
object PullRequestApi:

  /** The stable operation id [[PullRequestApi.list]] copies into every failure's
    * [[com.worxbend.codeberg4s.CallContext]]. Safe to alert on.
    */
  val ListOperation: String = "pulls.list"

  /** The stable operation id of the single-pull-request read on [[PullRequestApi]]. */
  val GetOperation: String = "pulls.get"

  /** The stable operation id of [[PullRequestApi.create]]. */
  val CreateOperation: String = "pulls.create"

  /** The stable operation id of [[PullRequestApi.edit]]. */
  val EditOperation: String = "pulls.edit"

  /** The stable operation id of [[PullRequestApi.merge]]. The one worth alerting on by itself. */
  val MergeOperation: String = "pulls.merge"

  /** The stable operation id of [[PullRequestApi.listReviews]]. */
  val ListReviewsOperation: String = "pulls.reviews.list"

  /** The stable operation id of [[PullRequestApi.listCommits]]. */
  val ListCommitsOperation: String = "pulls.commits.list"

  /** The stable operation id of [[PullRequestApi.listFiles]]. */
  val ListFilesOperation: String = "pulls.files.list"

  /** The typed rail of [[PullRequestApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.pulls.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: PullRequestApi)(using exec: Exec[Future]):

    /** [[PullRequestApi.list]] with its failure as a value. */
    def list(
        owner: Owner,
        name: RepoName,
        query: PullRequestQuery,
        page: PageParams,
    ): Future[Either[CodebergError, Page[PullRequest]]] =
      exec.attempt(rail.list(owner, name, query, page))

    /** The single-pull-request read on [[PullRequestApi]], with its failure as a value. */
    def get(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
    ): Future[Either[CodebergError, PullRequest]] =
      exec.attempt(rail.get(owner, name, number))

    /** [[PullRequestApi.create]] with its failure as a value. */
    def create(
        owner: Owner,
        name: RepoName,
        command: CreatePullRequest,
    ): Future[Either[CodebergError, PullRequest]] =
      exec.attempt(rail.create(owner, name, command))

    /** [[PullRequestApi.edit]] with its failure as a value. */
    def edit(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        command: EditPullRequest,
    ): Future[Either[CodebergError, PullRequest]] =
      exec.attempt(rail.edit(owner, name, number, command))

    /** [[PullRequestApi.merge]] with its failure as a value — including the `405` that means the merge was refused. */
    def merge(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        command: MergePullRequest,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.merge(owner, name, number, command))

    /** [[PullRequestApi.listReviews]] with its failure as a value. */
    def listReviews(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        page: PageParams,
    ): Future[Either[CodebergError, Page[Review]]] =
      exec.attempt(rail.listReviews(owner, name, number, page))

    /** [[PullRequestApi.listCommits]] with its failure as a value. */
    def listCommits(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        page: PageParams,
    ): Future[Either[CodebergError, Page[Commit]]] =
      exec.attempt(rail.listCommits(owner, name, number, page))

    /** [[PullRequestApi.listFiles]] with its failure as a value. */
    def listFiles(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        page: PageParams,
    ): Future[Either[CodebergError, Page[ChangedFile]]] =
      exec.attempt(rail.listFiles(owner, name, number, page))

  private def listRequest(
      owner: Owner,
      name: RepoName,
      query: PullRequestQuery,
      page: PageParams,
  ): CodebergRequest =
    read(ListOperation, pullsPath(owner, name), PullRequestQueries.pulls(query) ++ PullRequestQueries.paging(page))

  private def getRequest(owner: Owner, name: RepoName, number: PullRequestNumber): CodebergRequest =
    read(GetOperation, pullPath(owner, name, number), Nil)

  private def createRequest(owner: Owner, name: RepoName, command: CreatePullRequest): CodebergRequest =
    write(CreateOperation, HttpMethod.Post, pullsPath(owner, name), CreatePullRequestOptionDto.render(command))

  private def editRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      command: EditPullRequest,
  ): CodebergRequest =
    write(
      EditOperation,
      HttpMethod.Patch,
      pullPath(owner, name, number),
      EditPullRequestOptionDto.render(command),
    )

  private def mergeRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      command: MergePullRequest,
  ): CodebergRequest =
    write(
      MergeOperation,
      HttpMethod.Post,
      pullPath(owner, name, number) :+ "merge",
      MergePullRequestOptionDto.render(command),
    )

  private def reviewsRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      page: PageParams,
  ): CodebergRequest =
    read(ListReviewsOperation, pullPath(owner, name, number) :+ "reviews", PullRequestQueries.paging(page))

  private def commitsRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      page: PageParams,
  ): CodebergRequest =
    read(ListCommitsOperation, pullPath(owner, name, number) :+ "commits", PullRequestQueries.paging(page))

  private def filesRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      page: PageParams,
  ): CodebergRequest =
    read(ListFilesOperation, pullPath(owner, name, number) :+ "files", PullRequestQueries.paging(page))

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

  private def pullsPath(owner: Owner, name: RepoName): List[String] =
    List("repos", owner.value, name.value, "pulls")

  private def pullPath(owner: Owner, name: RepoName, number: PullRequestNumber): List[String] =
    pullsPath(owner, name) :+ number.value.toString

  private val PullDecoder: Decode[PullRequest] =
    WireDecode.of(Json.decoder[PullRequestDto])(_.toDomain)

  private val PullsDecoder: Decode[Vector[PullRequest]] =
    WireDecode.of(Json.decoder[Vector[PullRequestDto]])(dtos => PullRequestDto.toDomainAll(JsonPath.Root, dtos))

  private val ReviewsDecoder: Decode[Vector[Review]] =
    WireDecode.of(Json.decoder[Vector[ReviewDto]])(dtos => ReviewDto.toDomainAll(JsonPath.Root, dtos))

  private val CommitsDecoder: Decode[Vector[Commit]] =
    WireDecode.of(Json.decoder[Vector[CommitDto]])(dtos =>
      Elements.convert(JsonPath.Root, dtos)((dto, path) => dto.toDomainAt(path))
    )

  private val FilesDecoder: Decode[Vector[ChangedFile]] =
    WireDecode.of(Json.decoder[Vector[ChangedFileDto]])(dtos => ChangedFileDto.toDomainAll(JsonPath.Root, dtos))
