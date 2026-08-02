package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.issues.wire.EditIssueCommentOptionDto
import com.worxbend.codeberg4s.issues.wire.IssueQueries
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName

import scala.concurrent.Future

/** Reading, editing and deleting a comment once it exists, and listing every comment in a repository.
  *
  * Reached as `client.issues.comments`. Creating a comment and listing one issue's comments live on [[IssueApi]]
  * itself, where they were written; this class is everything else Forgejo offers on a comment.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[IssueCommentApi.attempt]] never fail and
  * return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Evidence==
  *
  * The `Comment` model is captured: `golden/issue/comments-list.json` is a real response, and
  * [[com.worxbend.codeberg4s.issues.wire.CommentDto]] is derived from it. The '''requests''' here are not — Forgejo's
  * `EditIssueCommentOption` is read from `spec/swagger.v1.json`, because every write needs a token and the harvest was
  * anonymous.
  *
  * ==A comment id has no issue in it==
  *
  * `/repos/{owner}/{repo}/issues/comments/{id}` carries no issue number, because [[CommentId]] is instance-wide. The
  * two `…/issues/{index}/comments/{id}` operations are Forgejo's '''deprecated''' spelling of the same thing, kept here
  * as [[editDeprecated]] and [[deleteDeprecated]] for callers talking to an older instance. New code should use
  * [[edit]] and [[delete]]; the deprecated pair sends an issue number the instance ignores.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository or the comment does not
  *     exist '''or''' is private to credentials the client does not have — Forgejo does not distinguish the two, on
  *     purpose — `401` when a token was required and none was sent, and `403` when the token lacks the scope. `422`
  *     '''and''' `400` both mean the request was rejected as invalid, per `docs/HAZARDS.md` §4. Three of these
  *     operations additionally declare a `500`, which arrives as an `Api` failure like any other status.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type, so a value that would forge a path or a query parameter is rejected by its own smart
  * constructor before a client is ever involved.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class IssueCommentApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: IssueCommentApi.Attempt = IssueCommentApi.Attempt(this)

  /** Lists every comment in a repository — `GET /repos/{owner}/{repo}/issues/comments`.
    *
    * '''Across all issues, not one.''' The per-issue listing is [[IssueApi.listComments]]. This is the endpoint an
    * indexer or a mirror walks.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5). A page past
    * the end is `200` with `[]`, not a `404`.
    *
    * '''Failures.''' The group contract above. A `422` here most often means a malformed `since` or `before`.
    *
    * @param query
    *   the time window to restrict to; [[CommentQuery.Empty]] asks for all of them
    */
  def listForRepository(
      owner: Owner,
      name: RepoName,
      query: CommentQuery,
      page: PageParams,
  ): Future[Page[Comment]] =
    pipeline.callPage(IssueCommentApi.listForRepositoryRequest(owner, name, query, page), page)(using
      IssueDecoders.comments)

  /** Reads one comment — `GET /repos/{owner}/{repo}/issues/comments/{id}`.
    *
    * '''`None` is a success, not a missing comment.''' Forgejo answers `204` with an empty body when the row behind
    * `id` exists but is not a user-written comment — the timeline is made of such rows, and they share the comment
    * numbering. A comment that genuinely does not exist is a `404`, which is a failure on both rails. See
    * [[IssueDecoders]] for how the empty body is read.
    *
    * '''Failures.''' The group contract above, plus the `500` this operation declares.
    */
  def get(owner: Owner, name: RepoName, id: CommentId): Future[Option[Comment]] =
    pipeline.call(IssueCommentApi.getRequest(owner, name, id), RetryEligibility.IdempotentOnly)(using
      IssueDecoders.comment)

  /** Replaces a comment's text — `PATCH /repos/{owner}/{repo}/issues/comments/{id}`.
    *
    * '''Never retried.''' This is not a partial update that could be reapplied safely: the body '''replaces''' the
    * comment outright, so a repeat after a transport failure overwrites whatever the comment has become in the
    * meantime, including somebody else's edit. Forgejo offers no conditional-update header that would let the instance
    * refuse a stale write, so the library cannot make the repeat safe and does not pretend to. A caller who knows their
    * edit is safe to repeat can re-issue it themselves.
    *
    * '''`None` is a success''', for the reason [[get]] gives.
    *
    * '''Failures.''' The group contract above, plus `423` when the issue is locked and the `500` this operation
    * declares.
    */
  def edit(owner: Owner, name: RepoName, id: CommentId, command: EditComment): Future[Option[Comment]] =
    pipeline.call(IssueCommentApi.editRequest(owner, name, id, command), RetryEligibility.Never)(using
      IssueDecoders.comment)

  /** Deletes a comment — `DELETE /repos/{owner}/{repo}/issues/comments/{id}`.
    *
    * '''Retried''', because the request names exactly one object by an identifier the instance never reuses: a comment
    * id is a database row id, so the state after N attempts is the state after one and nothing is created. The cost is
    * one a caller has to know — if the first attempt succeeded and its response was lost, the retry addresses something
    * that no longer exists and answers `404`. A `404` from a delete therefore means "it is gone", not necessarily "it
    * was never there".
    *
    * '''Answers `204`''', so there is nothing to return.
    *
    * '''Failures.''' The group contract above, plus the `500` this operation declares.
    */
  def delete(owner: Owner, name: RepoName, id: CommentId): Future[Unit] =
    pipeline.callUnit(IssueCommentApi.deleteRequest(owner, name, id), RetryEligibility.AlwaysRetry)

  /** [[edit]] against Forgejo's deprecated per-issue route —
    * `PATCH /repos/{owner}/{repo}/issues/{index}/comments/{id}`.
    *
    * '''Prefer [[edit]].''' The `spec/swagger.v1.json` operation id is literally `issueEditCommentDeprecated`. The
    * issue number in the path is not used to find the comment — [[CommentId]] is instance-wide — so passing the wrong
    * one edits the comment anyway. This exists for an instance old enough not to route the current spelling.
    *
    * '''Never retried''', and `None` is a success; both for the reasons [[edit]] gives.
    *
    * '''Failures.''' The group contract above, plus the `500` this operation declares.
    */
  def editDeprecated(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      id: CommentId,
      command: EditComment,
  ): Future[Option[Comment]] =
    pipeline.call(
      IssueCommentApi.editDeprecatedRequest(owner, name, number, id, command),
      RetryEligibility.Never,
    )(using IssueDecoders.comment)

  /** [[delete]] against Forgejo's deprecated per-issue route —
    * `DELETE /repos/{owner}/{repo}/issues/{index}/comments/{id}`.
    *
    * '''Prefer [[delete]]''', for the reason [[editDeprecated]] gives.
    *
    * '''Retried''', with the same `404`-after-a-lost-success consequence [[delete]] describes.
    *
    * '''Failures.''' The group contract above, plus the `500` this operation declares.
    */
  def deleteDeprecated(owner: Owner, name: RepoName, number: IssueNumber, id: CommentId): Future[Unit] =
    pipeline.callUnit(
      IssueCommentApi.deleteDeprecatedRequest(owner, name, number, id),
      RetryEligibility.AlwaysRetry,
    )

/** The requests this group issues, its operation ids, and its typed rail. */
object IssueCommentApi:

  /** The stable operation id of [[IssueCommentApi.listForRepository]]. Safe to alert on. */
  val ListForRepositoryOperation: String = "issues.comments.listForRepo"

  /** The stable operation id of the single-comment read on [[IssueCommentApi]]. */
  val GetOperation: String = "issues.comments.get"

  /** The stable operation id of [[IssueCommentApi.edit]]. */
  val EditOperation: String = "issues.comments.edit"

  /** The stable operation id of [[IssueCommentApi.delete]]. */
  val DeleteOperation: String = "issues.comments.delete"

  /** The stable operation id of [[IssueCommentApi.editDeprecated]], kept distinct from [[EditOperation]] so telemetry
    * can show which spelling a caller still uses.
    */
  val EditDeprecatedOperation: String = "issues.comments.editDeprecated"

  /** The stable operation id of [[IssueCommentApi.deleteDeprecated]]. */
  val DeleteDeprecatedOperation: String = "issues.comments.deleteDeprecated"

  /** The typed rail of [[IssueCommentApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.issues.comments.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: IssueCommentApi)(using exec: Exec[Future]):

    /** [[IssueCommentApi.listForRepository]] with its failure as a value. */
    def listForRepository(
        owner: Owner,
        name: RepoName,
        query: CommentQuery,
        page: PageParams,
    ): Future[Either[CodebergError, Page[Comment]]] =
      exec.attempt(rail.listForRepository(owner, name, query, page))

    /** The single-comment read on [[IssueCommentApi]], with its failure as a value. */
    def get(owner: Owner, name: RepoName, id: CommentId): Future[Either[CodebergError, Option[Comment]]] =
      exec.attempt(rail.get(owner, name, id))

    /** [[IssueCommentApi.edit]] with its failure as a value. */
    def edit(
        owner: Owner,
        name: RepoName,
        id: CommentId,
        command: EditComment,
    ): Future[Either[CodebergError, Option[Comment]]] =
      exec.attempt(rail.edit(owner, name, id, command))

    /** [[IssueCommentApi.delete]] with its failure as a value. */
    def delete(owner: Owner, name: RepoName, id: CommentId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.delete(owner, name, id))

    /** [[IssueCommentApi.editDeprecated]] with its failure as a value. */
    def editDeprecated(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        id: CommentId,
        command: EditComment,
    ): Future[Either[CodebergError, Option[Comment]]] =
      exec.attempt(rail.editDeprecated(owner, name, number, id, command))

    /** [[IssueCommentApi.deleteDeprecated]] with its failure as a value. */
    def deleteDeprecated(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        id: CommentId,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteDeprecated(owner, name, number, id))

  private def listForRepositoryRequest(
      owner: Owner,
      name: RepoName,
      query: CommentQuery,
      page: PageParams,
  ): CodebergRequest =
    IssueRequests.read(
      ListForRepositoryOperation,
      IssueRequests.issuesPath(owner, name) :+ "comments",
      IssueQueries.comments(query) ++ IssueQueries.paging(page),
    )

  private def getRequest(owner: Owner, name: RepoName, id: CommentId): CodebergRequest =
    IssueRequests.read(GetOperation, IssueRequests.commentPath(owner, name, id), Nil)

  private def editRequest(
      owner: Owner,
      name: RepoName,
      id: CommentId,
      command: EditComment,
  ): CodebergRequest =
    IssueRequests.write(
      EditOperation,
      HttpMethod.Patch,
      IssueRequests.commentPath(owner, name, id),
      EditIssueCommentOptionDto.render(command),
    )

  private def deleteRequest(owner: Owner, name: RepoName, id: CommentId): CodebergRequest =
    IssueRequests.remove(DeleteOperation, IssueRequests.commentPath(owner, name, id))

  private def editDeprecatedRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      id: CommentId,
      command: EditComment,
  ): CodebergRequest =
    IssueRequests.write(
      EditDeprecatedOperation,
      HttpMethod.Patch,
      deprecatedPath(owner, name, number, id),
      EditIssueCommentOptionDto.render(command),
    )

  private def deleteDeprecatedRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      id: CommentId,
  ): CodebergRequest =
    IssueRequests.remove(DeleteDeprecatedOperation, deprecatedPath(owner, name, number, id))

  private def deprecatedPath(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      id: CommentId,
  ): List[String] =
    IssueRequests.issuePath(owner, name, number) ++ List("comments", id.value.toString)
