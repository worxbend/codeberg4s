package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.core.CodebergRequest.{bodiless, read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.admin.wire.PushMirrorOptionDto
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName, RepositoryRequests}

import scala.concurrent.Future

/** Keeping a repository in step with a copy of it elsewhere: pull mirrors, push mirrors, and fork sync.
  *
  * Reached as `client.repos.admin.mirrors`. It is a group of its own rather than more methods on [[RepositoryAdminApi]]
  * because that class had grown past what a reader can hold in their head; the endpoints, the models and the retry
  * decisions are unchanged by the move.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[RepositoryMirrorApi.attempt]] never fail
  * and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Three mechanisms, not one==
  *
  * Forgejo copies commits between repositories in three unrelated ways, and this group serves all three because a
  * caller reaching for one usually has to know about the others:
  *
  *   - a '''pull mirror''' is a repository that fetches from an upstream on a schedule; [[syncMirror]] asks for that
  *     fetch to happen now;
  *   - a '''push mirror''' is an outbound copy this repository writes to; the push operations manage the list of them
  *     and [[syncPushMirrors]] pushes to all of them now;
  *   - '''fork sync''' pulls the parent repository's commits into a fork, which is what [[forkSyncInfo]] reports on and
  *     [[syncFork]] performs.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository does not exist '''or''' is
  *     private to credentials the client does not have — Forgejo does not distinguish the two, on purpose — `401` when
  *     a token was required and none was sent, and `403` when the token lacks the scope or the account lacks the
  *     permission. `422` '''and''' `400` both mean the request was rejected as invalid; `docs/HAZARDS.md` §4 records
  *     Forgejo using `400` where a reader would expect `422`.
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
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. Every `POST` here uses
  * [[com.worxbend.codeberg4s.core.RetryEligibility.Never]], because Forgejo offers no idempotency key and a repeated
  * sync is a second round of network traffic against somebody else's host. [[deletePushMirror]] is the one call that
  * names an identifier the server never reuses, so it is the one that can be retried unconditionally.
  *
  * ==Evidence==
  *
  * '''Every model this group declares is derived from `spec/swagger.v1.json`, not from a captured response.''' The
  * harvest behind `modules/codec/test/resources/golden` was anonymous and every endpoint here requires a token, so no
  * fixture exists for any of them. Where a shape is asserted in a test, the payload was written by hand to match the
  * spec's definition — it is not evidence that Forgejo sends exactly this.
  */

final class RepositoryMirrorApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryMirrorApi.Attempt = RepositoryMirrorApi.Attempt(this)

  /** Fetches a pull mirror now, rather than waiting for its interval — `POST /repos/{owner}/{repo}/mirror-sync`.
    *
    * '''Answers `200` with an empty body''', not `204`; the body is ignored either way.
    *
    * '''Never retried''', as every `POST` here is. A repeat queues a second fetch, which is wasteful rather than
    * harmful — but this library does not decide on a caller's behalf that repeating a mutating call is acceptable.
    *
    * '''Success means "queued", not "fetched".''' Forgejo performs the fetch asynchronously, so a caller checking that
    * new commits arrived must poll the repository rather than trust this return.
    *
    * '''Failures.''' The group contract above. `403` is what a repository that is not a mirror answers.
    */
  def syncMirror(owner: Owner, name: RepoName): Future[Unit] =
    pipeline.callUnit(RepositoryMirrorApi.syncMirrorRequest(owner, name), RetryEligibility.Never)

  /** Lists a repository's push mirrors — `GET /repos/{owner}/{repo}/push_mirrors`.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5). A page past
    * the end is `200` with `[]`, not a `404`.
    *
    * '''This is how a broken mirror is discovered.''' A push mirror that keeps failing does not make any call fail; it
    * sets [[PushMirror.lastError]], and nothing else surfaces it.
    *
    * '''Failures.''' The group contract above.
    */
  def pushMirrors(owner: Owner, name: RepoName, params: PageParams): Future[Page[PushMirror]] =
    pipeline.callPage(RepositoryMirrorApi.pushMirrorsRequest(owner, name, params), params)(using
      RepositoryAdminDecoders.pushMirrors)

  /** Reads one push mirror by its remote name — `GET /repos/{owner}/{repo}/push_mirrors/{name}`.
    *
    * '''Failures.''' The group contract above.
    *
    * @param mirror
    *   the handle Forgejo generated for the mirror — see [[MirrorName]]
    */
  def pushMirror(owner: Owner, name: RepoName, mirror: MirrorName): Future[PushMirror] =
    pipeline.call(RepositoryMirrorApi.pushMirrorRequest(owner, name, mirror), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.pushMirror)

  /** Sets up a push mirror — `POST /repos/{owner}/{repo}/push_mirrors`.
    *
    * '''The request may carry a credential for the remote.''' It is a [[RemoteCredential]] and it never comes back; see
    * [[CreatePushMirror]] and [[PushMirror]].
    *
    * '''Never retried''', as every `POST` here is — a repeat creates a second mirror to the same remote, each with its
    * own generated [[MirrorName]], and Forgejo does not object.
    *
    * '''Answers `200`''', not `201`, with the mirror as stored. When the command asked for
    * [[CreatePushMirror.overSsh]], [[PushMirror.publicKey]] on the answer is the key to install on the remote.
    *
    * '''Failures.''' The group contract above. `400` is what an unusable remote address produces.
    */
  def addPushMirror(owner: Owner, name: RepoName, command: CreatePushMirror): Future[PushMirror] =
    pipeline.call(RepositoryMirrorApi.addPushMirrorRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryAdminDecoders.pushMirror)

  /** Removes a push mirror — `DELETE /repos/{owner}/{repo}/push_mirrors/{name}`.
    *
    * '''Retried.''' This is one of the four writes in the group that meets the bar: [[MirrorName]] is a handle Forgejo
    * generates per mirror and does not hand out again, so a repeat addresses the same mirror or nothing at all, the end
    * state after N attempts is the state after one, and nothing is created. The cost is the usual one — after a lost
    * success the retry answers `404`, so a `404` here means "it is gone", not necessarily "it was never there".
    *
    * '''Answers `204`''', with no body.
    *
    * '''Failures.''' The group contract above.
    */
  def deletePushMirror(owner: Owner, name: RepoName, mirror: MirrorName): Future[Unit] =
    pipeline.callUnit(RepositoryMirrorApi.deletePushMirrorRequest(owner, name, mirror), RetryEligibility.AlwaysRetry)

  /** Pushes every push mirror now — `POST /repos/{owner}/{repo}/push_mirrors-sync`.
    *
    * '''Answers `200` with an empty body.''' As [[syncMirror]], success means the pushes were queued.
    *
    * '''Never retried''', as every `POST` here is.
    *
    * '''Failures.''' The group contract above.
    */
  def syncPushMirrors(owner: Owner, name: RepoName): Future[Unit] =
    pipeline.callUnit(RepositoryMirrorApi.syncPushMirrorsRequest(owner, name), RetryEligibility.Never)

  // --- fork syncing ---------------------------------------------------------

  /** Describes how far the fork's default branch is behind upstream — `GET /repos/{owner}/{repo}/sync_fork`.
    *
    * The call to make before [[syncFork]]: [[ForkSyncInfo.allowed]] says whether Forgejo will do it at all, and
    * [[ForkSyncInfo.commitsBehind]] whether there is anything to do.
    *
    * '''Failures.''' The group contract above. `400` is what a repository that is not a fork answers.
    */
  def forkSyncInfo(owner: Owner, name: RepoName): Future[ForkSyncInfo] =
    pipeline.call(RepositoryMirrorApi.forkSyncInfoRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.forkSyncInfo)

  /** Describes how far one branch of the fork is behind upstream — `GET /repos/{owner}/{repo}/sync_fork/{branch}`.
    *
    * '''Failures.''' The group contract above. A branch that does not exist upstream is `allowed: false` rather than a
    * failure — see [[ForkSyncInfo]].
    */
  def branchForkSyncInfo(owner: Owner, name: RepoName, branch: BranchName): Future[ForkSyncInfo] =
    pipeline.call(
      RepositoryMirrorApi.branchForkSyncInfoRequest(owner, name, branch),
      RetryEligibility.IdempotentOnly,
    )(using RepositoryAdminDecoders.forkSyncInfo)

  /** Fast-forwards the fork's default branch to upstream — `POST /repos/{owner}/{repo}/sync_fork`.
    *
    * '''Never retried''', as every `POST` here is. A repeat after a lost success is harmless in effect — the branch is
    * already there — but this is a mutating call with no idempotency key, and [[forkSyncInfo]] resolves the uncertainty
    * exactly.
    *
    * '''Answers `204`''', with no body.
    *
    * '''Failures.''' The group contract above. `400` covers a repository that is not a fork and a branch that has
    * diverged rather than merely fallen behind; [[forkSyncInfo]] distinguishes them beforehand.
    */
  def syncFork(owner: Owner, name: RepoName): Future[Unit] =
    pipeline.callUnit(RepositoryMirrorApi.syncForkRequest(owner, name), RetryEligibility.Never)

  /** Fast-forwards one branch of the fork to upstream — `POST /repos/{owner}/{repo}/sync_fork/{branch}`.
    *
    * '''Never retried''', for the reason [[syncFork]] gives.
    *
    * '''Answers `204`''', with no body.
    *
    * '''Failures.''' The group contract above.
    */
  def syncForkBranch(owner: Owner, name: RepoName, branch: BranchName): Future[Unit] =
    pipeline.callUnit(RepositoryMirrorApi.syncForkBranchRequest(owner, name, branch), RetryEligibility.Never)

/** The requests this group issues, its operation ids, and its typed rail. */
object RepositoryMirrorApi:

  /** The stable operation id of [[RepositoryMirrorApi.syncMirror]]. */
  val SyncMirrorOperation: String = "repos.admin.mirror.sync"

  /** The stable operation id of [[RepositoryMirrorApi.pushMirrors]]. */
  val ListPushMirrorsOperation: String = "repos.admin.pushMirrors.list"

  /** The stable operation id of the single push-mirror read on [[RepositoryAdminApi]]. */
  val GetPushMirrorOperation: String = "repos.admin.pushMirrors.get"

  /** The stable operation id of [[RepositoryMirrorApi.addPushMirror]]. */
  val AddPushMirrorOperation: String = "repos.admin.pushMirrors.add"

  /** The stable operation id of [[RepositoryMirrorApi.deletePushMirror]]. */
  val DeletePushMirrorOperation: String = "repos.admin.pushMirrors.delete"

  /** The stable operation id of [[RepositoryMirrorApi.syncPushMirrors]]. */
  val SyncPushMirrorsOperation: String = "repos.admin.pushMirrors.sync"

  /** The stable operation id of [[RepositoryMirrorApi.forkSyncInfo]]. */
  val ForkSyncInfoOperation: String = "repos.admin.syncFork.defaultInfo"

  /** The stable operation id of [[RepositoryMirrorApi.branchForkSyncInfo]]. */
  val BranchForkSyncInfoOperation: String = "repos.admin.syncFork.branchInfo"

  /** The stable operation id of [[RepositoryMirrorApi.syncFork]]. */
  val SyncForkOperation: String = "repos.admin.syncFork.default"

  /** The stable operation id of [[RepositoryMirrorApi.syncForkBranch]]. */
  val SyncForkBranchOperation: String = "repos.admin.syncFork.branch"

  /** The typed rail of [[RepositoryMirrorApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.repos.admin.mirrors.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryMirrorApi)(using exec: Exec[Future]):

    /** [[RepositoryMirrorApi.syncMirror]] with its failure as a value. */
    def syncMirror(owner: Owner, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.syncMirror(owner, name))

    /** [[RepositoryMirrorApi.pushMirrors]] with its failure as a value. */
    def pushMirrors(owner: Owner, name: RepoName, params: PageParams): Future[Either[CodebergError, Page[PushMirror]]] =
      exec.attempt(rail.pushMirrors(owner, name, params))

    /** The single push-mirror read on [[RepositoryAdminApi]], with its failure as a value. */
    def pushMirror(owner: Owner, name: RepoName, mirror: MirrorName): Future[Either[CodebergError, PushMirror]] =
      exec.attempt(rail.pushMirror(owner, name, mirror))

    /** [[RepositoryMirrorApi.addPushMirror]] with its failure as a value. */
    def addPushMirror(
        owner: Owner,
        name: RepoName,
        command: CreatePushMirror,
    ): Future[Either[CodebergError, PushMirror]] =
      exec.attempt(rail.addPushMirror(owner, name, command))

    /** [[RepositoryMirrorApi.deletePushMirror]] with its failure as a value. */
    def deletePushMirror(owner: Owner, name: RepoName, mirror: MirrorName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deletePushMirror(owner, name, mirror))

    /** [[RepositoryMirrorApi.syncPushMirrors]] with its failure as a value. */
    def syncPushMirrors(owner: Owner, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.syncPushMirrors(owner, name))

    /** [[RepositoryMirrorApi.forkSyncInfo]] with its failure as a value. */
    def forkSyncInfo(owner: Owner, name: RepoName): Future[Either[CodebergError, ForkSyncInfo]] =
      exec.attempt(rail.forkSyncInfo(owner, name))

    /** [[RepositoryMirrorApi.branchForkSyncInfo]] with its failure as a value. */
    def branchForkSyncInfo(
        owner: Owner,
        name: RepoName,
        branch: BranchName,
    ): Future[Either[CodebergError, ForkSyncInfo]] =
      exec.attempt(rail.branchForkSyncInfo(owner, name, branch))

    /** [[RepositoryMirrorApi.syncFork]] with its failure as a value. */
    def syncFork(owner: Owner, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.syncFork(owner, name))

    /** [[RepositoryMirrorApi.syncForkBranch]] with its failure as a value. */
    def syncForkBranch(owner: Owner, name: RepoName, branch: BranchName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.syncForkBranch(owner, name, branch))

  private def syncMirrorRequest(owner: Owner, name: RepoName): CodebergRequest =
    bodiless(SyncMirrorOperation, HttpMethod.Post, RepositoryRequests.repositoryPath(owner, name) :+ "mirror-sync")

  private def pushMirrorsRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(ListPushMirrorsOperation, pushMirrorsPath(owner, name), PagingQuery.window(params))

  private def pushMirrorRequest(owner: Owner, name: RepoName, mirror: MirrorName): CodebergRequest =
    read(GetPushMirrorOperation, pushMirrorsPath(owner, name) :+ mirror.value, Nil)

  private def addPushMirrorRequest(owner: Owner, name: RepoName, command: CreatePushMirror): CodebergRequest =
    write(AddPushMirrorOperation, HttpMethod.Post, pushMirrorsPath(owner, name), PushMirrorOptionDto.render(command))

  private def deletePushMirrorRequest(owner: Owner, name: RepoName, mirror: MirrorName): CodebergRequest =
    remove(DeletePushMirrorOperation, pushMirrorsPath(owner, name) :+ mirror.value)

  private def syncPushMirrorsRequest(owner: Owner, name: RepoName): CodebergRequest =
    bodiless(
      SyncPushMirrorsOperation,
      HttpMethod.Post,
      RepositoryRequests.repositoryPath(owner, name) :+ "push_mirrors-sync"
    )

  private def forkSyncInfoRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ForkSyncInfoOperation, syncForkPath(owner, name), Nil)

  private def branchForkSyncInfoRequest(owner: Owner, name: RepoName, branch: BranchName): CodebergRequest =
    read(BranchForkSyncInfoOperation, syncForkPath(owner, name) ++ branch.segments, Nil)

  private def syncForkRequest(owner: Owner, name: RepoName): CodebergRequest =
    bodiless(SyncForkOperation, HttpMethod.Post, syncForkPath(owner, name))

  private def syncForkBranchRequest(owner: Owner, name: RepoName, branch: BranchName): CodebergRequest =
    bodiless(SyncForkBranchOperation, HttpMethod.Post, syncForkPath(owner, name) ++ branch.segments)

  private def pushMirrorsPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "push_mirrors"

  private def syncForkPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "sync_fork"
