package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.issues.wire.MilestoneOptionDto

import scala.concurrent.Future

/** Creating, editing and deleting a repository's milestones.
  *
  * Reached as `client.issues.milestones`. Listing milestones and reading one live on [[IssueApi]] itself, where they
  * were written; this class is the three writes.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[IssueMilestoneApi.attempt]] never fail
  * and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Evidence==
  *
  * The `Milestone` model is captured: `golden/issue/milestones-list.json` is a real response and
  * [[com.worxbend.codeberg4s.issues.wire.MilestoneDto]] is derived from it. The '''requests''' are not —
  * `CreateMilestoneOption` and `EditMilestoneOption` are read from `spec/swagger.v1.json`, because every write needs a
  * token and the harvest was anonymous.
  *
  * ==Addressed by id, never by title==
  *
  * Forgejo accepts either a milestone id or a milestone title in the `{id}` path segment. This library sends only the
  * id, for the reason [[MilestoneId]] gives: a title is not unique and is freely renamed, and the id is what survives a
  * rename. That is also why [[create]] returns the created milestone rather than `Unit` — the id it hands back is the
  * only durable handle on what was just made.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository or the milestone does not
  *     exist '''or''' is private to credentials the client does not have — Forgejo does not distinguish the two, on
  *     purpose — `401` when a token was required and none was sent, and `403` when the token lacks the scope. `422`
  *     '''and''' `400` both mean the request was rejected as invalid, per `docs/HAZARDS.md` §4; a malformed `due_on` is
  *     the likeliest cause here, and it comes back carrying a raw Go parse error.
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
final class IssueMilestoneApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: IssueMilestoneApi.Attempt = IssueMilestoneApi.Attempt(this)

  /** Creates a milestone — `POST /repos/{owner}/{repo}/milestones`.
    *
    * '''Never retried.''' Forgejo does not reject a duplicate milestone title, so a repeat leaves two identical
    * milestones with different ids and issues split between them. A transport failure therefore leaves the caller
    * genuinely unsure whether the milestone exists, which is the honest state of affairs and better than two —
    * [[IssueApi.listMilestones]] resolves it.
    *
    * '''Answers `201`''' with the created milestone, including the [[MilestoneId]] every later call needs.
    *
    * '''Failures.''' The group contract above.
    */
  def create(owner: Owner, name: RepoName, command: CreateMilestone): Future[Milestone] =
    pipeline.call(IssueMilestoneApi.createRequest(owner, name, command), RetryEligibility.Never)(using
      IssueDecoders.milestone)

  /** Edits a milestone — `PATCH /repos/{owner}/{repo}/milestones/{id}`.
    *
    * '''Never retried.''' It names one milestone by an id the instance never reuses and sets stated values, which looks
    * idempotent — but a repeat after a lost success overwrites whatever the milestone has become in the meantime,
    * including somebody else's rename or a close another client has since undone, and Forgejo offers no
    * conditional-update header that would let the instance refuse a stale write. [[IssueApi]] makes the same call for
    * its `PATCH`.
    *
    * '''Only what the command sets is sent.''' There is no way to '''clear''' a deadline here — Forgejo declares no
    * `unset_due_on` flag, unlike the issue edit's `unset_due_date`; see [[EditMilestone]].
    *
    * '''Failures.''' The group contract above.
    */
  def edit(owner: Owner, name: RepoName, id: MilestoneId, command: EditMilestone): Future[Milestone] =
    pipeline.call(IssueMilestoneApi.editRequest(owner, name, id, command), RetryEligibility.Never)(using
      IssueDecoders.milestone)

  /** Deletes a milestone — `DELETE /repos/{owner}/{repo}/milestones/{id}`.
    *
    * '''The issues survive'''; they simply stop belonging to a milestone. Nothing here removes an issue.
    *
    * '''Retried''', because the request names exactly one object by an identifier the instance never reuses: a
    * milestone id is a database row id, so the state after N attempts is the state after one and nothing is created.
    * The cost is one a caller has to know — if the first attempt succeeded and its response was lost, the retry
    * addresses something that no longer exists and answers `404`. A `404` from a delete therefore means "it is gone",
    * not necessarily "it was never there".
    *
    * '''Answers `204`''', so there is nothing to return.
    *
    * '''Failures.''' The group contract above.
    */
  def delete(owner: Owner, name: RepoName, id: MilestoneId): Future[Unit] =
    pipeline.callUnit(IssueMilestoneApi.deleteRequest(owner, name, id), RetryEligibility.AlwaysRetry)

/** The requests this group issues, its operation ids, and its typed rail. */
object IssueMilestoneApi:

  /** The stable operation id of [[IssueMilestoneApi.create]]. Safe to alert on. */
  val CreateOperation: String = "issues.milestones.create"

  /** The stable operation id of [[IssueMilestoneApi.edit]]. */
  val EditOperation: String = "issues.milestones.edit"

  /** The stable operation id of [[IssueMilestoneApi.delete]]. */
  val DeleteOperation: String = "issues.milestones.delete"

  /** The typed rail of [[IssueMilestoneApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.issues.milestones.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: IssueMilestoneApi)(using exec: Exec[Future]):

    /** [[IssueMilestoneApi.create]] with its failure as a value. */
    def create(
        owner: Owner,
        name: RepoName,
        command: CreateMilestone,
    ): Future[Either[CodebergError, Milestone]] =
      exec.attempt(rail.create(owner, name, command))

    /** [[IssueMilestoneApi.edit]] with its failure as a value. */
    def edit(
        owner: Owner,
        name: RepoName,
        id: MilestoneId,
        command: EditMilestone,
    ): Future[Either[CodebergError, Milestone]] =
      exec.attempt(rail.edit(owner, name, id, command))

    /** [[IssueMilestoneApi.delete]] with its failure as a value. */
    def delete(owner: Owner, name: RepoName, id: MilestoneId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.delete(owner, name, id))

  private def createRequest(owner: Owner, name: RepoName, command: CreateMilestone): CodebergRequest =
    IssueRequests.write(
      CreateOperation,
      HttpMethod.Post,
      IssueRequests.repoPath(owner, name) :+ "milestones",
      MilestoneOptionDto.renderCreate(command),
    )

  private def editRequest(
      owner: Owner,
      name: RepoName,
      id: MilestoneId,
      command: EditMilestone,
  ): CodebergRequest =
    IssueRequests.write(
      EditOperation,
      HttpMethod.Patch,
      IssueRequests.milestonePath(owner, name, id),
      MilestoneOptionDto.renderEdit(command),
    )

  private def deleteRequest(owner: Owner, name: RepoName, id: MilestoneId): CodebergRequest =
    IssueRequests.remove(DeleteOperation, IssueRequests.milestonePath(owner, name, id))
