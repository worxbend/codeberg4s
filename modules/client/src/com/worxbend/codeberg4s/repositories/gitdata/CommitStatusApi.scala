package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.core.CodebergRequest.read
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.pulls.PullRequest
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.gitdata.wire.GitDataQueries
import com.worxbend.codeberg4s.{CodebergError, Owner, RepoName, RepositoryRequests}

import scala.concurrent.Future

/** What CI has said about a commit, and which pull request brought it.
  *
  * Reached as `client.repos.git.statuses`. It is a group of its own rather than more methods on [[RepositoryGitApi]]
  * because that class had grown past what a reader can hold in their head; the endpoints, the models and the retry
  * decisions are unchanged by the move.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[CommitStatusApi.attempt]] never fail
  * and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==A combined status is not the list of statuses==
  *
  * A commit can carry many statuses, one per reporting system, and Forgejo also folds them into a single verdict.
  * [[getCombinedStatus]] answers the verdict and [[statuses]] answers the rows behind it; a caller that wants to know
  * '''why''' a commit is failing needs the second, and one that only gates on green needs the first.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository, the ref or the object does
  *     not exist '''or''' is invisible to the credentials in use — Forgejo does not distinguish the two, on purpose —
  *     `401` when a token was required and none was sent, and `403` when the token lacks the scope. `422` '''and'''
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
  * Every operation here is a read and uses [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]: a
  * repeated read costs nothing but the round trip.
  */

final class CommitStatusApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: CommitStatusApi.Attempt = CommitStatusApi.Attempt(this)

  /** Reads a commit's combined CI status — `GET /repos/{owner}/{repo}/commits/{ref}/status`.
    *
    * '''Returns the whole envelope, not a page.''' The endpoint declares `page` and `limit` and they window the nested
    * `statuses` array, but the array is wrapped in an object that also carries the reduced verdict, the resolved sha
    * and the repository. Turning that into a [[com.worxbend.codeberg4s.paging.Page]] would throw away the verdict,
    * which is the reason the endpoint exists — so the window is an argument and [[CombinedCommitStatus.totalCount]] is
    * what says whether another window is worth asking for.
    *
    * '''Failures.''' The group contract above, plus a `400` when the ref cannot be resolved, and
    * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at `$.sha`.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param ref
    *   a branch, a tag or a commit id
    * @param params
    *   the window over the nested statuses
    */
  def getCombinedStatus(
      owner: Owner,
      name: RepoName,
      ref: RefName,
      params: PageParams,
  ): Future[CombinedCommitStatus] =
    pipeline.call(CommitStatusApi.combinedStatusRequest(owner, name, ref, params), RetryEligibility.IdempotentOnly)(
      using GitDataDecoders.combinedStatus
    )

  /** Lists a commit's individual CI statuses — `GET /repos/{owner}/{repo}/commits/{ref}/statuses`.
    *
    * Every check that reported on the commit, unreduced. Use [[getCombinedStatus]] for the instance's single verdict
    * over them.
    *
    * '''Paging.''' The `Link` header decides, per `docs/HAZARDS.md` §5, and the number of items returned decides
    * nothing.
    *
    * '''Failures.''' The group contract above, plus a `400` when the ref cannot be resolved or the instance rejects a
    * filter — note that [[CommitStatusState.Skipped]] is not among the values the `state` filter declares, which
    * [[CommitStatusQuery.inState]] explains — and [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at
    * `$[n].id`.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param ref
    *   a branch, a tag or a commit id
    * @param query
    *   the ordering and state filter; [[CommitStatusQuery.Empty]] asks for neither
    * @param params
    *   the page to fetch and how many statuses it may hold
    */
  def statuses(
      owner: Owner,
      name: RepoName,
      ref: RefName,
      query: CommitStatusQuery,
      params: PageParams,
  ): Future[Page[CommitStatus]] =
    pipeline.callPage(CommitStatusApi.statusesRequest(owner, name, ref, query, params), params)(using
      GitDataDecoders.commitStatuses)

  /** Reads the pull request a commit belongs to — `GET /repos/{owner}/{repo}/commits/{sha}/pull`.
    *
    * The inverse of asking a pull request for its commits, and the only way to get from a commit id back to the review
    * it went through. A commit that was pushed straight to a branch has no pull request and answers `404`.
    *
    * The result is the pull-request wave's [[com.worxbend.codeberg4s.pulls.PullRequest]] — the same model, not a
    * reduced copy, because Forgejo returns the same object here as it does from the pull-request endpoints.
    *
    * '''Failures.''' The group contract above, plus [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] at
    * `$.number` or another field the pull-request model requires.
    *
    * @param owner
    *   the user or organisation that owns the repository
    * @param name
    *   the repository name, without the owner
    * @param sha
    *   the commit's object id
    */
  def getCommitPullRequest(owner: Owner, name: RepoName, sha: CommitSha): Future[PullRequest] =
    pipeline.call(CommitStatusApi.commitPullRequest(owner, name, sha), RetryEligibility.IdempotentOnly)(using
      GitDataDecoders.pullRequest)

/** The requests this group issues, its operation ids, and its typed rail. */
object CommitStatusApi:

  /** The stable operation id of [[CommitStatusApi.getCombinedStatus]]. */
  val GetCombinedStatusOperation: String = "repos.commits.status.get"

  /** The stable operation id of [[CommitStatusApi.statuses]]. */
  val ListStatusesOperation: String = "repos.commits.statuses.list"

  /** The stable operation id of [[CommitStatusApi.getCommitPullRequest]]. */
  val GetCommitPullRequestOperation: String = "repos.commits.pull.get"

  /** The typed rail of [[CommitStatusApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.repos.git.statuses.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: CommitStatusApi)(using exec: Exec[Future]):

    /** [[CommitStatusApi.getCombinedStatus]] with its failure as a value. */
    def getCombinedStatus(
        owner: Owner,
        name: RepoName,
        ref: RefName,
        params: PageParams,
    ): Future[Either[CodebergError, CombinedCommitStatus]] =
      exec.attempt(rail.getCombinedStatus(owner, name, ref, params))

    /** [[CommitStatusApi.statuses]] with its failure as a value. */
    def statuses(
        owner: Owner,
        name: RepoName,
        ref: RefName,
        query: CommitStatusQuery,
        params: PageParams,
    ): Future[Either[CodebergError, Page[CommitStatus]]] =
      exec.attempt(rail.statuses(owner, name, ref, query, params))

    /** [[CommitStatusApi.getCommitPullRequest]] with its failure as a value. */
    def getCommitPullRequest(
        owner: Owner,
        name: RepoName,
        sha: CommitSha,
    ): Future[Either[CodebergError, PullRequest]] =
      exec.attempt(rail.getCommitPullRequest(owner, name, sha))

  private def combinedStatusRequest(
      owner: Owner,
      name: RepoName,
      ref: RefName,
      params: PageParams,
  ): CodebergRequest =
    read(
      GetCombinedStatusOperation,
      commitsPath(owner, name) ++ ref.segments :+ "status",
      PagingQuery.window(params),
    )

  private def statusesRequest(
      owner: Owner,
      name: RepoName,
      ref: RefName,
      query: CommitStatusQuery,
      params: PageParams,
  ): CodebergRequest =
    read(
      ListStatusesOperation,
      commitsPath(owner, name) ++ ref.segments :+ "statuses",
      GitDataQueries.commitStatuses(query) ++ PagingQuery.window(params),
    )

  private def commitPullRequest(owner: Owner, name: RepoName, sha: CommitSha): CodebergRequest =
    read(GetCommitPullRequestOperation, commitsPath(owner, name) :+ sha.value :+ "pull", Nil)

  /** The `/repos/{owner}/{repo}/commits` prefix every path in this group builds on.
    *
    * Note the missing `/git`: the commit statuses hang off the repository directly, where
    * [[RepositoryGitApi.getCommit]] reads the commit object itself under `/git/commits`. The two are different
    * endpoints answering about the same commit.
    */
  private def commitsPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "commits"
