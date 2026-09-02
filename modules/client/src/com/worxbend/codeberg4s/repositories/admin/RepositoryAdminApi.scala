package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.core.CodebergRequest.{bodiless, read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.repositories.admin.wire.{
  AvatarOptionDto,
  BranchOptionDto,
  MigrateRepoOptionsDto,
  RepositoryOptionDto,
  TransferRepoOptionDto
}
import com.worxbend.codeberg4s.repositories.{Branch, BranchName, Repository, RepositoryId}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName, RepositoryRequests}

import scala.concurrent.Future

/** Administering a repository: creating it, editing it, moving it, branching it, and eventually deleting it.
  *
  * Reached as `client.repos.admin`. Four groups that used to be methods here hang off it — [[mirrors]], [[contents]],
  * [[watchers]] and [[insights]] — because one class holding all of them had grown past what a reader can hold in their
  * head. What each operation calls, returns and retries is unchanged by that move.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[RepositoryAdminApi.attempt]] never fail
  * and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Evidence==
  *
  * '''Every model in this group is derived from `spec/swagger.v1.json`, not from a captured response.''' The harvest
  * behind `modules/codec/test/resources/golden` was anonymous and every endpoint here either requires a token or
  * describes the calling account, so no fixture exists for any of them. Where a shape is asserted in a test, the
  * payload was written by hand to match the spec's definition — it is not evidence that Forgejo sends exactly this. The
  * exceptions are the models this group '''reuses''' rather than declares:
  * [[com.worxbend.codeberg4s.repositories.Repository]], [[com.worxbend.codeberg4s.repositories.Branch]],
  * [[com.worxbend.codeberg4s.repositories.ContentEntry]], [[com.worxbend.codeberg4s.users.User]] and
  * [[com.worxbend.codeberg4s.issues.Issue]], each of which is backed by a golden capture in its own group.
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
  *     Forgejo using `400` where a reader would expect `422`. `409` means a name is already taken. `413` means an
  *     instance quota was exceeded. `423` means the repository is archived, which every write in this group can hit.
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
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. Every `POST` uses
  * [[com.worxbend.codeberg4s.core.RetryEligibility.Never]], because Forgejo offers no idempotency key and a repeated
  * `POST` here creates a second repository, a second branch or a second commit.
  *
  * The `PUT`, `PATCH` and `DELETE` decisions are made per endpoint and justified where they are made. The bar earlier
  * groups set is that [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]] requires the request to name one
  * identifier the server never reuses, so that the end state after N attempts is the state after one and nothing is
  * created. One call in this class meets it — [[deleteAvatar]]. The rest do not, and the reason is worth stating once
  * here because it recurs:
  *
  *   - a '''branch name is reused'''. A retry of [[deleteBranch]] after a lost success would delete whatever now
  *     carries that name, which may be a branch somebody recreated in between;
  *   - a '''rename moves the target'''. A retry of [[edit]] or [[renameBranch]] after a lost success addresses the old
  *     name, which is either gone — a `404` reported for a call that succeeded — or, worse, taken by something else;
  *   - [[delete]] is discussed on its own method, at length.
  *
  * ==What is not here==
  *
  * Two spec operations in this group's tag are not served here:
  *
  *   - `GET /repos/{owner}/{repo}/actions/artifacts/{artifact_id}/zip` (`DownloadActionArtifact`);
  *   - `GET /repos/{owner}/{repo}/actions/runs/{run_id}/logs` (`repoGetActionRunLogs`).
  *
  * They belong to the Actions surface and are implemented there, as
  * [[com.worxbend.codeberg4s.repositories.actions.RepositoryActionApi.downloadArtifact]] and
  * [[com.worxbend.codeberg4s.repositories.actions.RepositoryActionApi.downloadRunLogs]] — reached as
  * `client.repos.actions`.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class RepositoryAdminApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryAdminApi.Attempt = RepositoryAdminApi.Attempt(this)

  /** Pull mirrors, push mirrors and fork sync: keeping this repository in step with a copy of it elsewhere. */
  val mirrors: RepositoryMirrorApi = RepositoryMirrorApi(pipeline)

  /** The repository's files, and the commits that change them without a clone. */
  val contents: RepositoryContentApi = RepositoryContentApi(pipeline)

  /** Who is watching, who has starred, and who may be assigned work on this repository. */
  val watchers: RepositoryWatcherApi = RepositoryWatcherApi(pipeline)

  /** What the repository reports about itself: activity, languages, pins, topics and tracked time. */
  val insights: RepositoryInsightApi = RepositoryInsightApi(pipeline)

  // --- the repository itself ---------------------------------------------

  /** Creates a repository owned by the authenticated account — `POST /user/repos`.
    *
    * '''Never retried.''' A repeat either creates a second repository or comes back `409`; Forgejo has no idempotency
    * key that would let it recognise the repeat.
    *
    * '''Without [[CreateRepository.initialised]] the repository has no commits''' and no default branch, which makes
    * every contents write in this group answer `404` until something is pushed. See [[CreateRepository]].
    *
    * '''Failures.''' The group contract above. `409` means the name is already taken by this account, `422` that
    * Forgejo rejected the name or a template that does not exist, and `413` that the account is over its quota.
    */
  def create(command: CreateRepository): Future[Repository] =
    pipeline.call(RepositoryAdminApi.createRequest(command), RetryEligibility.Never)(using
      RepositoryAdminDecoders.repository)

  /** Reads a repository by its instance-wide identifier — `GET /repositories/{id}`.
    *
    * '''The only lookup that survives a rename or a transfer.''' `owner/name` does not: both halves change when a
    * repository moves, while [[RepositoryId]] does not. Store the id, not the slug, if a reference has to outlive
    * either.
    *
    * '''Failures.''' The group contract above.
    */
  def byId(id: RepositoryId): Future[Repository] =
    pipeline.call(RepositoryAdminApi.byIdRequest(id), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.repository)

  /** Changes a repository's properties — `PATCH /repos/{owner}/{repo}`.
    *
    * '''Only what the command set is changed'''; see [[EditRepository]] for why every property there is an `Option` and
    * what an [[EditRepository.Empty]] does.
    *
    * '''Never retried.''' A `PATCH` that sets fields to stated values would ordinarily be safe to repeat, but this one
    * can carry [[EditRepository.renamedTo]]: after a lost success the repository is at its new name, so the retry
    * addresses the old path — which either `404`s, turning a success into a reported failure, or reaches a
    * '''different''' repository that has since been created there and edits that instead. Choosing per call was
    * considered and rejected: an edit that renames and an edit that does not are the same method, and a rule a caller
    * cannot see is worse than a conservative one they can.
    *
    * '''Failures.''' The group contract above. `422` covers a name already in use, a default branch that does not
    * exist, a merge style the repository has not enabled, and an organisation that forbids the visibility change.
    */
  def edit(owner: Owner, name: RepoName, command: EditRepository): Future[Repository] =
    pipeline.call(RepositoryAdminApi.editRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryAdminDecoders.repository)

  /** Deletes a repository — `DELETE /repos/{owner}/{repo}`.
    *
    * '''This is the most destructive operation in this library, and it takes no confirmation.''' Forgejo offers no
    * confirmation token, no "are you sure" parameter and no soft-delete window on this endpoint: the call returns `204`
    * and the repository, its issues, its pull requests, its releases, its wiki, its packages and its Actions history
    * are gone. There is no undelete. A caller who wants a safety net has to build it — read the repository first,
    * require an explicit confirmation in their own interface, or archive it with [[EditRepository.archivedRepository]]
    * instead, which is reversible and stops every write.
    *
    * '''Never retried, and the reason is not squeamishness.''' A repository name '''is''' reused: the moment this
    * succeeds, `owner/name` is free, and the same account or another one may create something there. If the success
    * response were lost and this call were repeated, the retry would address whatever now stands at that path and
    * delete '''that''' instead. That is the exact failure mode
    * [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]] is meant to exclude, and it is why no amount of
    * "deleting twice is harmless" reasoning applies here.
    *
    * A caller who sees a [[com.worxbend.codeberg4s.CodebergError.Transport]] from this call therefore does '''not'''
    * know whether the repository is gone. [[byId]] with the id read beforehand is the way to find out, and it is the
    * one lookup a rename cannot confuse.
    *
    * '''Answers `204`''', with no body.
    *
    * '''Failures.''' The group contract above. `403` is what a non-owner gets, and what an instance that forbids
    * deletion answers.
    */
  def delete(owner: Owner, name: RepoName): Future[Unit] =
    pipeline.callUnit(RepositoryAdminApi.deleteRequest(owner, name), RetryEligibility.Never)

  /** Copies a repository from another host — `POST /repos/migrate`.
    *
    * '''The request carries a credential for somebody else's forge.''' It is a [[RemoteCredential]], which masks itself
    * everywhere except the request body; see that type and [[MigrateRepository]] for the whole argument.
    *
    * '''Never retried.''' A repeat starts a second migration, which either produces a second repository under a
    * different name or comes back `409`. Migration is also slow — Forgejo runs it asynchronously and the `201` means
    * "accepted and started", not "finished" — so a caller who loses the response should look for the repository rather
    * than ask again.
    *
    * '''Failures.''' The group contract above. `409` means the name is taken, `422` that Forgejo could not reach or
    * could not read the remote, and `413` that the account is over its quota.
    */
  def migrate(command: MigrateRepository): Future[Repository] =
    pipeline.call(RepositoryAdminApi.migrateRequest(command), RetryEligibility.Never)(using
      RepositoryAdminDecoders.repository)

  /** Offers a repository to another owner — `POST /repos/{owner}/{repo}/transfer`.
    *
    * '''Answers `202`, and the repository has usually not moved yet.''' The transfer is pending until the receiving
    * side calls [[acceptTransfer]] or [[rejectTransfer]]; Forgejo completes it immediately only when the caller already
    * controls the destination. Both outcomes are the same `202` with the same body, so read the repository back if the
    * difference matters — see [[TransferRepository]].
    *
    * '''Never retried''', as every `POST` here is. A repeat while a transfer is pending is a `422`.
    *
    * '''Failures.''' The group contract above. `422` covers an unknown destination account, a name already taken there,
    * and teams named for a destination that is not an organisation.
    */
  def transfer(owner: Owner, name: RepoName, command: TransferRepository): Future[Repository] =
    pipeline.call(RepositoryAdminApi.transferRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryAdminDecoders.repository)

  /** Accepts a pending transfer — `POST /repos/{owner}/{repo}/transfer/accept`.
    *
    * Called by the '''receiving''' side, against the repository at its current path. Answers `202` and the repository
    * as it now stands.
    *
    * '''Never retried''', as every `POST` here is. A repeat after a lost success is a `404`, because the repository has
    * moved to the new owner's path.
    *
    * '''Failures.''' The group contract above. `404` also means there is no transfer pending.
    */
  def acceptTransfer(owner: Owner, name: RepoName): Future[Repository] =
    pipeline.call(RepositoryAdminApi.acceptTransferRequest(owner, name), RetryEligibility.Never)(using
      RepositoryAdminDecoders.repository)

  /** Rejects a pending transfer — `POST /repos/{owner}/{repo}/transfer/reject`.
    *
    * Called by the receiving side. Answers `200` and the repository, still where it was.
    *
    * '''Never retried''', as every `POST` here is. A repeat is a `404`, because there is no longer a transfer to
    * reject.
    *
    * '''Failures.''' The group contract above.
    */
  def rejectTransfer(owner: Owner, name: RepoName): Future[Repository] =
    pipeline.call(RepositoryAdminApi.rejectTransferRequest(owner, name), RetryEligibility.Never)(using
      RepositoryAdminDecoders.repository)

  /** Turns a pull mirror into an ordinary repository — `POST /repos/{owner}/{repo}/convert`.
    *
    * '''One-way.''' There is no endpoint that turns an ordinary repository back into a mirror; the only route back is
    * to delete it and [[migrate]] again with [[MigrateRepository.asMirror]]. After this, pushes are accepted and
    * Forgejo stops fetching from upstream.
    *
    * '''Never retried''', as every `POST` here is. A repeat is a `422`, because the repository is no longer a mirror.
    *
    * '''Failures.''' The group contract above. `422` is also what a repository that was never a mirror answers.
    */
  def convert(owner: Owner, name: RepoName): Future[Repository] =
    pipeline.call(RepositoryAdminApi.convertRequest(owner, name), RetryEligibility.Never)(using
      RepositoryAdminDecoders.repository)

  // --- branches ----------------------------------------------------------

  /** Creates a branch — `POST /repos/{owner}/{repo}/branches`.
    *
    * '''Never retried''', as every `POST` here is. A repeat after a lost success answers `409`, which would report a
    * failure for a branch that exists exactly as the caller asked.
    *
    * '''Answers `201`''' and the branch as created.
    *
    * '''Failures.''' The group contract above. `404` here means '''the starting ref''' does not exist, not the
    * repository; `409` that the branch name is taken; `403` that the repository is a mirror; `423` that it is archived.
    */
  def createBranch(owner: Owner, name: RepoName, command: CreateBranch): Future[Branch] =
    pipeline.call(RepositoryAdminApi.createBranchRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryAdminDecoders.branch)

  /** Deletes a branch — `DELETE /repos/{owner}/{repo}/branches/{branch}`.
    *
    * '''Never retried, unlike most deletes in this library.''' A branch name is '''reused''': the instant this succeeds
    * the name is free, and a fork sync, a merge or another client may put a new branch there. A retry after a lost
    * success would delete that one. [[MirrorName]] and an artifact id are not reused, which is why [[deletePushMirror]]
    * is retried and this is not.
    *
    * '''Answers `204`''', with no body. The commits that were only on this branch become unreachable and are removed by
    * Forgejo's garbage collection in due course; recovering one before then means finding its id in the reflog, which
    * the API does not expose.
    *
    * '''Failures.''' The group contract above. `403` is what a protected branch and the default branch both answer;
    * `423` is what an archived repository answers.
    */
  def deleteBranch(owner: Owner, name: RepoName, branch: BranchName): Future[Unit] =
    pipeline.callUnit(RepositoryAdminApi.deleteBranchRequest(owner, name, branch), RetryEligibility.Never)

  /** Renames a branch — `PATCH /repos/{owner}/{repo}/branches/{branch}`.
    *
    * '''This endpoint only renames.''' Despite Forgejo's "update a branch" summary, the request model has one property.
    * See [[RenameBranch]].
    *
    * '''Never retried.''' After a lost success the branch is at its new name, so the retry addresses a path that is
    * either gone — a `404` reported for a call that succeeded — or occupied by a branch somebody recreated, which it
    * would then rename. Same argument as [[edit]].
    *
    * '''Answers `204`''', with no body; read the branch back through `RepositoryApi` if the result is wanted.
    *
    * '''Failures.''' The group contract above. `422` is what a name already in use produces.
    */
  def renameBranch(owner: Owner, name: RepoName, branch: BranchName, command: RenameBranch): Future[Unit] =
    pipeline.callUnit(RepositoryAdminApi.renameBranchRequest(owner, name, branch, command), RetryEligibility.Never)

  // --- the avatar --------------------------------------------------------

  /** Sets the repository's avatar — `POST /repos/{owner}/{repo}/avatar`.
    *
    * '''A JSON body carrying Base64, not a multipart upload.''' That is what the spec declares, and it is the opposite
    * of how release assets are sent; see [[AvatarImage]].
    *
    * '''Never retried''', as every `POST` here is. A repeat is in fact harmless — the avatar ends up the same either
    * way — but a `POST` with a body is not something this library repeats on a caller's behalf.
    *
    * '''Answers `204`''', with no body.
    *
    * '''Failures.''' The group contract above. `422` is what an image Forgejo cannot decode or will not accept
    * produces.
    */
  def updateAvatar(owner: Owner, name: RepoName, image: AvatarImage): Future[Unit] =
    pipeline.callUnit(RepositoryAdminApi.updateAvatarRequest(owner, name, image), RetryEligibility.Never)

  /** Removes the repository's avatar — `DELETE /repos/{owner}/{repo}/avatar`.
    *
    * '''Retried.''' This meets the bar: the request names the repository in its path and nothing else, sets its avatar
    * to absent, and creates nothing. Forgejo answers `204` whether or not there was an avatar, so unlike most deletes
    * there is not even a `404`-after-success cost — the end state and the reported outcome are both identical after N
    * attempts.
    *
    * '''Answers `204`''', with no body.
    *
    * '''Failures.''' The group contract above.
    */
  def deleteAvatar(owner: Owner, name: RepoName): Future[Unit] =
    pipeline.callUnit(RepositoryAdminApi.deleteAvatarRequest(owner, name), RetryEligibility.AlwaysRetry)

/** The requests this group issues, its operation ids, and its typed rail. */
object RepositoryAdminApi:

  /** The stable operation id of [[RepositoryAdminApi.create]]. Safe to alert on. */
  val CreateOperation: String = "repos.admin.create"

  /** The stable operation id of [[RepositoryAdminApi.byId]]. */
  val GetByIdOperation: String = "repos.admin.getById"

  /** The stable operation id of [[RepositoryAdminApi.edit]]. */
  val EditOperation: String = "repos.admin.edit"

  /** The stable operation id of [[RepositoryAdminApi.delete]]. */
  val DeleteOperation: String = "repos.admin.delete"

  /** The stable operation id of [[RepositoryAdminApi.migrate]]. */
  val MigrateOperation: String = "repos.admin.migrate"

  /** The stable operation id of [[RepositoryAdminApi.transfer]]. */
  val TransferOperation: String = "repos.admin.transfer.start"

  /** The stable operation id of [[RepositoryAdminApi.acceptTransfer]]. */
  val AcceptTransferOperation: String = "repos.admin.transfer.accept"

  /** The stable operation id of [[RepositoryAdminApi.rejectTransfer]]. */
  val RejectTransferOperation: String = "repos.admin.transfer.reject"

  /** The stable operation id of [[RepositoryAdminApi.convert]]. */
  val ConvertOperation: String = "repos.admin.convert"

  /** The stable operation id of [[RepositoryAdminApi.createBranch]]. */
  val CreateBranchOperation: String = "repos.admin.branches.create"

  /** The stable operation id of [[RepositoryAdminApi.deleteBranch]]. */
  val DeleteBranchOperation: String = "repos.admin.branches.delete"

  /** The stable operation id of [[RepositoryAdminApi.renameBranch]]. */
  val RenameBranchOperation: String = "repos.admin.branches.rename"

  /** The stable operation id of [[RepositoryAdminApi.updateAvatar]]. */
  val UpdateAvatarOperation: String = "repos.admin.avatar.update"

  /** The stable operation id of [[RepositoryAdminApi.deleteAvatar]]. */
  val DeleteAvatarOperation: String = "repos.admin.avatar.delete"

  /** The typed rail of [[RepositoryAdminApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.repos.admin.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryAdminApi)(using exec: Exec[Future]):

    /** [[RepositoryAdminApi.create]] with its failure as a value. */
    def create(command: CreateRepository): Future[Either[CodebergError, Repository]] =
      exec.attempt(rail.create(command))

    /** [[RepositoryAdminApi.byId]] with its failure as a value. */
    def byId(id: RepositoryId): Future[Either[CodebergError, Repository]] =
      exec.attempt(rail.byId(id))

    /** [[RepositoryAdminApi.edit]] with its failure as a value. */
    def edit(owner: Owner, name: RepoName, command: EditRepository): Future[Either[CodebergError, Repository]] =
      exec.attempt(rail.edit(owner, name, command))

    /** [[RepositoryAdminApi.delete]] with its failure as a value. */
    def delete(owner: Owner, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.delete(owner, name))

    /** [[RepositoryAdminApi.migrate]] with its failure as a value. */
    def migrate(command: MigrateRepository): Future[Either[CodebergError, Repository]] =
      exec.attempt(rail.migrate(command))

    /** [[RepositoryAdminApi.transfer]] with its failure as a value. */
    def transfer(
        owner: Owner,
        name: RepoName,
        command: TransferRepository,
    ): Future[Either[CodebergError, Repository]] =
      exec.attempt(rail.transfer(owner, name, command))

    /** [[RepositoryAdminApi.acceptTransfer]] with its failure as a value. */
    def acceptTransfer(owner: Owner, name: RepoName): Future[Either[CodebergError, Repository]] =
      exec.attempt(rail.acceptTransfer(owner, name))

    /** [[RepositoryAdminApi.rejectTransfer]] with its failure as a value. */
    def rejectTransfer(owner: Owner, name: RepoName): Future[Either[CodebergError, Repository]] =
      exec.attempt(rail.rejectTransfer(owner, name))

    /** [[RepositoryAdminApi.convert]] with its failure as a value. */
    def convert(owner: Owner, name: RepoName): Future[Either[CodebergError, Repository]] =
      exec.attempt(rail.convert(owner, name))

    /** [[RepositoryAdminApi.createBranch]] with its failure as a value. */
    def createBranch(owner: Owner, name: RepoName, command: CreateBranch): Future[Either[CodebergError, Branch]] =
      exec.attempt(rail.createBranch(owner, name, command))

    /** [[RepositoryAdminApi.deleteBranch]] with its failure as a value. */
    def deleteBranch(owner: Owner, name: RepoName, branch: BranchName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteBranch(owner, name, branch))

    /** [[RepositoryAdminApi.renameBranch]] with its failure as a value. */
    def renameBranch(
        owner: Owner,
        name: RepoName,
        branch: BranchName,
        command: RenameBranch,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.renameBranch(owner, name, branch, command))

    /** [[RepositoryAdminApi.updateAvatar]] with its failure as a value. */
    def updateAvatar(owner: Owner, name: RepoName, image: AvatarImage): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.updateAvatar(owner, name, image))

    /** [[RepositoryAdminApi.deleteAvatar]] with its failure as a value. */
    def deleteAvatar(owner: Owner, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteAvatar(owner, name))

  private def createRequest(command: CreateRepository): CodebergRequest =
    write(CreateOperation, HttpMethod.Post, List("user", "repos"), RepositoryOptionDto.renderCreate(command))

  private def byIdRequest(id: RepositoryId): CodebergRequest =
    read(GetByIdOperation, List("repositories", id.value.toString), Nil)

  private def editRequest(owner: Owner, name: RepoName, command: EditRepository): CodebergRequest =
    write(
      EditOperation,
      HttpMethod.Patch,
      RepositoryRequests.repositoryPath(owner, name),
      RepositoryOptionDto.renderEdit(command)
    )

  private def deleteRequest(owner: Owner, name: RepoName): CodebergRequest =
    remove(DeleteOperation, RepositoryRequests.repositoryPath(owner, name))

  private def migrateRequest(command: MigrateRepository): CodebergRequest =
    write(
      MigrateOperation,
      HttpMethod.Post,
      RepositoryRequests.reposPath :+ "migrate",
      MigrateRepoOptionsDto.render(command)
    )

  private def transferRequest(owner: Owner, name: RepoName, command: TransferRepository): CodebergRequest =
    write(
      TransferOperation,
      HttpMethod.Post,
      RepositoryRequests.repositoryPath(owner, name) :+ "transfer",
      TransferRepoOptionDto.render(command),
    )

  private def acceptTransferRequest(owner: Owner, name: RepoName): CodebergRequest =
    bodiless(
      AcceptTransferOperation,
      HttpMethod.Post,
      RepositoryRequests.repositoryPath(owner, name) ++ List("transfer", "accept")
    )

  private def rejectTransferRequest(owner: Owner, name: RepoName): CodebergRequest =
    bodiless(
      RejectTransferOperation,
      HttpMethod.Post,
      RepositoryRequests.repositoryPath(owner, name) ++ List("transfer", "reject")
    )

  private def convertRequest(owner: Owner, name: RepoName): CodebergRequest =
    bodiless(ConvertOperation, HttpMethod.Post, RepositoryRequests.repositoryPath(owner, name) :+ "convert")

  private def createBranchRequest(owner: Owner, name: RepoName, command: CreateBranch): CodebergRequest =
    write(CreateBranchOperation, HttpMethod.Post, branchesPath(owner, name), BranchOptionDto.renderCreate(command))

  private def deleteBranchRequest(owner: Owner, name: RepoName, branch: BranchName): CodebergRequest =
    remove(DeleteBranchOperation, branchesPath(owner, name) ++ branch.segments)

  private def renameBranchRequest(
      owner: Owner,
      name: RepoName,
      branch: BranchName,
      command: RenameBranch,
  ): CodebergRequest =
    write(
      RenameBranchOperation,
      HttpMethod.Patch,
      branchesPath(owner, name) ++ branch.segments,
      BranchOptionDto.renderRename(command),
    )

  private def updateAvatarRequest(owner: Owner, name: RepoName, image: AvatarImage): CodebergRequest =
    write(UpdateAvatarOperation, HttpMethod.Post, avatarPath(owner, name), AvatarOptionDto.render(image))

  private def deleteAvatarRequest(owner: Owner, name: RepoName): CodebergRequest =
    remove(DeleteAvatarOperation, avatarPath(owner, name))

  private def branchesPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "branches"

  private def avatarPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "avatar"
