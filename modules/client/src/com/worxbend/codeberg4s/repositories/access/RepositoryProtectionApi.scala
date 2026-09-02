package com.worxbend.codeberg4s.repositories.access

import com.worxbend.codeberg4s.core.CodebergRequest.{read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.repositories.access.wire.{
  CreateBranchProtectionOptionDto,
  EditBranchProtectionOptionDto,
  TagProtectionOptionDto
}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName, RepositoryRequests}

import scala.concurrent.Future

/** The rules that stop a push: a repository's branch protections and its tag protections.
  *
  * Reached as `client.repos.access.protections`. It is a group of its own rather than more methods on
  * [[RepositoryAccessApi]] because that class had grown past what a reader can hold in their head, and because the
  * split falls on a real line: [[RepositoryAccessApi]] answers '''who''' may reach the repository, and this answers
  * '''what''' they may do to a ref once they are in. The endpoints, the models and the retry decisions are unchanged
  * by the move.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[RepositoryProtectionApi.attempt]] never
  * fail and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Branch and tag protections are not the same shape==
  *
  * A branch protection is a large record with a dozen independent switches and several allow-lists; a tag protection
  * is a pattern and a list of accounts allowed to move tags matching it. They are together because they are the two
  * halves of one question — which refs are not freely writable — and apart from every other operation on the access
  * surface for the same reason.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository or the protection does not
  *     exist '''or''' is invisible to the credentials in use, `401` when a token was required and none was sent, and
  *     `403` when the token lacks the scope or the account lacks the permission. `422` '''and''' `400` both mean the
  *     request was rejected as invalid.
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
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. The two `POST`s are never retried,
  * because a repeat after a lost success answers `409` for a protection that exists exactly as the caller asked. The
  * edits and the deletes each say what they do on their own method.
  */

final class RepositoryProtectionApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: RepositoryProtectionApi.Attempt = RepositoryProtectionApi.Attempt(this)

  /** Lists a repository's branch protection rules — `GET /repos/{owner}/{repo}/branch_protections`.
    *
    * '''Not paged, and that is the endpoint's decision.''' The spec declares no `page` or `limit` for this operation,
    * so every rule arrives at once and the result is a `Vector` rather than a [[com.worxbend.codeberg4s.paging.Page]] —
    * a page reporting a window nobody chose would be a lie about what was requested. A repository's rule count is
    * bounded by what an administrator typed, so this is not the unbounded read that would make paging necessary.
    *
    * '''This is the only way to see a rule whose name contains a `/`.''' Those cannot be addressed one at a time; see
    * [[BranchRuleName]].
    *
    * '''Failures.''' The group contract above.
    */
  def branchProtections(owner: Owner, name: RepoName): Future[Vector[BranchProtection]] =
    pipeline.call(RepositoryProtectionApi.branchProtectionsRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAccessDecoders.branchProtections)

  /** Reads one branch protection rule — `GET /repos/{owner}/{repo}/branch_protections/{name}`.
    *
    * '''Failures.''' The group contract above. `404` covers both "no such rule in this repository" and "no such
    * repository", and — because the route matches one path segment — is also what a rule whose name contains a `/`
    * would answer if this library let such a name through. It does not; see [[BranchRuleName]].
    *
    * @param rule
    *   the rule's own name, which is a glob and not a branch
    */
  def branchProtection(owner: Owner, name: RepoName, rule: BranchRuleName): Future[BranchProtection] =
    pipeline.call(RepositoryProtectionApi.branchProtectionRequest(owner, name, rule), RetryEligibility.IdempotentOnly)(using
      RepositoryAccessDecoders.branchProtection)

  /** Creates a branch protection rule — `POST /repos/{owner}/{repo}/branch_protections`.
    *
    * '''Never retried''', because it is a `POST` and this library repeats none. A repeat would answer `422` rather than
    * create a second rule — Forgejo rejects a duplicate rule name — but that is the instance's behaviour to change, not
    * a promise this library makes on its behalf. A transport failure therefore leaves the caller genuinely unsure
    * whether the rule exists, which [[branchProtections]] resolves.
    *
    * '''Only what the command states is sent.''' Everything the caller left unset takes Forgejo's own default; see
    * [[BranchProtectionSettings]].
    *
    * '''Failures.''' The group contract above. `423` is what an archived repository answers — a protection rule cannot
    * be added to something that is already frozen.
    */
  def createBranchProtection(
      owner: Owner,
      name: RepoName,
      command: CreateBranchProtection,
  ): Future[BranchProtection] =
    pipeline.call(RepositoryProtectionApi.createBranchProtectionRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryAccessDecoders.branchProtection)

  /** Changes a branch protection rule — `PATCH /repos/{owner}/{repo}/branch_protections/{name}`.
    *
    * '''Never retried.''' A `PATCH` is not safe in the RFC 9110 sense, and this is the endpoint where replaying a
    * request the caller did not intend to replay changes who may push to a branch. Repeating this particular body would
    * in fact be state-identical — it states values rather than deltas — but that is a property of Forgejo's
    * implementation rather than of the method, and the library does not bet a branch on it. A caller who knows their
    * edit is safe to repeat can re-issue it.
    *
    * '''A rule cannot be renamed here.''' `EditBranchProtectionOption` declares neither `rule_name` nor `branch_name`,
    * so the name in the path is the only one there is.
    *
    * '''Anything the command leaves unset is left alone''' — see [[EditBranchProtection]] for why that is the single
    * most important sentence in this group.
    *
    * '''Failures.''' The group contract above, `423` included.
    */
  def editBranchProtection(
      owner: Owner,
      name: RepoName,
      rule: BranchRuleName,
      command: EditBranchProtection,
  ): Future[BranchProtection] =
    pipeline.call(
      RepositoryProtectionApi.editBranchProtectionRequest(owner, name, rule, command),
      RetryEligibility.Never,
    )(using RepositoryAccessDecoders.branchProtection)

  /** Removes a branch protection rule — `DELETE /repos/{owner}/{repo}/branch_protections/{name}`.
    *
    * '''Never retried, unlike every other delete in this library.''' The bar for repeating a delete is that the request
    * names an identifier the instance never reuses, and a rule '''name''' is not one: a rule called `main` can be
    * deleted and another created under the same name a second later. If the first attempt succeeded and its response
    * was lost, a retry would delete whatever now answers to that name — which on this endpoint means silently
    * unprotecting a branch someone had just protected. [[deleteTagProtection]] is addressed by a number and is retried;
    * the difference between the two is exactly that.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def deleteBranchProtection(owner: Owner, name: RepoName, rule: BranchRuleName): Future[Unit] =
    pipeline.callUnit(RepositoryProtectionApi.deleteBranchProtectionRequest(owner, name, rule), RetryEligibility.Never)

  // --- tag protections ------------------------------------------------------

  /** Lists a repository's tag protection rules — `GET /repos/{owner}/{repo}/tag_protections`.
    *
    * '''Not paged''', for the reason [[branchProtections]] gives: the spec declares no `page` or `limit`.
    *
    * '''Failures.''' The group contract above. This operation declares no failure status at all in the spec, which is a
    * gap in the spec rather than a promise — `401` and `404` both occur.
    */
  def tagProtections(owner: Owner, name: RepoName): Future[Vector[TagProtection]] =
    pipeline.call(RepositoryProtectionApi.tagProtectionsRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      RepositoryAccessDecoders.tagProtections)

  /** Reads one tag protection rule — `GET /repos/{owner}/{repo}/tag_protections/{id}`.
    *
    * '''Addressed by a number, not by its pattern''' — unlike a branch rule. See [[TagProtectionId]].
    *
    * '''Failures.''' The group contract above.
    */
  def tagProtection(owner: Owner, name: RepoName, id: TagProtectionId): Future[TagProtection] =
    pipeline.call(RepositoryProtectionApi.tagProtectionRequest(owner, name, id), RetryEligibility.IdempotentOnly)(using
      RepositoryAccessDecoders.tagProtection)

  /** Creates a tag protection rule — `POST /repos/{owner}/{repo}/tag_protections`.
    *
    * '''Never retried''', for the reason [[createBranchProtection]] gives.
    *
    * '''All three properties are sent''', whitelists included, because an omitted whitelist and an empty one mean the
    * same thing on a create; see [[com.worxbend.codeberg4s.repositories.access.wire.TagProtectionOptionDto]].
    *
    * '''Failures.''' The group contract above, `423` included.
    */
  def createTagProtection(owner: Owner, name: RepoName, command: CreateTagProtection): Future[TagProtection] =
    pipeline.call(RepositoryProtectionApi.createTagProtectionRequest(owner, name, command), RetryEligibility.Never)(using
      RepositoryAccessDecoders.tagProtection)

  /** Changes a tag protection rule — `PATCH /repos/{owner}/{repo}/tag_protections/{id}`.
    *
    * '''Never retried''', for the reason [[editBranchProtection]] gives.
    *
    * '''Anything the command leaves unset is left alone''', and an explicitly empty whitelist clears it; see
    * [[EditTagProtection]].
    *
    * '''Failures.''' The group contract above, `423` included.
    */
  def editTagProtection(
      owner: Owner,
      name: RepoName,
      id: TagProtectionId,
      command: EditTagProtection,
  ): Future[TagProtection] =
    pipeline.call(RepositoryProtectionApi.editTagProtectionRequest(owner, name, id, command), RetryEligibility.Never)(using
      RepositoryAccessDecoders.tagProtection)

  /** Removes a tag protection rule — `DELETE /repos/{owner}/{repo}/tag_protections/{id}`.
    *
    * '''Retried''', because the request names a number the instance never reuses: a repeat can only ever address the
    * rule the first attempt addressed, so `N` attempts leave the instance where one would have. That is precisely the
    * property [[deleteBranchProtection]] lacks, which is why that one is not retried. The cost is stated in the class
    * note — a retry after a lost success answers `404`.
    *
    * '''Answers `204`.'''
    *
    * '''Failures.''' The group contract above.
    */
  def deleteTagProtection(owner: Owner, name: RepoName, id: TagProtectionId): Future[Unit] =
    pipeline.callUnit(RepositoryProtectionApi.deleteTagProtectionRequest(owner, name, id), RetryEligibility.AlwaysRetry)

  // --- collaborators --------------------------------------------------------

/** The requests this group issues, its operation ids, and its typed rail. */
object RepositoryProtectionApi:

  /** The stable operation id of [[RepositoryProtectionApi.branchProtections]]. Safe to alert on. */
  val ListBranchProtectionsOperation: String = "repos.branchProtections.list"

  /** The stable operation id of the single-rule branch protection read on [[RepositoryAccessApi]]. */
  val GetBranchProtectionOperation: String = "repos.branchProtections.get"

  /** The stable operation id of [[RepositoryProtectionApi.createBranchProtection]]. */
  val CreateBranchProtectionOperation: String = "repos.branchProtections.create"

  /** The stable operation id of [[RepositoryProtectionApi.editBranchProtection]]. */
  val EditBranchProtectionOperation: String = "repos.branchProtections.edit"

  /** The stable operation id of [[RepositoryProtectionApi.deleteBranchProtection]]. */
  val DeleteBranchProtectionOperation: String = "repos.branchProtections.delete"

  /** The stable operation id of [[RepositoryProtectionApi.tagProtections]]. */
  val ListTagProtectionsOperation: String = "repos.tagProtections.list"

  /** The stable operation id of the single-rule tag protection read on [[RepositoryAccessApi]]. */
  val GetTagProtectionOperation: String = "repos.tagProtections.get"

  /** The stable operation id of [[RepositoryProtectionApi.createTagProtection]]. */
  val CreateTagProtectionOperation: String = "repos.tagProtections.create"

  /** The stable operation id of [[RepositoryProtectionApi.editTagProtection]]. */
  val EditTagProtectionOperation: String = "repos.tagProtections.edit"

  /** The stable operation id of [[RepositoryProtectionApi.deleteTagProtection]]. */
  val DeleteTagProtectionOperation: String = "repos.tagProtections.delete"

  /** The typed rail of [[RepositoryProtectionApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]]
    * as a value.
    *
    * Obtained as `client.repos.access.protections.attempt`. Each method is the convenience-rail method with its
    * failure channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is
    * missing from both.
    */
  final class Attempt private[codeberg4s] (rail: RepositoryProtectionApi)(using exec: Exec[Future]):

    /** [[RepositoryProtectionApi.branchProtections]] with its failure as a value. */
    def branchProtections(
        owner: Owner,
        name: RepoName,
    ): Future[Either[CodebergError, Vector[BranchProtection]]] =
      exec.attempt(rail.branchProtections(owner, name))

    /** The single-rule branch protection read on [[RepositoryAccessApi]], with its failure as a value. */
    def branchProtection(
        owner: Owner,
        name: RepoName,
        rule: BranchRuleName,
    ): Future[Either[CodebergError, BranchProtection]] =
      exec.attempt(rail.branchProtection(owner, name, rule))

    /** [[RepositoryProtectionApi.createBranchProtection]] with its failure as a value. */
    def createBranchProtection(
        owner: Owner,
        name: RepoName,
        command: CreateBranchProtection,
    ): Future[Either[CodebergError, BranchProtection]] =
      exec.attempt(rail.createBranchProtection(owner, name, command))

    /** [[RepositoryProtectionApi.editBranchProtection]] with its failure as a value. */
    def editBranchProtection(
        owner: Owner,
        name: RepoName,
        rule: BranchRuleName,
        command: EditBranchProtection,
    ): Future[Either[CodebergError, BranchProtection]] =
      exec.attempt(rail.editBranchProtection(owner, name, rule, command))

    /** [[RepositoryProtectionApi.deleteBranchProtection]] with its failure as a value. */
    def deleteBranchProtection(
        owner: Owner,
        name: RepoName,
        rule: BranchRuleName,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteBranchProtection(owner, name, rule))

    /** [[RepositoryProtectionApi.tagProtections]] with its failure as a value. */
    def tagProtections(owner: Owner, name: RepoName): Future[Either[CodebergError, Vector[TagProtection]]] =
      exec.attempt(rail.tagProtections(owner, name))

    /** The single-rule tag protection read on [[RepositoryAccessApi]], with its failure as a value. */
    def tagProtection(
        owner: Owner,
        name: RepoName,
        id: TagProtectionId,
    ): Future[Either[CodebergError, TagProtection]] =
      exec.attempt(rail.tagProtection(owner, name, id))

    /** [[RepositoryProtectionApi.createTagProtection]] with its failure as a value. */
    def createTagProtection(
        owner: Owner,
        name: RepoName,
        command: CreateTagProtection,
    ): Future[Either[CodebergError, TagProtection]] =
      exec.attempt(rail.createTagProtection(owner, name, command))

    /** [[RepositoryProtectionApi.editTagProtection]] with its failure as a value. */
    def editTagProtection(
        owner: Owner,
        name: RepoName,
        id: TagProtectionId,
        command: EditTagProtection,
    ): Future[Either[CodebergError, TagProtection]] =
      exec.attempt(rail.editTagProtection(owner, name, id, command))

    /** [[RepositoryProtectionApi.deleteTagProtection]] with its failure as a value. */
    def deleteTagProtection(
        owner: Owner,
        name: RepoName,
        id: TagProtectionId,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteTagProtection(owner, name, id))

  private def branchProtectionsRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ListBranchProtectionsOperation, branchProtectionsPath(owner, name), Nil)

  private def branchProtectionRequest(owner: Owner, name: RepoName, rule: BranchRuleName): CodebergRequest =
    read(GetBranchProtectionOperation, branchProtectionPath(owner, name, rule), Nil)

  private def createBranchProtectionRequest(
      owner: Owner,
      name: RepoName,
      command: CreateBranchProtection,
  ): CodebergRequest =
    write(
      CreateBranchProtectionOperation,
      HttpMethod.Post,
      branchProtectionsPath(owner, name),
      CreateBranchProtectionOptionDto.render(command),
    )

  private def editBranchProtectionRequest(
      owner: Owner,
      name: RepoName,
      rule: BranchRuleName,
      command: EditBranchProtection,
  ): CodebergRequest =
    write(
      EditBranchProtectionOperation,
      HttpMethod.Patch,
      branchProtectionPath(owner, name, rule),
      EditBranchProtectionOptionDto.render(command),
    )

  private def deleteBranchProtectionRequest(owner: Owner, name: RepoName, rule: BranchRuleName): CodebergRequest =
    remove(DeleteBranchProtectionOperation, branchProtectionPath(owner, name, rule))

  private def tagProtectionsRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ListTagProtectionsOperation, tagProtectionsPath(owner, name), Nil)

  private def tagProtectionRequest(owner: Owner, name: RepoName, id: TagProtectionId): CodebergRequest =
    read(GetTagProtectionOperation, tagProtectionPath(owner, name, id), Nil)

  private def createTagProtectionRequest(
      owner: Owner,
      name: RepoName,
      command: CreateTagProtection,
  ): CodebergRequest =
    write(
      CreateTagProtectionOperation,
      HttpMethod.Post,
      tagProtectionsPath(owner, name),
      TagProtectionOptionDto.renderCreate(command),
    )

  private def editTagProtectionRequest(
      owner: Owner,
      name: RepoName,
      id: TagProtectionId,
      command: EditTagProtection,
  ): CodebergRequest =
    write(
      EditTagProtectionOperation,
      HttpMethod.Patch,
      tagProtectionPath(owner, name, id),
      TagProtectionOptionDto.renderEdit(command),
    )

  private def deleteTagProtectionRequest(owner: Owner, name: RepoName, id: TagProtectionId): CodebergRequest =
    remove(DeleteTagProtectionOperation, tagProtectionPath(owner, name, id))

  private def branchProtectionsPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "branch_protections"

  private def branchProtectionPath(owner: Owner, name: RepoName, rule: BranchRuleName): List[String] =
    branchProtectionsPath(owner, name) :+ rule.value

  private def tagProtectionsPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "tag_protections"

  private def tagProtectionPath(owner: Owner, name: RepoName, id: TagProtectionId): List[String] =
    tagProtectionsPath(owner, name) :+ id.value.toString
