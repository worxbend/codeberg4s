package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.core.CodebergRequest.{read, removeWithBody, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.issues.wire.{EditReactionOptionDto, IssueQueries}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName}

import scala.concurrent.Future

/** Emoji reactions on an issue and on a comment.
  *
  * Reached as `client.issues.reactions`. Six operations, in two identical halves — list, add and remove, once under
  * `…/issues/{index}/reactions` and once under `…/issues/comments/{id}/reactions`, with the same models on both. The
  * method names say which half they are.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[IssueReactionApi.attempt]] never fail
  * and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Evidence==
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' No golden fixture contains a reaction: the
  * harvest was anonymous and none of the captured issues or comments carried one.
  *
  * ==The vocabulary is open, and that was checked==
  *
  * `Reaction.content` and `EditReactionOption.content` are both declared as a bare `{"type": "string"}` with '''no'''
  * `enum` — unlike `Attachment.type`, which the same document does constrain and which [[AttachmentKind]] therefore
  * models as a closed set. Forgejo additionally lets an instance choose which reactions its UI offers, without
  * republishing the list. So [[ReactionContent]] is an opaque string, not an enum, and '''nothing here checks that the
  * instance accepts the reaction''' — an unsupported one comes back as a `403` or a `404`, not as a validation error.
  *
  * ==A listing is not a tally==
  *
  * Both listings return one element per account per emoji. Counting is the caller's job: group [[Reaction.content]].
  *
  * ==A DELETE with a body==
  *
  * Which reaction to remove is not in the URL, so both removals put it in a JSON body on a `DELETE`. RFC 9110 permits
  * that and defines no semantics for it; Forgejo defines its own. It is the only reason these two are not ordinary
  * bodiless deletes.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository, the issue or the comment
  *     does not exist '''or''' is private to credentials the client does not have — Forgejo does not distinguish the
  *     two, on purpose — `401` when a token was required and none was sent, and `403` when the token lacks the scope
  *     '''or''' when the instance does not offer the reaction. `422` and `400` both mean the request was rejected as
  *     invalid, per `docs/HAZARDS.md` §4.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class IssueReactionApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: IssueReactionApi.Attempt = IssueReactionApi.Attempt(this)

  /** Lists the reactions on an issue — `GET /repos/{owner}/{repo}/issues/{index}/reactions`.
    *
    * '''Paged''', unlike the comment listing below: this is the one of the two the spec gives `page` and `limit`.
    * Paging ends where the response's `Link` header says it ends, never where a short page suggests it does — Forgejo
    * clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5).
    *
    * '''One element per account per emoji'''; see the class note.
    *
    * '''Failures.''' The group contract above.
    */
  def listOnIssue(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      params: PageParams,
  ): Future[Page[Reaction]] =
    pipeline.callPage(IssueReactionApi.listOnIssueRequest(owner, name, number, params), params)(using
      IssueDecoders.reactions)

  /** Reacts to an issue — `POST /repos/{owner}/{repo}/issues/{index}/reactions`.
    *
    * '''Never retried''', because it is a `POST` and this library never repeats one. The practical effect of a repeat
    * would be Forgejo answering `200` instead of `201` — the reaction is per account and per content, so there is only
    * ever one — but that is the instance's behaviour to change, not a promise this library makes on its behalf.
    *
    * '''`200` and `201` are both success, and both carry the reaction''': `201` when it was created, `200` when the
    * account had already reacted that way. [[com.worxbend.codeberg4s.core.StatusMapping]] treats every `2xx` alike, so
    * there is no way to tell the two apart from the return value.
    *
    * '''Failures.''' The group contract above; a `403` is what an unsupported reaction produces.
    */
  def addToIssue(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      content: ReactionContent,
  ): Future[Reaction] =
    pipeline.call(IssueReactionApi.addToIssueRequest(owner, name, number, content), RetryEligibility.Never)(using
      IssueDecoders.reaction)

  /** Withdraws a reaction from an issue — `DELETE /repos/{owner}/{repo}/issues/{index}/reactions`.
    *
    * '''Retried''', because the request names exactly one thing to remove — this issue, this account, this content —
    * and none of those can change under it: the account is the token's, and Forgejo stores at most one reaction per
    * account per content. The state after N attempts is the state after one, and nothing is created. Unlike most
    * deletes in this library a lost success does '''not''' turn into a `404` on the retry: the spec declares `200` for
    * this operation whether or not the reaction was there.
    *
    * '''The reaction to remove travels in the body''', not the URL; see the class note.
    *
    * '''Failures.''' The group contract above.
    */
  def removeFromIssue(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      content: ReactionContent,
  ): Future[Unit] =
    pipeline.callUnit(
      IssueReactionApi.removeFromIssueRequest(owner, name, number, content),
      RetryEligibility.AlwaysRetry,
    )

  /** Lists the reactions on a comment — `GET /repos/{owner}/{repo}/issues/comments/{id}/reactions`.
    *
    * '''Not paged, and that is the endpoint's decision.''' The spec declares no `page` or `limit` for this operation —
    * the response is typed `ReactionListWithoutPagination`, where the issue listing is a plain `ReactionList` — so the
    * whole list arrives at once and the result is a `Vector` rather than a [[com.worxbend.codeberg4s.paging.Page]]. A
    * page reporting a window nobody chose would be a lie about what was requested.
    *
    * '''Failures.''' The group contract above.
    */
  def listOnComment(owner: Owner, name: RepoName, comment: CommentId): Future[Vector[Reaction]] =
    pipeline.call(IssueReactionApi.listOnCommentRequest(owner, name, comment), RetryEligibility.IdempotentOnly)(using
      IssueDecoders.reactions)

  /** Reacts to a comment — `POST /repos/{owner}/{repo}/issues/comments/{id}/reactions`.
    *
    * '''Never retried''', and `200`/`201` are both success carrying the reaction; both for the reasons [[addToIssue]]
    * gives.
    *
    * '''Failures.''' The group contract above.
    */
  def addToComment(
      owner: Owner,
      name: RepoName,
      comment: CommentId,
      content: ReactionContent,
  ): Future[Reaction] =
    pipeline.call(IssueReactionApi.addToCommentRequest(owner, name, comment, content), RetryEligibility.Never)(using
      IssueDecoders.reaction)

  /** Withdraws a reaction from a comment — `DELETE /repos/{owner}/{repo}/issues/comments/{id}/reactions`.
    *
    * '''Retried''', for the reason [[removeFromIssue]] gives, and with the same `200`-whether-or-not-it-was-there
    * consequence. The reaction to remove travels in the body.
    *
    * '''Failures.''' The group contract above.
    */
  def removeFromComment(
      owner: Owner,
      name: RepoName,
      comment: CommentId,
      content: ReactionContent,
  ): Future[Unit] =
    pipeline.callUnit(
      IssueReactionApi.removeFromCommentRequest(owner, name, comment, content),
      RetryEligibility.AlwaysRetry,
    )

/** The requests this group issues, its operation ids, and its typed rail. */
object IssueReactionApi:

  /** The stable operation id of [[IssueReactionApi.listOnIssue]]. Safe to alert on. */
  val ListOnIssueOperation: String = "issues.reactions.list"

  /** The stable operation id of [[IssueReactionApi.addToIssue]]. */
  val AddToIssueOperation: String = "issues.reactions.add"

  /** The stable operation id of [[IssueReactionApi.removeFromIssue]]. */
  val RemoveFromIssueOperation: String = "issues.reactions.remove"

  /** The stable operation id of [[IssueReactionApi.listOnComment]]. */
  val ListOnCommentOperation: String = "issues.comments.reactions.list"

  /** The stable operation id of [[IssueReactionApi.addToComment]]. */
  val AddToCommentOperation: String = "issues.comments.reactions.add"

  /** The stable operation id of [[IssueReactionApi.removeFromComment]]. */
  val RemoveFromCommentOperation: String = "issues.comments.reactions.remove"

  /** The typed rail of [[IssueReactionApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.issues.reactions.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: IssueReactionApi)(using exec: Exec[Future]):

    /** [[IssueReactionApi.listOnIssue]] with its failure as a value. */
    def listOnIssue(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        params: PageParams,
    ): Future[Either[CodebergError, Page[Reaction]]] =
      exec.attempt(rail.listOnIssue(owner, name, number, params))

    /** [[IssueReactionApi.addToIssue]] with its failure as a value. */
    def addToIssue(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        content: ReactionContent,
    ): Future[Either[CodebergError, Reaction]] =
      exec.attempt(rail.addToIssue(owner, name, number, content))

    /** [[IssueReactionApi.removeFromIssue]] with its failure as a value. */
    def removeFromIssue(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        content: ReactionContent,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.removeFromIssue(owner, name, number, content))

    /** [[IssueReactionApi.listOnComment]] with its failure as a value. */
    def listOnComment(
        owner: Owner,
        name: RepoName,
        comment: CommentId,
    ): Future[Either[CodebergError, Vector[Reaction]]] =
      exec.attempt(rail.listOnComment(owner, name, comment))

    /** [[IssueReactionApi.addToComment]] with its failure as a value. */
    def addToComment(
        owner: Owner,
        name: RepoName,
        comment: CommentId,
        content: ReactionContent,
    ): Future[Either[CodebergError, Reaction]] =
      exec.attempt(rail.addToComment(owner, name, comment, content))

    /** [[IssueReactionApi.removeFromComment]] with its failure as a value. */
    def removeFromComment(
        owner: Owner,
        name: RepoName,
        comment: CommentId,
        content: ReactionContent,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.removeFromComment(owner, name, comment, content))

  private def listOnIssueRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      params: PageParams,
  ): CodebergRequest =
    read(ListOnIssueOperation, issueReactionsPath(owner, name, number), IssueQueries.paging(params))

  private def addToIssueRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      content: ReactionContent,
  ): CodebergRequest =
    write(
      AddToIssueOperation,
      HttpMethod.Post,
      issueReactionsPath(owner, name, number),
      EditReactionOptionDto.render(content),
    )

  private def removeFromIssueRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      content: ReactionContent,
  ): CodebergRequest =
    removeWithBody(
      RemoveFromIssueOperation,
      issueReactionsPath(owner, name, number),
      EditReactionOptionDto.render(content),
    )

  private def listOnCommentRequest(owner: Owner, name: RepoName, comment: CommentId): CodebergRequest =
    read(ListOnCommentOperation, commentReactionsPath(owner, name, comment), Nil)

  private def addToCommentRequest(
      owner: Owner,
      name: RepoName,
      comment: CommentId,
      content: ReactionContent,
  ): CodebergRequest =
    write(
      AddToCommentOperation,
      HttpMethod.Post,
      commentReactionsPath(owner, name, comment),
      EditReactionOptionDto.render(content),
    )

  private def removeFromCommentRequest(
      owner: Owner,
      name: RepoName,
      comment: CommentId,
      content: ReactionContent,
  ): CodebergRequest =
    removeWithBody(
      RemoveFromCommentOperation,
      commentReactionsPath(owner, name, comment),
      EditReactionOptionDto.render(content),
    )

  private def issueReactionsPath(owner: Owner, name: RepoName, number: IssueNumber): List[String] =
    IssueRequests.issuePath(owner, name, number) :+ "reactions"

  private def commentReactionsPath(owner: Owner, name: RepoName, comment: CommentId): List[String] =
    IssueRequests.commentPath(owner, name, comment) :+ "reactions"
