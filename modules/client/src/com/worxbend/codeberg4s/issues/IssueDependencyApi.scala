package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.core.CodebergRequest.{read, removeWithBody, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.issues.wire.IssueMetaDto
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName}

import scala.concurrent.Future

/** The edges between issues: what an issue depends on, and what it blocks.
  *
  * Reached as `client.issues.dependencies`. It is a group of its own rather than more methods on [[IssueApi]] because
  * that class had grown past what a reader can hold in their head; the endpoints, the models and the retry decisions
  * are unchanged by the move.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[IssueDependencyApi.attempt]] never fail
  * and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==One edge, read from both ends==
  *
  * A dependency and a block are the same relationship seen from opposite sides: if issue A depends on issue B, then B
  * blocks A. Forgejo exposes both directions as separate endpoints, and both are here so that a caller who reaches for
  * one is looking at the other. Which direction to use is a question of which issue is in hand, not of which
  * relationship is meant.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository or the issue does not exist
  *     '''or''' is invisible to the credentials in use — Forgejo does not distinguish the two, on purpose — `401` when
  *     a token was required and none was sent, and `403` when the token lacks the scope or the account lacks the
  *     permission. `422` '''and''' `400` both mean the request was rejected as invalid.
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
  * The two listings use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. The writes name both ends of
  * one edge and state the whole intended state, so each says on its own method how it is retried.
  */

final class IssueDependencyApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: IssueDependencyApi.Attempt = IssueDependencyApi.Attempt(this)

  /** Lists the issues this issue is blocking — `GET /repos/{owner}/{repo}/issues/{index}/blocks`.
    *
    * '''Read the direction carefully.''' These are the issues that cannot proceed until this one is done. The opposite
    * relation — what this issue is waiting on — is [[dependencies]]. Forgejo stores one relation and serves both ends
    * of it, so adding a block here is the same edge as adding a dependency there, seen from the other side.
    *
    * '''Paging.''' As [[list]]: the `Link` header decides, not the number of items.
    *
    * '''Failures.''' The group contract above.
    */
  def blocks(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      params: PageParams,
  ): Future[Page[Issue]] =
    pipeline.callPage(IssueDependencyApi.blocksRequest(owner, name, number, params), params)(using IssueDecoders.issues)

  /** Declares that this issue blocks another — `POST /repos/{owner}/{repo}/issues/{index}/blocks`.
    *
    * '''The blocked issue is in the body, the blocking one in the path.''' The spec's own words are "block the issue
    * given in the body by the issue in path". The body may name an issue in a '''different''' repository, which is why
    * it is an [[IssueRef]] carrying an owner and a repository of its own and not a bare [[IssueNumber]].
    *
    * '''Never retried''', because it is a `POST` and this library never repeats one. Forgejo stores at most one edge
    * between two issues, so a repeat would most likely be rejected rather than duplicate the link — but that is the
    * instance's behaviour to change, not a promise this library makes on its behalf.
    *
    * '''Answers `201`''' with the issue that is now blocked, that is the one named in the body.
    *
    * '''Failures.''' The group contract above; a `404` here can mean either issue is missing.
    */
  def addBlock(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      blocked: IssueRef,
  ): Future[Issue] =
    pipeline.call(IssueDependencyApi.addBlockRequest(owner, name, number, blocked), RetryEligibility.Never)(using
      IssueDecoders.issue)

  /** Withdraws a block — `DELETE /repos/{owner}/{repo}/issues/{index}/blocks`.
    *
    * '''The blocked issue travels in the body''', not the URL, because which edge to sever is not otherwise expressible
    * — the same shape [[com.worxbend.codeberg4s.issues.wire.EditReactionOptionDto]] describes.
    *
    * '''Retried''', because the request names exactly one edge — this issue, that issue — and asks for an absolute end
    * state: the edge is gone. Doing it twice leaves the instance where doing it once would and nothing is created. The
    * usual cost applies: if the first attempt succeeded and its response was lost, the retry addresses an edge that no
    * longer exists and answers `404`.
    *
    * '''Answers `200`''' with the issue that is no longer blocked.
    *
    * '''Failures.''' The group contract above.
    */
  def removeBlock(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      blocked: IssueRef,
  ): Future[Issue] =
    pipeline.call(
      IssueDependencyApi.removeBlockRequest(owner, name, number, blocked),
      RetryEligibility.AlwaysRetry
    )(using IssueDecoders.issue)

  /** Lists the issues this issue is waiting on — `GET /repos/{owner}/{repo}/issues/{index}/dependencies`.
    *
    * The other end of the relation [[blocks]] reports; see that method for the direction.
    *
    * '''Paging.''' As [[list]]: the `Link` header decides, not the number of items.
    *
    * '''Failures.''' The group contract above.
    */
  def dependencies(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      params: PageParams,
  ): Future[Page[Issue]] =
    pipeline.callPage(IssueDependencyApi.dependenciesRequest(owner, name, number, params), params)(using
      IssueDecoders.issues)

  /** Declares that this issue depends on another — `POST /repos/{owner}/{repo}/issues/{index}/dependencies`.
    *
    * '''The issue in the URL depends on the issue in the body''' — the spec's own words. As with [[addBlock]], the body
    * may name an issue in another repository, which is why it is an [[IssueRef]].
    *
    * '''Never retried''', for the reason [[addBlock]] gives.
    *
    * '''Answers `201`''' with the issue the dependency was added to.
    *
    * '''Failures.''' The group contract above, plus `423` when the issue is locked.
    */
  def addDependency(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      blocker: IssueRef,
  ): Future[Issue] =
    pipeline.call(IssueDependencyApi.addDependencyRequest(owner, name, number, blocker), RetryEligibility.Never)(using
      IssueDecoders.issue)

  /** Withdraws a dependency — `DELETE /repos/{owner}/{repo}/issues/{index}/dependencies`.
    *
    * '''The blocking issue travels in the body''', not the URL; see [[removeBlock]] for the shape and for the retry
    * reasoning, which is identical.
    *
    * '''Answers `200`''' with the issue the dependency was removed from.
    *
    * '''Failures.''' The group contract above, plus `423` when the issue is locked.
    */
  def removeDependency(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      blocker: IssueRef,
  ): Future[Issue] =
    pipeline.call(
      IssueDependencyApi.removeDependencyRequest(owner, name, number, blocker),
      RetryEligibility.AlwaysRetry
    )(using IssueDecoders.issue)

/** The requests this group issues, its operation ids, and its typed rail. */
object IssueDependencyApi:

  /** The stable operation id of [[IssueDependencyApi.blocks]]. */
  val ListBlocksOperation: String = "issues.blocks.list"

  /** The stable operation id of [[IssueDependencyApi.addBlock]]. */
  val AddBlockOperation: String = "issues.blocks.add"

  /** The stable operation id of [[IssueDependencyApi.removeBlock]]. */
  val RemoveBlockOperation: String = "issues.blocks.remove"

  /** The stable operation id of [[IssueDependencyApi.dependencies]]. */
  val ListDependenciesOperation: String = "issues.dependencies.list"

  /** The stable operation id of [[IssueDependencyApi.addDependency]]. */
  val AddDependencyOperation: String = "issues.dependencies.add"

  /** The stable operation id of [[IssueDependencyApi.removeDependency]]. */
  val RemoveDependencyOperation: String = "issues.dependencies.remove"

  /** The typed rail of [[IssueDependencyApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.issues.dependencies.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: IssueDependencyApi)(using exec: Exec[Future]):

    /** [[IssueDependencyApi.blocks]] with its failure as a value. */
    def blocks(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        params: PageParams,
    ): Future[Either[CodebergError, Page[Issue]]] =
      exec.attempt(rail.blocks(owner, name, number, params))

    /** [[IssueDependencyApi.addBlock]] with its failure as a value. */
    def addBlock(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        blocked: IssueRef,
    ): Future[Either[CodebergError, Issue]] =
      exec.attempt(rail.addBlock(owner, name, number, blocked))

    /** [[IssueDependencyApi.removeBlock]] with its failure as a value. */
    def removeBlock(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        blocked: IssueRef,
    ): Future[Either[CodebergError, Issue]] =
      exec.attempt(rail.removeBlock(owner, name, number, blocked))

    /** [[IssueDependencyApi.dependencies]] with its failure as a value. */
    def dependencies(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        params: PageParams,
    ): Future[Either[CodebergError, Page[Issue]]] =
      exec.attempt(rail.dependencies(owner, name, number, params))

    /** [[IssueDependencyApi.addDependency]] with its failure as a value. */
    def addDependency(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        blocker: IssueRef,
    ): Future[Either[CodebergError, Issue]] =
      exec.attempt(rail.addDependency(owner, name, number, blocker))

    /** [[IssueDependencyApi.removeDependency]] with its failure as a value. */
    def removeDependency(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        blocker: IssueRef,
    ): Future[Either[CodebergError, Issue]] =
      exec.attempt(rail.removeDependency(owner, name, number, blocker))

  private def blocksRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      params: PageParams,
  ): CodebergRequest =
    read(ListBlocksOperation, blocksPath(owner, name, number), PagingQuery.window(params))

  private def addBlockRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      blocked: IssueRef,
  ): CodebergRequest =
    write(
      AddBlockOperation,
      HttpMethod.Post,
      blocksPath(owner, name, number),
      IssueMetaDto.render(blocked),
    )

  private def removeBlockRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      blocked: IssueRef,
  ): CodebergRequest =
    removeWithBody(
      RemoveBlockOperation,
      blocksPath(owner, name, number),
      IssueMetaDto.render(blocked),
    )

  private def dependenciesRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      params: PageParams,
  ): CodebergRequest =
    read(ListDependenciesOperation, dependenciesPath(owner, name, number), PagingQuery.window(params))

  private def addDependencyRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      blocker: IssueRef,
  ): CodebergRequest =
    write(
      AddDependencyOperation,
      HttpMethod.Post,
      dependenciesPath(owner, name, number),
      IssueMetaDto.render(blocker),
    )

  private def removeDependencyRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      blocker: IssueRef,
  ): CodebergRequest =
    removeWithBody(
      RemoveDependencyOperation,
      dependenciesPath(owner, name, number),
      IssueMetaDto.render(blocker),
    )

  private def blocksPath(owner: Owner, name: RepoName, number: IssueNumber): List[String] =
    IssueRequests.issuePath(owner, name, number) :+ "blocks"

  private def dependenciesPath(owner: Owner, name: RepoName, number: IssueNumber): List[String] =
    IssueRequests.issuePath(owner, name, number) :+ "dependencies"
