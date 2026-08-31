package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RequestBody
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.issues.Issue
import com.worxbend.codeberg4s.issues.TrackedTime
import com.worxbend.codeberg4s.issues.TrackedTimeQuery
import com.worxbend.codeberg4s.issues.wire.IssueQueries
import com.worxbend.codeberg4s.miscellaneous.SigningKey
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.Branch
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.ContentEntry
import com.worxbend.codeberg4s.repositories.ContentPath
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.repositories.admin.wire.AdminQueries
import com.worxbend.codeberg4s.repositories.admin.wire.AvatarOptionDto
import com.worxbend.codeberg4s.repositories.admin.wire.BranchOptionDto
import com.worxbend.codeberg4s.repositories.admin.wire.FileOptionsDto
import com.worxbend.codeberg4s.repositories.admin.wire.MigrateRepoOptionsDto
import com.worxbend.codeberg4s.repositories.admin.wire.PushMirrorOptionDto
import com.worxbend.codeberg4s.repositories.admin.wire.RepositoryOptionDto
import com.worxbend.codeberg4s.repositories.admin.wire.TransferRepoOptionDto
import com.worxbend.codeberg4s.repositories.gitdata.FileChange
import com.worxbend.codeberg4s.repositories.gitdata.RefName
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.Username

import scala.concurrent.Future

import java.time.LocalDate

/** Administering a repository: creating it, editing it, moving it, mirroring it, writing files into it, and eventually
  * deleting it.
  *
  * Reached as `client.repos.admin`. Both error rails are here (ADR-0005): the methods on this class fail the `Future`
  * with [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[RepositoryAdminApi.attempt]] never
  * fail and return an `Either` instead. The typed rail is derived from this one by
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
  * created. Four calls meet it — [[watch]], [[unwatch]], [[deleteAvatar]] and [[deletePushMirror]]. The rest do not,
  * and the reason is worth stating once here because it recurs:
  *
  *   - a '''branch name is reused'''. A retry of [[deleteBranch]] after a lost success would delete whatever now
  *     carries that name, which may be a branch somebody recreated in between;
  *   - a '''rename moves the target'''. A retry of [[edit]] or [[renameBranch]] after a lost success addresses the old
  *     name, which is either gone — a `404` reported for a call that succeeded — or, worse, taken by something else;
  *   - a '''contents write names the blob it expects to replace'''. After a lost success that blob id no longer exists,
  *     so a retry of [[updateFile]] or [[deleteFile]] answers `409`. The guard makes the retry harmless and useless at
  *     the same time, and reporting a `409` for a write that landed is not an improvement;
  *   - [[delete]] is discussed on its own method, at length.
  *
  * ==What is not here==
  *
  * Two spec operations in this group's tag are deliberately absent, for the same reason the Actions group leaves two
  * out:
  *
  *   - `GET /repos/{owner}/{repo}/actions/artifacts/{artifact_id}/zip` (`DownloadActionArtifact`);
  *   - `GET /repos/{owner}/{repo}/actions/runs/{run_id}/logs` (`repoGetActionRunLogs`).
  *
  * Both answer a ZIP archive, so neither is served by a class whose every other operation decodes text. They are
  * implemented on [[com.worxbend.codeberg4s.repositories.actions.ActionDownloadApi]], reached as `client.downloads`,
  * which reads a body as bytes.
  *
  * @param pipeline
  *   the shared request pipeline; the only thing here that reaches the network
  */
final class RepositoryAdminApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryAdminApi.Attempt = RepositoryAdminApi.Attempt(this)

  // --- the repository itself ------------------------------------------------

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

  // --- mirrors --------------------------------------------------------------

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
    pipeline.callUnit(RepositoryAdminApi.syncMirrorRequest(owner, name), RetryEligibility.Never)

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
    pipeline.callPage(RepositoryAdminApi.pushMirrorsRequest(owner, name, params), params)(using
      RepositoryAdminDecoders.pushMirrors)

  /** Reads one push mirror by its remote name — `GET /repos/{owner}/{repo}/push_mirrors/{name}`.
    *
    * '''Failures.''' The group contract above.
    *
    * @param mirror
    *   the handle Forgejo generated for the mirror — see [[MirrorName]]
    */
  def pushMirror(owner: Owner, name: RepoName, mirror: MirrorName): Future[PushMirror] =
    pipeline.call(RepositoryAdminApi.pushMirrorRequest(owner, name, mirror), RetryEligibility.IdempotentOnly)(using
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
    pipeline.call(RepositoryAdminApi.addPushMirrorRequest(owner, name, command), RetryEligibility.Never)(using
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
    pipeline.callUnit(RepositoryAdminApi.deletePushMirrorRequest(owner, name, mirror), RetryEligibility.AlwaysRetry)

  /** Pushes every push mirror now — `POST /repos/{owner}/{repo}/push_mirrors-sync`.
    *
    * '''Answers `200` with an empty body.''' As [[syncMirror]], success means the pushes were queued.
    *
    * '''Never retried''', as every `POST` here is.
    *
    * '''Failures.''' The group contract above.
    */
  def syncPushMirrors(owner: Owner, name: RepoName): Future[Unit] =
    pipeline.callUnit(RepositoryAdminApi.syncPushMirrorsRequest(owner, name), RetryEligibility.Never)

  // --- fork syncing ---------------------------------------------------------

  /** Describes how far the fork's default branch is behind upstream — `GET /repos/{owner}/{repo}/sync_fork`.
    *
    * The call to make before [[syncFork]]: [[ForkSyncInfo.allowed]] says whether Forgejo will do it at all, and
    * [[ForkSyncInfo.commitsBehind]] whether there is anything to do.
    *
    * '''Failures.''' The group contract above. `400` is what a repository that is not a fork answers.
    */
  def forkSyncInfo(owner: Owner, name: RepoName): Future[ForkSyncInfo] =
    pipeline.call(RepositoryAdminApi.forkSyncInfoRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.forkSyncInfo)

  /** Describes how far one branch of the fork is behind upstream — `GET /repos/{owner}/{repo}/sync_fork/{branch}`.
    *
    * '''Failures.''' The group contract above. A branch that does not exist upstream is `allowed: false` rather than a
    * failure — see [[ForkSyncInfo]].
    */
  def branchForkSyncInfo(owner: Owner, name: RepoName, branch: BranchName): Future[ForkSyncInfo] =
    pipeline.call(
      RepositoryAdminApi.branchForkSyncInfoRequest(owner, name, branch),
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
    pipeline.callUnit(RepositoryAdminApi.syncForkRequest(owner, name), RetryEligibility.Never)

  /** Fast-forwards one branch of the fork to upstream — `POST /repos/{owner}/{repo}/sync_fork/{branch}`.
    *
    * '''Never retried''', for the reason [[syncFork]] gives.
    *
    * '''Answers `204`''', with no body.
    *
    * '''Failures.''' The group contract above.
    */
  def syncForkBranch(owner: Owner, name: RepoName, branch: BranchName): Future[Unit] =
    pipeline.callUnit(RepositoryAdminApi.syncForkBranchRequest(owner, name, branch), RetryEligibility.Never)

  // --- watching -------------------------------------------------------------

  /** Reads whether the authenticated account watches the repository — `GET /repos/{owner}/{repo}/subscription`.
    *
    * '''Not watching is a `404`, not a `false`.''' The spec says so in as many words, and it is the same status a
    * repository the caller cannot see produces — so this method '''cannot''' be used as a "do I watch this?" predicate
    * without also treating "no such repository" as "not watching". Read [[WatchStatus]] before relying on it.
    *
    * '''Failures.''' The group contract above, with the `404` caveat above.
    */
  def subscription(owner: Owner, name: RepoName): Future[WatchStatus] =
    pipeline.call(RepositoryAdminApi.subscriptionRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.watchStatus)

  /** Starts watching the repository — `PUT /repos/{owner}/{repo}/subscription`.
    *
    * '''Retried.''' This meets the bar: the request names the repository in its path and the account in its token, sets
    * that one subscription to "watching", creates nothing, and carries no body that could differ between attempts. The
    * end state after N attempts is the state after one, and unlike a delete there is no `404`-after-success cost —
    * repeating it answers `200` with the same [[WatchStatus]].
    *
    * '''Failures.''' The group contract above.
    */
  def watch(owner: Owner, name: RepoName): Future[WatchStatus] =
    pipeline.call(RepositoryAdminApi.watchRequest(owner, name), RetryEligibility.AlwaysRetry)(using
      RepositoryAdminDecoders.watchStatus)

  /** Stops watching the repository — `DELETE /repos/{owner}/{repo}/subscription`.
    *
    * '''Retried''', for the reason [[watch]] gives and with one difference: what is being removed is the subscription
    * of the account holding the token, which is not a name anybody else can take. A repeat therefore cannot reach a
    * different subject the way [[deleteBranch]] can, and Forgejo answers `204` whether or not there was a subscription
    * to remove — so there is not even a `404`-after-success cost.
    *
    * '''Answers `204`''', with no body.
    *
    * '''Failures.''' The group contract above.
    */
  def unwatch(owner: Owner, name: RepoName): Future[Unit] =
    pipeline.callUnit(RepositoryAdminApi.unwatchRequest(owner, name), RetryEligibility.AlwaysRetry)

  // --- people ---------------------------------------------------------------

  /** Lists the accounts that may be assigned to an issue — `GET /repos/{owner}/{repo}/assignees`.
    *
    * Everyone with write access. '''Not paged''': the endpoint declares no `page` or `limit`, and answers the whole
    * set, which is why this returns a `Vector` and not a [[com.worxbend.codeberg4s.paging.Page]].
    *
    * '''Failures.''' The group contract above.
    */
  def assignees(owner: Owner, name: RepoName): Future[Vector[User]] =
    pipeline.call(RepositoryAdminApi.assigneesRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.users)

  /** Lists the accounts that may be asked to review a pull request — `GET /repos/{owner}/{repo}/reviewers`.
    *
    * '''Not the same set as [[assignees]]''', despite both being "people with access": Forgejo computes reviewers from
    * the branch protection rules as well as from collaboration, so the two lists can differ on the same repository.
    * Also not paged.
    *
    * '''Failures.''' The group contract above.
    */
  def reviewers(owner: Owner, name: RepoName): Future[Vector[User]] =
    pipeline.call(RepositoryAdminApi.reviewersRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.users)

  /** Lists the accounts that starred the repository — `GET /repos/{owner}/{repo}/stargazers`.
    *
    * '''Paging.''' As [[pushMirrors]]: the `Link` header decides, not the number of items.
    *
    * '''Failures.''' The group contract above.
    */
  def stargazers(owner: Owner, name: RepoName, params: PageParams): Future[Page[User]] =
    pipeline.callPage(RepositoryAdminApi.stargazersRequest(owner, name, params), params)(using
      RepositoryAdminDecoders.users)

  /** Lists the accounts that watch the repository — `GET /repos/{owner}/{repo}/subscribers`.
    *
    * The other side of [[subscription]]: this is everyone watching, that is whether one particular account does.
    *
    * '''Paging.''' As [[pushMirrors]].
    *
    * '''Failures.''' The group contract above.
    */
  def subscribers(owner: Owner, name: RepoName, params: PageParams): Future[Page[User]] =
    pipeline.callPage(RepositoryAdminApi.subscribersRequest(owner, name, params), params)(using
      RepositoryAdminDecoders.users)

  // --- branches -------------------------------------------------------------

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

  // --- contents -------------------------------------------------------------

  /** Lists the entries of the repository's root directory — `GET /repos/{owner}/{repo}/contents`.
    *
    * The root-only sibling of `RepositoryApi.contents`, which takes a path. '''Not paged''': the endpoint declares no
    * `page` or `limit` and answers the whole directory.
    *
    * '''A file listed here carries no content.''' `docs/HAZARDS.md` §3 measured it: a directory listing sends
    * `content: null` for its files, and only a request for a file's own path carries the bytes. See
    * [[com.worxbend.codeberg4s.repositories.ContentEntry.File]].
    *
    * '''Failures.''' The group contract above. `404` is also what a repository with no commits answers, since there is
    * no tree to list.
    *
    * @param ref
    *   the branch, tag or commit to read at; absent means the repository's default branch
    */
  def contents(owner: Owner, name: RepoName, ref: Option[RefName]): Future[Vector[ContentEntry]] =
    pipeline.call(RepositoryAdminApi.contentsRequest(owner, name, ref), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.contents)

  /** Adds a file — `POST /repos/{owner}/{repo}/contents/{filepath}`.
    *
    * '''Never retried''', as every `POST` here is. A repeat after a lost success answers `422`, because the file now
    * exists — which would report a failure for a commit that landed.
    *
    * '''Answers `201`''' and the file as committed, together with the commit that wrote it.
    *
    * '''Failures.''' The group contract above. `422` covers a path that already exists; `404` a branch that does not;
    * `423` an archived repository; `409` a conflicting concurrent write.
    *
    * @param path
    *   where the file will live, relative to the repository root
    */
  def createFile(owner: Owner, name: RepoName, path: ContentPath, command: CreateFile): Future[FileChange] =
    pipeline.call(RepositoryAdminApi.createFileRequest(owner, name, path, command), RetryEligibility.Never)(using
      RepositoryAdminDecoders.fileChange)

  /** Replaces a file — `PUT /repos/{owner}/{repo}/contents/{filepath}`.
    *
    * '''The command names the blob it expects to replace''', and Forgejo refuses the write with `409` when the file has
    * moved on. That is not optional and it is the reason this call cannot silently discard somebody else's commit; see
    * [[UpdateFile]].
    *
    * '''Never retried''', despite `PUT` being the method a retry is usually safe on. After a lost success the file's
    * blob id has already changed, so the retry cannot succeed — it answers `409`, reporting a conflict for a write that
    * in fact landed. The guard makes the repeat harmless and useless at once, which is the case the retry rules
    * exclude.
    *
    * '''Answers `200`''' and the file as committed.
    *
    * '''Failures.''' The group contract above. `409` is the concurrent-edit case above; `404` a path or branch that
    * does not exist; `423` an archived repository.
    */
  def updateFile(owner: Owner, name: RepoName, path: ContentPath, command: UpdateFile): Future[FileChange] =
    pipeline.call(RepositoryAdminApi.updateFileRequest(owner, name, path, command), RetryEligibility.Never)(using
      RepositoryAdminDecoders.fileChange)

  /** Removes a file — `DELETE /repos/{owner}/{repo}/contents/{filepath}`.
    *
    * '''This `DELETE` carries a body''', which is unusual and is what the spec declares: `DeleteFileOptions` is
    * required, and it holds the same sha guard [[updateFile]] carries plus the commit settings.
    *
    * '''Never retried''', for the reason [[updateFile]] gives — the sha guard means a repeat after a lost success
    * answers `400`, reporting a failure for a commit that landed. Note that this is the one delete in the group that
    * '''creates''' something: a commit. That alone rules out
    * [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]].
    *
    * '''Answers `200`''' and the commit that removed the file. The response's `content` is always `null`, which
    * [[com.worxbend.codeberg4s.repositories.gitdata.FileChange.content]] reports as absent.
    *
    * '''Failures.''' The group contract above. `400` is what a stale sha produces here, where the update answers `409`.
    */
  def deleteFile(owner: Owner, name: RepoName, path: ContentPath, command: DeleteFile): Future[FileChange] =
    pipeline.call(RepositoryAdminApi.deleteFileRequest(owner, name, path, command), RetryEligibility.Never)(using
      RepositoryAdminDecoders.fileChange)

  /** Writes several files in one commit — `POST /repos/{owner}/{repo}/contents`.
    *
    * '''The only atomic multi-file write.''' Four separate single-file calls produce four commits and four chances to
    * leave the repository half-changed; this produces one commit that either lands whole or does not land. See
    * [[ChangeFiles]].
    *
    * '''Never retried''', as every `POST` here is, and with an extra reason: the batch's update and delete operations
    * carry sha guards, so a repeat after a lost success fails on the first guard it reaches — reporting a conflict for
    * a commit that landed.
    *
    * '''Answers `201`''' and every entry the commit touched. A batch of deletes legitimately answers with an empty
    * [[FileChangeSet.files]].
    *
    * '''Failures.''' The group contract above. `409` is the concurrent-edit case; `422` a batch Forgejo rejected, for
    * instance a create for a path that exists.
    */
  def changeFiles(owner: Owner, name: RepoName, command: ChangeFiles): Future[FileChangeSet] =
    pipeline.call(RepositoryAdminApi.changeFilesRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryAdminDecoders.fileChangeSet)

  // --- avatar ---------------------------------------------------------------

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

  // --- reporting ------------------------------------------------------------

  /** Lists the repository's activity feed — `GET /repos/{owner}/{repo}/activities/feeds`.
    *
    * '''Paging.''' As [[pushMirrors]]: the `Link` header decides.
    *
    * '''Failures.''' The group contract above.
    *
    * @param date
    *   restrict the feed to one calendar day. A `java.time.LocalDate` rather than an instant because the spec declares
    *   the parameter `format: date` — see [[com.worxbend.codeberg4s.repositories.admin.wire.AdminQueries.activities]]
    *   for why a time zone must not be chosen on the caller's behalf
    */
  def activityFeed(
      owner: Owner,
      name: RepoName,
      date: Option[LocalDate],
      params: PageParams,
  ): Future[Page[RepositoryActivity]] =
    pipeline.callPage(RepositoryAdminApi.activityFeedRequest(owner, name, date, params), params)(using
      RepositoryAdminDecoders.activities)

  /** Reads how many bytes of each language the repository holds — `GET /repos/{owner}/{repo}/languages`.
    *
    * '''The body is a bare object with no fixed keys''', which is why it has a model of its own; see
    * [[LanguageBreakdown]]. A repository whose analysis has not run answers `{}`, which arrives as
    * [[LanguageBreakdown.Empty]] and is a success.
    *
    * '''Failures.''' The group contract above.
    */
  def languages(owner: Owner, name: RepoName): Future[LanguageBreakdown] =
    pipeline.call(RepositoryAdminApi.languagesRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.languages)

  /** Reads whether another issue or pull request may be pinned — `GET /repos/{owner}/{repo}/new_pin_allowed`.
    *
    * The check to make before attempting a pin, since the cap is an instance setting the caller cannot read otherwise.
    *
    * '''Failures.''' The group contract above.
    */
  def newPinAllowed(owner: Owner, name: RepoName): Future[IssuePinsAllowed] =
    pipeline.call(RepositoryAdminApi.newPinAllowedRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.issuePinsAllowed)

  /** Lists the repository's pinned issues — `GET /repos/{owner}/{repo}/issues/pinned`.
    *
    * '''Not paged''': the endpoint answers the whole set, which is small by construction because Forgejo caps it. The
    * order is the pin order the repository's maintainers chose, not the issue order.
    *
    * '''Failures.''' The group contract above.
    */
  def pinnedIssues(owner: Owner, name: RepoName): Future[Vector[Issue]] =
    pipeline.call(RepositoryAdminApi.pinnedIssuesRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.issues)

  /** Reads the key the repository's commits are signed with — `GET /repos/{owner}/{repo}/signing-key.gpg`.
    *
    * '''Not JSON.''' The response is an armored OpenPGP block under `text/plain`, so nothing parses it — see
    * [[com.worxbend.codeberg4s.miscellaneous.PlainText]]. A repository that signs nothing answers `200` with an empty
    * body, which is `None` here and a success, not a failure.
    *
    * '''Failures.''' The group contract above, minus [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]], which
    * this method cannot produce because nothing is parsed.
    */
  def signingKey(owner: Owner, name: RepoName): Future[Option[SigningKey]] =
    pipeline.call(RepositoryAdminApi.signingKeyRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.signingKey)

  /** Lists the time recorded against the repository's issues — `GET /repos/{owner}/{repo}/times`.
    *
    * '''Time tracking has to be on.''' A repository whose `internal_tracker.enable_time_tracker` is `false` answers
    * `404` here, which is indistinguishable from a missing repository; [[EditRepository.trackingIssuesWith]] is what
    * turns it on.
    *
    * '''Paging.''' As [[pushMirrors]].
    *
    * '''Failures.''' The group contract above. `403` is what filtering by another account produces when the caller is
    * not an issue manager — see [[com.worxbend.codeberg4s.issues.TrackedTimeQuery]].
    *
    * @param query
    *   the filters to apply; [[com.worxbend.codeberg4s.issues.TrackedTimeQuery.Empty]] asks for every entry
    */
  def trackedTimes(
      owner: Owner,
      name: RepoName,
      query: TrackedTimeQuery,
      params: PageParams,
  ): Future[Page[TrackedTime]] =
    pipeline.callPage(RepositoryAdminApi.trackedTimesRequest(owner, name, query, params), params)(using
      RepositoryAdminDecoders.trackedTimes)

  /** Lists one account's tracked time in the repository — `GET /repos/{owner}/{repo}/times/{user}`.
    *
    * '''Not paged''', unlike [[trackedTimes]] — the spec's own name for the response is
    * `TrackedTimeListWithoutPagination`, and it sends no paging headers. This is a genuine difference between two
    * endpoints that otherwise answer the same model, not an oversight here.
    *
    * '''Failures.''' The group contract above.
    */
  def trackedTimesFor(owner: Owner, name: RepoName, user: Username): Future[Vector[TrackedTime]] =
    pipeline.call(RepositoryAdminApi.trackedTimesForRequest(owner, name, user), RetryEligibility.IdempotentOnly)(using
      RepositoryAdminDecoders.trackedTimes)

  /** Searches the instance's topics — `GET /topics/search`.
    *
    * Instance-wide, not repository-scoped: this is how a caller discovers what topics exist before setting one with
    * `RepositoryPublishingApi`'s topic endpoints.
    *
    * '''The body is a `{"topics": [...]}` envelope''', the third envelope shape in this library; see
    * [[com.worxbend.codeberg4s.repositories.admin.wire.TopicSearchEnvelopeDto]].
    *
    * '''Paging.''' As [[pushMirrors]].
    *
    * '''Failures.''' The group contract above.
    *
    * @param keyword
    *   the search term, which the spec marks required
    */
  def searchTopics(keyword: String, params: PageParams): Future[Page[TopicSummary]] =
    pipeline.callPage(RepositoryAdminApi.searchTopicsRequest(keyword, params), params)(using
      RepositoryAdminDecoders.topics)

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

  /** The stable operation id of [[RepositoryAdminApi.syncMirror]]. */
  val SyncMirrorOperation: String = "repos.admin.mirror.sync"

  /** The stable operation id of [[RepositoryAdminApi.pushMirrors]]. */
  val ListPushMirrorsOperation: String = "repos.admin.pushMirrors.list"

  /** The stable operation id of the single push-mirror read on [[RepositoryAdminApi]]. */
  val GetPushMirrorOperation: String = "repos.admin.pushMirrors.get"

  /** The stable operation id of [[RepositoryAdminApi.addPushMirror]]. */
  val AddPushMirrorOperation: String = "repos.admin.pushMirrors.add"

  /** The stable operation id of [[RepositoryAdminApi.deletePushMirror]]. */
  val DeletePushMirrorOperation: String = "repos.admin.pushMirrors.delete"

  /** The stable operation id of [[RepositoryAdminApi.syncPushMirrors]]. */
  val SyncPushMirrorsOperation: String = "repos.admin.pushMirrors.sync"

  /** The stable operation id of [[RepositoryAdminApi.forkSyncInfo]]. */
  val ForkSyncInfoOperation: String = "repos.admin.syncFork.defaultInfo"

  /** The stable operation id of [[RepositoryAdminApi.branchForkSyncInfo]]. */
  val BranchForkSyncInfoOperation: String = "repos.admin.syncFork.branchInfo"

  /** The stable operation id of [[RepositoryAdminApi.syncFork]]. */
  val SyncForkOperation: String = "repos.admin.syncFork.default"

  /** The stable operation id of [[RepositoryAdminApi.syncForkBranch]]. */
  val SyncForkBranchOperation: String = "repos.admin.syncFork.branch"

  /** The stable operation id of [[RepositoryAdminApi.subscription]]. */
  val GetSubscriptionOperation: String = "repos.admin.subscription.get"

  /** The stable operation id of [[RepositoryAdminApi.watch]]. */
  val WatchOperation: String = "repos.admin.subscription.watch"

  /** The stable operation id of [[RepositoryAdminApi.unwatch]]. */
  val UnwatchOperation: String = "repos.admin.subscription.unwatch"

  /** The stable operation id of [[RepositoryAdminApi.assignees]]. */
  val ListAssigneesOperation: String = "repos.admin.assignees.list"

  /** The stable operation id of [[RepositoryAdminApi.reviewers]]. */
  val ListReviewersOperation: String = "repos.admin.reviewers.list"

  /** The stable operation id of [[RepositoryAdminApi.stargazers]]. */
  val ListStargazersOperation: String = "repos.admin.stargazers.list"

  /** The stable operation id of [[RepositoryAdminApi.subscribers]]. */
  val ListSubscribersOperation: String = "repos.admin.subscribers.list"

  /** The stable operation id of [[RepositoryAdminApi.createBranch]]. */
  val CreateBranchOperation: String = "repos.admin.branches.create"

  /** The stable operation id of [[RepositoryAdminApi.deleteBranch]]. */
  val DeleteBranchOperation: String = "repos.admin.branches.delete"

  /** The stable operation id of [[RepositoryAdminApi.renameBranch]]. */
  val RenameBranchOperation: String = "repos.admin.branches.rename"

  /** The stable operation id of [[RepositoryAdminApi.contents]]. */
  val ListContentsOperation: String = "repos.admin.contents.list"

  /** The stable operation id of [[RepositoryAdminApi.createFile]]. */
  val CreateFileOperation: String = "repos.admin.contents.create"

  /** The stable operation id of [[RepositoryAdminApi.updateFile]]. */
  val UpdateFileOperation: String = "repos.admin.contents.update"

  /** The stable operation id of [[RepositoryAdminApi.deleteFile]]. */
  val DeleteFileOperation: String = "repos.admin.contents.delete"

  /** The stable operation id of [[RepositoryAdminApi.changeFiles]]. */
  val ChangeFilesOperation: String = "repos.admin.contents.change"

  /** The stable operation id of [[RepositoryAdminApi.updateAvatar]]. */
  val UpdateAvatarOperation: String = "repos.admin.avatar.update"

  /** The stable operation id of [[RepositoryAdminApi.deleteAvatar]]. */
  val DeleteAvatarOperation: String = "repos.admin.avatar.delete"

  /** The stable operation id of [[RepositoryAdminApi.activityFeed]]. */
  val ListActivityFeedOperation: String = "repos.admin.activities.list"

  /** The stable operation id of [[RepositoryAdminApi.languages]]. */
  val GetLanguagesOperation: String = "repos.admin.languages.get"

  /** The stable operation id of [[RepositoryAdminApi.newPinAllowed]]. */
  val NewPinAllowedOperation: String = "repos.admin.pins.allowed"

  /** The stable operation id of [[RepositoryAdminApi.pinnedIssues]]. */
  val ListPinnedIssuesOperation: String = "repos.admin.pins.issues"

  /** The stable operation id of [[RepositoryAdminApi.signingKey]]. */
  val SigningKeyOperation: String = "repos.admin.signingKey"

  /** The stable operation id of [[RepositoryAdminApi.trackedTimes]]. */
  val ListTrackedTimesOperation: String = "repos.admin.times.list"

  /** The stable operation id of [[RepositoryAdminApi.trackedTimesFor]]. */
  val UserTrackedTimesOperation: String = "repos.admin.times.user"

  /** The stable operation id of [[RepositoryAdminApi.searchTopics]]. */
  val SearchTopicsOperation: String = "repos.admin.topics.search"

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

    /** [[RepositoryAdminApi.syncMirror]] with its failure as a value. */
    def syncMirror(owner: Owner, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.syncMirror(owner, name))

    /** [[RepositoryAdminApi.pushMirrors]] with its failure as a value. */
    def pushMirrors(owner: Owner, name: RepoName, params: PageParams): Future[Either[CodebergError, Page[PushMirror]]] =
      exec.attempt(rail.pushMirrors(owner, name, params))

    /** The single push-mirror read on [[RepositoryAdminApi]], with its failure as a value. */
    def pushMirror(owner: Owner, name: RepoName, mirror: MirrorName): Future[Either[CodebergError, PushMirror]] =
      exec.attempt(rail.pushMirror(owner, name, mirror))

    /** [[RepositoryAdminApi.addPushMirror]] with its failure as a value. */
    def addPushMirror(
        owner: Owner,
        name: RepoName,
        command: CreatePushMirror,
    ): Future[Either[CodebergError, PushMirror]] =
      exec.attempt(rail.addPushMirror(owner, name, command))

    /** [[RepositoryAdminApi.deletePushMirror]] with its failure as a value. */
    def deletePushMirror(owner: Owner, name: RepoName, mirror: MirrorName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deletePushMirror(owner, name, mirror))

    /** [[RepositoryAdminApi.syncPushMirrors]] with its failure as a value. */
    def syncPushMirrors(owner: Owner, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.syncPushMirrors(owner, name))

    /** [[RepositoryAdminApi.forkSyncInfo]] with its failure as a value. */
    def forkSyncInfo(owner: Owner, name: RepoName): Future[Either[CodebergError, ForkSyncInfo]] =
      exec.attempt(rail.forkSyncInfo(owner, name))

    /** [[RepositoryAdminApi.branchForkSyncInfo]] with its failure as a value. */
    def branchForkSyncInfo(
        owner: Owner,
        name: RepoName,
        branch: BranchName,
    ): Future[Either[CodebergError, ForkSyncInfo]] =
      exec.attempt(rail.branchForkSyncInfo(owner, name, branch))

    /** [[RepositoryAdminApi.syncFork]] with its failure as a value. */
    def syncFork(owner: Owner, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.syncFork(owner, name))

    /** [[RepositoryAdminApi.syncForkBranch]] with its failure as a value. */
    def syncForkBranch(owner: Owner, name: RepoName, branch: BranchName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.syncForkBranch(owner, name, branch))

    /** [[RepositoryAdminApi.subscription]] with its failure as a value. */
    def subscription(owner: Owner, name: RepoName): Future[Either[CodebergError, WatchStatus]] =
      exec.attempt(rail.subscription(owner, name))

    /** [[RepositoryAdminApi.watch]] with its failure as a value. */
    def watch(owner: Owner, name: RepoName): Future[Either[CodebergError, WatchStatus]] =
      exec.attempt(rail.watch(owner, name))

    /** [[RepositoryAdminApi.unwatch]] with its failure as a value. */
    def unwatch(owner: Owner, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.unwatch(owner, name))

    /** [[RepositoryAdminApi.assignees]] with its failure as a value. */
    def assignees(owner: Owner, name: RepoName): Future[Either[CodebergError, Vector[User]]] =
      exec.attempt(rail.assignees(owner, name))

    /** [[RepositoryAdminApi.reviewers]] with its failure as a value. */
    def reviewers(owner: Owner, name: RepoName): Future[Either[CodebergError, Vector[User]]] =
      exec.attempt(rail.reviewers(owner, name))

    /** [[RepositoryAdminApi.stargazers]] with its failure as a value. */
    def stargazers(owner: Owner, name: RepoName, params: PageParams): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.stargazers(owner, name, params))

    /** [[RepositoryAdminApi.subscribers]] with its failure as a value. */
    def subscribers(owner: Owner, name: RepoName, params: PageParams): Future[Either[CodebergError, Page[User]]] =
      exec.attempt(rail.subscribers(owner, name, params))

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

    /** [[RepositoryAdminApi.contents]] with its failure as a value. */
    def contents(
        owner: Owner,
        name: RepoName,
        ref: Option[RefName],
    ): Future[Either[CodebergError, Vector[ContentEntry]]] =
      exec.attempt(rail.contents(owner, name, ref))

    /** [[RepositoryAdminApi.createFile]] with its failure as a value. */
    def createFile(
        owner: Owner,
        name: RepoName,
        path: ContentPath,
        command: CreateFile,
    ): Future[Either[CodebergError, FileChange]] =
      exec.attempt(rail.createFile(owner, name, path, command))

    /** [[RepositoryAdminApi.updateFile]] with its failure as a value. */
    def updateFile(
        owner: Owner,
        name: RepoName,
        path: ContentPath,
        command: UpdateFile,
    ): Future[Either[CodebergError, FileChange]] =
      exec.attempt(rail.updateFile(owner, name, path, command))

    /** [[RepositoryAdminApi.deleteFile]] with its failure as a value. */
    def deleteFile(
        owner: Owner,
        name: RepoName,
        path: ContentPath,
        command: DeleteFile,
    ): Future[Either[CodebergError, FileChange]] =
      exec.attempt(rail.deleteFile(owner, name, path, command))

    /** [[RepositoryAdminApi.changeFiles]] with its failure as a value. */
    def changeFiles(
        owner: Owner,
        name: RepoName,
        command: ChangeFiles,
    ): Future[Either[CodebergError, FileChangeSet]] =
      exec.attempt(rail.changeFiles(owner, name, command))

    /** [[RepositoryAdminApi.updateAvatar]] with its failure as a value. */
    def updateAvatar(owner: Owner, name: RepoName, image: AvatarImage): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.updateAvatar(owner, name, image))

    /** [[RepositoryAdminApi.deleteAvatar]] with its failure as a value. */
    def deleteAvatar(owner: Owner, name: RepoName): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteAvatar(owner, name))

    /** [[RepositoryAdminApi.activityFeed]] with its failure as a value. */
    def activityFeed(
        owner: Owner,
        name: RepoName,
        date: Option[LocalDate],
        params: PageParams,
    ): Future[Either[CodebergError, Page[RepositoryActivity]]] =
      exec.attempt(rail.activityFeed(owner, name, date, params))

    /** [[RepositoryAdminApi.languages]] with its failure as a value. */
    def languages(owner: Owner, name: RepoName): Future[Either[CodebergError, LanguageBreakdown]] =
      exec.attempt(rail.languages(owner, name))

    /** [[RepositoryAdminApi.newPinAllowed]] with its failure as a value. */
    def newPinAllowed(owner: Owner, name: RepoName): Future[Either[CodebergError, IssuePinsAllowed]] =
      exec.attempt(rail.newPinAllowed(owner, name))

    /** [[RepositoryAdminApi.pinnedIssues]] with its failure as a value. */
    def pinnedIssues(owner: Owner, name: RepoName): Future[Either[CodebergError, Vector[Issue]]] =
      exec.attempt(rail.pinnedIssues(owner, name))

    /** [[RepositoryAdminApi.signingKey]] with its failure as a value. */
    def signingKey(owner: Owner, name: RepoName): Future[Either[CodebergError, Option[SigningKey]]] =
      exec.attempt(rail.signingKey(owner, name))

    /** [[RepositoryAdminApi.trackedTimes]] with its failure as a value. */
    def trackedTimes(
        owner: Owner,
        name: RepoName,
        query: TrackedTimeQuery,
        params: PageParams,
    ): Future[Either[CodebergError, Page[TrackedTime]]] =
      exec.attempt(rail.trackedTimes(owner, name, query, params))

    /** [[RepositoryAdminApi.trackedTimesFor]] with its failure as a value. */
    def trackedTimesFor(
        owner: Owner,
        name: RepoName,
        user: Username,
    ): Future[Either[CodebergError, Vector[TrackedTime]]] =
      exec.attempt(rail.trackedTimesFor(owner, name, user))

    /** [[RepositoryAdminApi.searchTopics]] with its failure as a value. */
    def searchTopics(keyword: String, params: PageParams): Future[Either[CodebergError, Page[TopicSummary]]] =
      exec.attempt(rail.searchTopics(keyword, params))

  private def createRequest(command: CreateRepository): CodebergRequest =
    write(CreateOperation, HttpMethod.Post, List("user", "repos"), RepositoryOptionDto.renderCreate(command))

  private def byIdRequest(id: RepositoryId): CodebergRequest =
    read(GetByIdOperation, List("repositories", id.value.toString), Nil)

  private def editRequest(owner: Owner, name: RepoName, command: EditRepository): CodebergRequest =
    write(EditOperation, HttpMethod.Patch, repoPath(owner, name), RepositoryOptionDto.renderEdit(command))

  private def deleteRequest(owner: Owner, name: RepoName): CodebergRequest =
    remove(DeleteOperation, repoPath(owner, name), None)

  private def migrateRequest(command: MigrateRepository): CodebergRequest =
    write(MigrateOperation, HttpMethod.Post, List("repos", "migrate"), MigrateRepoOptionsDto.render(command))

  private def transferRequest(owner: Owner, name: RepoName, command: TransferRepository): CodebergRequest =
    write(
      TransferOperation,
      HttpMethod.Post,
      repoPath(owner, name) :+ "transfer",
      TransferRepoOptionDto.render(command),
    )

  private def acceptTransferRequest(owner: Owner, name: RepoName): CodebergRequest =
    post(AcceptTransferOperation, repoPath(owner, name) ++ List("transfer", "accept"))

  private def rejectTransferRequest(owner: Owner, name: RepoName): CodebergRequest =
    post(RejectTransferOperation, repoPath(owner, name) ++ List("transfer", "reject"))

  private def convertRequest(owner: Owner, name: RepoName): CodebergRequest =
    post(ConvertOperation, repoPath(owner, name) :+ "convert")

  private def syncMirrorRequest(owner: Owner, name: RepoName): CodebergRequest =
    post(SyncMirrorOperation, repoPath(owner, name) :+ "mirror-sync")

  private def pushMirrorsRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(ListPushMirrorsOperation, pushMirrorsPath(owner, name), AdminQueries.paging(params))

  private def pushMirrorRequest(owner: Owner, name: RepoName, mirror: MirrorName): CodebergRequest =
    read(GetPushMirrorOperation, pushMirrorsPath(owner, name) :+ mirror.value, Nil)

  private def addPushMirrorRequest(owner: Owner, name: RepoName, command: CreatePushMirror): CodebergRequest =
    write(AddPushMirrorOperation, HttpMethod.Post, pushMirrorsPath(owner, name), PushMirrorOptionDto.render(command))

  private def deletePushMirrorRequest(owner: Owner, name: RepoName, mirror: MirrorName): CodebergRequest =
    remove(DeletePushMirrorOperation, pushMirrorsPath(owner, name) :+ mirror.value, None)

  private def syncPushMirrorsRequest(owner: Owner, name: RepoName): CodebergRequest =
    post(SyncPushMirrorsOperation, repoPath(owner, name) :+ "push_mirrors-sync")

  private def forkSyncInfoRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ForkSyncInfoOperation, syncForkPath(owner, name), Nil)

  private def branchForkSyncInfoRequest(owner: Owner, name: RepoName, branch: BranchName): CodebergRequest =
    read(BranchForkSyncInfoOperation, syncForkPath(owner, name) ++ branch.segments, Nil)

  private def syncForkRequest(owner: Owner, name: RepoName): CodebergRequest =
    post(SyncForkOperation, syncForkPath(owner, name))

  private def syncForkBranchRequest(owner: Owner, name: RepoName, branch: BranchName): CodebergRequest =
    post(SyncForkBranchOperation, syncForkPath(owner, name) ++ branch.segments)

  private def subscriptionRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(GetSubscriptionOperation, subscriptionPath(owner, name), Nil)

  /** The watch `PUT` carries no body. Forgejo declares none, and sending `{}` would be a body the endpoint never
    * defined.
    */
  private def watchRequest(owner: Owner, name: RepoName): CodebergRequest =
    CodebergRequest(
      operation = WatchOperation,
      method    = HttpMethod.Put,
      path      = subscriptionPath(owner, name),
      query     = Nil,
      headers   = Nil,
      body      = None,
    )

  private def unwatchRequest(owner: Owner, name: RepoName): CodebergRequest =
    remove(UnwatchOperation, subscriptionPath(owner, name), None)

  private def assigneesRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ListAssigneesOperation, repoPath(owner, name) :+ "assignees", Nil)

  private def reviewersRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ListReviewersOperation, repoPath(owner, name) :+ "reviewers", Nil)

  private def stargazersRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(ListStargazersOperation, repoPath(owner, name) :+ "stargazers", AdminQueries.paging(params))

  private def subscribersRequest(owner: Owner, name: RepoName, params: PageParams): CodebergRequest =
    read(ListSubscribersOperation, repoPath(owner, name) :+ "subscribers", AdminQueries.paging(params))

  private def createBranchRequest(owner: Owner, name: RepoName, command: CreateBranch): CodebergRequest =
    write(CreateBranchOperation, HttpMethod.Post, branchesPath(owner, name), BranchOptionDto.renderCreate(command))

  private def deleteBranchRequest(owner: Owner, name: RepoName, branch: BranchName): CodebergRequest =
    remove(DeleteBranchOperation, branchesPath(owner, name) ++ branch.segments, None)

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

  private def contentsRequest(owner: Owner, name: RepoName, ref: Option[RefName]): CodebergRequest =
    read(ListContentsOperation, contentsPath(owner, name), AdminQueries.contents(ref))

  private def createFileRequest(
      owner: Owner,
      name: RepoName,
      path: ContentPath,
      command: CreateFile,
  ): CodebergRequest =
    write(
      CreateFileOperation,
      HttpMethod.Post,
      contentsPath(owner, name) ++ path.segments,
      FileOptionsDto.renderCreate(command),
    )

  private def updateFileRequest(
      owner: Owner,
      name: RepoName,
      path: ContentPath,
      command: UpdateFile,
  ): CodebergRequest =
    write(
      UpdateFileOperation,
      HttpMethod.Put,
      contentsPath(owner, name) ++ path.segments,
      FileOptionsDto.renderUpdate(command),
    )

  /** The one `DELETE` in the library that carries a body; the spec declares `DeleteFileOptions` required. */
  private def deleteFileRequest(
      owner: Owner,
      name: RepoName,
      path: ContentPath,
      command: DeleteFile,
  ): CodebergRequest =
    remove(
      DeleteFileOperation,
      contentsPath(owner, name) ++ path.segments,
      Some(RequestBody.Json(FileOptionsDto.renderDelete(command))),
    )

  private def changeFilesRequest(owner: Owner, name: RepoName, command: ChangeFiles): CodebergRequest =
    write(ChangeFilesOperation, HttpMethod.Post, contentsPath(owner, name), FileOptionsDto.renderChange(command))

  private def updateAvatarRequest(owner: Owner, name: RepoName, image: AvatarImage): CodebergRequest =
    write(UpdateAvatarOperation, HttpMethod.Post, avatarPath(owner, name), AvatarOptionDto.render(image))

  private def deleteAvatarRequest(owner: Owner, name: RepoName): CodebergRequest =
    remove(DeleteAvatarOperation, avatarPath(owner, name), None)

  private def activityFeedRequest(
      owner: Owner,
      name: RepoName,
      date: Option[LocalDate],
      params: PageParams,
  ): CodebergRequest =
    read(
      ListActivityFeedOperation,
      repoPath(owner, name) ++ List("activities", "feeds"),
      AdminQueries.activities(date) ++ AdminQueries.paging(params),
    )

  private def languagesRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(GetLanguagesOperation, repoPath(owner, name) :+ "languages", Nil)

  private def newPinAllowedRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(NewPinAllowedOperation, repoPath(owner, name) :+ "new_pin_allowed", Nil)

  private def pinnedIssuesRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ListPinnedIssuesOperation, repoPath(owner, name) ++ List("issues", "pinned"), Nil)

  private def signingKeyRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(SigningKeyOperation, repoPath(owner, name) :+ "signing-key.gpg", Nil)

  private def trackedTimesRequest(
      owner: Owner,
      name: RepoName,
      query: TrackedTimeQuery,
      params: PageParams,
  ): CodebergRequest =
    read(
      ListTrackedTimesOperation,
      timesPath(owner, name),
      IssueQueries.trackedTimes(query) ++ AdminQueries.paging(params),
    )

  private def trackedTimesForRequest(owner: Owner, name: RepoName, user: Username): CodebergRequest =
    read(UserTrackedTimesOperation, timesPath(owner, name) :+ user.value, Nil)

  private def searchTopicsRequest(keyword: String, params: PageParams): CodebergRequest =
    read(
      SearchTopicsOperation,
      List("topics", "search"),
      AdminQueries.topicSearch(keyword) ++ AdminQueries.paging(params),
    )

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

  /** A `POST` that Forgejo declares no request model for — accept, reject, convert and the four sync calls. */
  private def post(operation: String, path: List[String]): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Post,
      path      = path,
      query     = Nil,
      headers   = Nil,
      body      = None,
    )

  private def remove(operation: String, path: List[String], body: Option[RequestBody]): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Delete,
      path      = path,
      query     = Nil,
      headers   = Nil,
      body      = body,
    )

  private def repoPath(owner: Owner, name: RepoName): List[String] =
    List("repos", owner.value, name.value)

  private def pushMirrorsPath(owner: Owner, name: RepoName): List[String] =
    repoPath(owner, name) :+ "push_mirrors"

  private def syncForkPath(owner: Owner, name: RepoName): List[String] =
    repoPath(owner, name) :+ "sync_fork"

  private def subscriptionPath(owner: Owner, name: RepoName): List[String] =
    repoPath(owner, name) :+ "subscription"

  private def branchesPath(owner: Owner, name: RepoName): List[String] =
    repoPath(owner, name) :+ "branches"

  private def contentsPath(owner: Owner, name: RepoName): List[String] =
    repoPath(owner, name) :+ "contents"

  private def avatarPath(owner: Owner, name: RepoName): List[String] =
    repoPath(owner, name) :+ "avatar"

  private def timesPath(owner: Owner, name: RepoName): List[String] =
    repoPath(owner, name) :+ "times"
