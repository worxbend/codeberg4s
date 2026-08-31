package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.core.CodebergRequest.{read, remove, removeWithBody, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.issues.wire.{EditLabelOptionDto, IssueLabelsOptionDto}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName}

import scala.concurrent.Future

/** A repository's labels once they exist, and which of them are on an issue.
  *
  * Reached as `client.issues.labels`. Listing a repository's labels and creating one live on [[IssueApi]] itself, where
  * they were written; this class is the rest — reading, editing and deleting a label, and the five operations that
  * attach labels to an issue.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[IssueLabelApi.attempt]] never fail and
  * return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Evidence==
  *
  * The `Label` model is captured — `golden/issue/labels-repo.json` and `golden/issue/labels-on-issue-empty.json` are
  * real responses, and the latter is exactly what [[listOnIssue]] returns for an unlabelled issue. The '''requests'''
  * are not: `EditLabelOption`, `IssueLabelsOption` and `DeleteLabelsOption` are read from `spec/swagger.v1.json`,
  * because every write needs a token and the harvest was anonymous.
  *
  * ==Adding is not replacing, and only one of them may be repeated==
  *
  * This is the sharpest retry distinction in the group, so it is stated here as well as on the methods:
  *
  *   - [[replaceOnIssue]] is a `PUT` that sets the issue's '''complete''' label set. Doing it twice leaves the issue
  *     exactly where doing it once would, so it is [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]].
  *   - [[addToIssue]] is a `POST` that '''adds''' to whatever is already there. It is
  *     [[com.worxbend.codeberg4s.core.RetryEligibility.Never]] — this library never repeats a `POST` — and the
  *     underlying operation is not the kind of thing repeating is safe for in general, even where Forgejo happens to
  *     ignore a duplicate.
  *
  * A caller who wants an add that is safe to repeat reads the current labels and issues a replace.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository, the issue or the label does
  *     not exist '''or''' is private to credentials the client does not have — Forgejo does not distinguish the two, on
  *     purpose — `401` when a token was required and none was sent, and `403` when the token lacks the scope. `422`
  *     '''and''' `400` both mean the request was rejected as invalid, per `docs/HAZARDS.md` §4; a `422` on
  *     [[removeFromIssue]] specifically is what an unparseable `{identifier}` produces.
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
final class IssueLabelApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: IssueLabelApi.Attempt = IssueLabelApi.Attempt(this)

  // --- a repository's labels ------------------------------------------------

  /** Reads one label — `GET /repos/{owner}/{repo}/labels/{id}`.
    *
    * Addressed by id rather than by name, because a name is not unique across a repository and an organisation and is
    * freely renamed; see [[LabelId]].
    *
    * '''Failures.''' The group contract above.
    */
  def get(owner: Owner, name: RepoName, id: LabelId): Future[Label] =
    pipeline.call(IssueLabelApi.getRequest(owner, name, id), RetryEligibility.IdempotentOnly)(using IssueDecoders.label)

  /** Edits a label — `PATCH /repos/{owner}/{repo}/labels/{id}`.
    *
    * '''Never retried.''' It names one label by an id the instance never reuses and sets stated values, which looks
    * idempotent — but a repeat after a lost success overwrites whatever the label has become in the meantime, including
    * somebody else's rename or recolour, and Forgejo offers no conditional-update header that would let the instance
    * refuse a stale write. [[IssueApi]] makes the same call for its `PATCH`.
    *
    * '''Only what the command sets is sent'''; everything else keeps its current value. See [[EditLabel]] for why the
    * two flags are `Option[Boolean]`.
    *
    * '''Failures.''' The group contract above. A `422` means Forgejo rejected the name or the colour — the colour is
    * sent in the `#rrggbb` form it documents, so a `422` on colour is unlikely.
    */
  def edit(owner: Owner, name: RepoName, id: LabelId, command: EditLabel): Future[Label] =
    pipeline.call(IssueLabelApi.editRequest(owner, name, id, command), RetryEligibility.Never)(using IssueDecoders.label)

  /** Deletes a label from the repository — `DELETE /repos/{owner}/{repo}/labels/{id}`.
    *
    * '''This removes it from every issue that carries it''', which is why it is a different operation from
    * [[removeFromIssue]].
    *
    * '''Retried''', because the request names exactly one object by an identifier the instance never reuses: a label id
    * is a database row id, so the state after N attempts is the state after one and nothing is created. The cost is one
    * a caller has to know — if the first attempt succeeded and its response was lost, the retry addresses something
    * that no longer exists and answers `404`. A `404` from a delete therefore means "it is gone", not necessarily "it
    * was never there".
    *
    * '''Answers `204`''', so there is nothing to return.
    *
    * '''Failures.''' The group contract above.
    */
  def delete(owner: Owner, name: RepoName, id: LabelId): Future[Unit] =
    pipeline.callUnit(IssueLabelApi.deleteRequest(owner, name, id), RetryEligibility.AlwaysRetry)

  // --- an issue's labels ----------------------------------------------------

  /** Lists the labels on one issue — `GET /repos/{owner}/{repo}/issues/{index}/labels`.
    *
    * '''Not paged, and that is the endpoint's decision.''' The spec declares no `page` or `limit` — the response is
    * typed `LabelListWithoutPagination` — so the whole list arrives at once and the result is a `Vector` rather than a
    * [[com.worxbend.codeberg4s.paging.Page]]. `golden/issue/labels-on-issue-empty.json` is a capture of this exact
    * endpoint, and it is `[]`: an unlabelled issue is an empty array, not a `404`.
    *
    * The same labels also arrive on [[Issue.labels]] whenever an issue is read, so this endpoint is for the case where
    * a caller wants only the labels.
    *
    * '''Failures.''' The group contract above.
    */
  def listOnIssue(owner: Owner, name: RepoName, number: IssueNumber): Future[Vector[Label]] =
    pipeline.call(IssueLabelApi.listOnIssueRequest(owner, name, number), RetryEligibility.IdempotentOnly)(using
      IssueDecoders.labels)

  /** Adds labels to an issue, keeping the ones it already has — `POST /repos/{owner}/{repo}/issues/{index}/labels`.
    *
    * '''Never retried''', and the contrast with [[replaceOnIssue]] is the point: see the class note. A `POST` here adds
    * to a set the request does not fully describe, so the library does not decide on a caller's behalf that repeating
    * it is safe.
    *
    * '''Answers the issue's complete label set''', not just the ones added.
    *
    * '''Failures.''' The group contract above. A `404` covers a label id from another repository as well as a missing
    * issue.
    */
  def addToIssue(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      command: LabelUpdate,
  ): Future[Vector[Label]] =
    pipeline.call(IssueLabelApi.addToIssueRequest(owner, name, number, command), RetryEligibility.Never)(using
      IssueDecoders.labels)

  /** Replaces an issue's labels outright — `PUT /repos/{owner}/{repo}/issues/{index}/labels`.
    *
    * '''Retried''', because the request states the issue's complete label set: doing it twice leaves the issue exactly
    * where doing it once would, nothing is created, and a lost success costs nothing to repeat. This is the one write
    * in this class where a retry is unambiguously harmless, and the reason it differs from [[addToIssue]] is the method
    * rather than the body — see the class note.
    *
    * '''[[LabelUpdate.Empty]] clears the issue''', because an empty complete set is no labels. That is not the same
    * request as [[clearOnIssue]] only in spelling; both end with an unlabelled issue.
    *
    * '''Answers the issue's complete label set.'''
    *
    * '''Failures.''' The group contract above.
    */
  def replaceOnIssue(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      command: LabelUpdate,
  ): Future[Vector[Label]] =
    pipeline.call(IssueLabelApi.replaceOnIssueRequest(owner, name, number, command), RetryEligibility.AlwaysRetry)(using
      IssueDecoders.labels)

  /** Removes one label from an issue — `DELETE /repos/{owner}/{repo}/issues/{index}/labels/{identifier}`.
    *
    * '''The label may be named by id or by name'''; see [[LabelRef]], including why the id is the better identifier and
    * what happens to a name containing `/`.
    *
    * '''Retried''', because the request names exactly which label to detach from exactly which issue and the state
    * after N attempts is the state after one. Nothing is created and nothing else is touched — the label itself
    * survives on every other issue, which is what makes this different from [[delete]].
    *
    * '''Answers `204`''', so there is nothing to return.
    *
    * '''Failures.''' The group contract above; a `422` here is an `{identifier}` Forgejo could not parse.
    */
  def removeFromIssue(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      label: LabelRef,
      command: LabelRemoval,
  ): Future[Unit] =
    pipeline.callUnit(
      IssueLabelApi.removeFromIssueRequest(owner, name, number, label, command),
      RetryEligibility.AlwaysRetry,
    )

  /** Removes every label from an issue — `DELETE /repos/{owner}/{repo}/issues/{index}/labels`.
    *
    * '''Retried''', for the reason [[replaceOnIssue]] gives: the request states an absolute end state — no labels — so
    * repeating it changes nothing. Unlike most deletes in this library, a lost success does not turn into a `404`: the
    * issue is still there, it simply has no labels left to remove.
    *
    * '''Answers `204`''', so there is nothing to return.
    *
    * '''Failures.''' The group contract above.
    */
  def clearOnIssue(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      command: LabelRemoval,
  ): Future[Unit] =
    pipeline.callUnit(
      IssueLabelApi.clearOnIssueRequest(owner, name, number, command),
      RetryEligibility.AlwaysRetry,
    )

/** The requests this group issues, its operation ids, and its typed rail. */
object IssueLabelApi:

  /** The stable operation id of the single-label read on [[IssueLabelApi]]. Safe to alert on. */
  val GetOperation: String = "issues.labels.get"

  /** The stable operation id of [[IssueLabelApi.edit]]. */
  val EditOperation: String = "issues.labels.edit"

  /** The stable operation id of [[IssueLabelApi.delete]]. */
  val DeleteOperation: String = "issues.labels.delete"

  /** The stable operation id of [[IssueLabelApi.listOnIssue]]. */
  val ListOnIssueOperation: String = "issues.labels.listOnIssue"

  /** The stable operation id of [[IssueLabelApi.addToIssue]]. */
  val AddToIssueOperation: String = "issues.labels.addToIssue"

  /** The stable operation id of [[IssueLabelApi.replaceOnIssue]]. */
  val ReplaceOnIssueOperation: String = "issues.labels.replaceOnIssue"

  /** The stable operation id of [[IssueLabelApi.removeFromIssue]]. */
  val RemoveFromIssueOperation: String = "issues.labels.removeFromIssue"

  /** The stable operation id of [[IssueLabelApi.clearOnIssue]]. */
  val ClearOnIssueOperation: String = "issues.labels.clearOnIssue"

  /** The typed rail of [[IssueLabelApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.issues.labels.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: IssueLabelApi)(using exec: Exec[Future]):

    /** The single-label read on [[IssueLabelApi]], with its failure as a value. */
    def get(owner: Owner, name: RepoName, id: LabelId): Future[Either[CodebergError, Label]] =
      exec.attempt(rail.get(owner, name, id))

    /** [[IssueLabelApi.edit]] with its failure as a value. */
    def edit(
        owner: Owner,
        name: RepoName,
        id: LabelId,
        command: EditLabel,
    ): Future[Either[CodebergError, Label]] =
      exec.attempt(rail.edit(owner, name, id, command))

    /** [[IssueLabelApi.delete]] with its failure as a value. */
    def delete(owner: Owner, name: RepoName, id: LabelId): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.delete(owner, name, id))

    /** [[IssueLabelApi.listOnIssue]] with its failure as a value. */
    def listOnIssue(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
    ): Future[Either[CodebergError, Vector[Label]]] =
      exec.attempt(rail.listOnIssue(owner, name, number))

    /** [[IssueLabelApi.addToIssue]] with its failure as a value. */
    def addToIssue(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        command: LabelUpdate,
    ): Future[Either[CodebergError, Vector[Label]]] =
      exec.attempt(rail.addToIssue(owner, name, number, command))

    /** [[IssueLabelApi.replaceOnIssue]] with its failure as a value. */
    def replaceOnIssue(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        command: LabelUpdate,
    ): Future[Either[CodebergError, Vector[Label]]] =
      exec.attempt(rail.replaceOnIssue(owner, name, number, command))

    /** [[IssueLabelApi.removeFromIssue]] with its failure as a value. */
    def removeFromIssue(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        label: LabelRef,
        command: LabelRemoval,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.removeFromIssue(owner, name, number, label, command))

    /** [[IssueLabelApi.clearOnIssue]] with its failure as a value. */
    def clearOnIssue(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        command: LabelRemoval,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.clearOnIssue(owner, name, number, command))

  private def getRequest(owner: Owner, name: RepoName, id: LabelId): CodebergRequest =
    read(GetOperation, IssueRequests.labelPath(owner, name, id), Nil)

  private def editRequest(owner: Owner, name: RepoName, id: LabelId, command: EditLabel): CodebergRequest =
    write(
      EditOperation,
      HttpMethod.Patch,
      IssueRequests.labelPath(owner, name, id),
      EditLabelOptionDto.render(command),
    )

  private def deleteRequest(owner: Owner, name: RepoName, id: LabelId): CodebergRequest =
    remove(DeleteOperation, IssueRequests.labelPath(owner, name, id))

  private def listOnIssueRequest(owner: Owner, name: RepoName, number: IssueNumber): CodebergRequest =
    read(ListOnIssueOperation, issueLabelsPath(owner, name, number), Nil)

  private def addToIssueRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      command: LabelUpdate,
  ): CodebergRequest =
    write(
      AddToIssueOperation,
      HttpMethod.Post,
      issueLabelsPath(owner, name, number),
      IssueLabelsOptionDto.renderUpdate(command),
    )

  private def replaceOnIssueRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      command: LabelUpdate,
  ): CodebergRequest =
    write(
      ReplaceOnIssueOperation,
      HttpMethod.Put,
      issueLabelsPath(owner, name, number),
      IssueLabelsOptionDto.renderUpdate(command),
    )

  private def removeFromIssueRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      label: LabelRef,
      command: LabelRemoval,
  ): CodebergRequest =
    removeWithBody(
      RemoveFromIssueOperation,
      issueLabelsPath(owner, name, number) :+ label.pathSegment,
      IssueLabelsOptionDto.renderRemoval(command),
    )

  private def clearOnIssueRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      command: LabelRemoval,
  ): CodebergRequest =
    removeWithBody(
      ClearOnIssueOperation,
      issueLabelsPath(owner, name, number),
      IssueLabelsOptionDto.renderRemoval(command),
    )

  private def issueLabelsPath(owner: Owner, name: RepoName, number: IssueNumber): List[String] =
    IssueRequests.issuePath(owner, name, number) :+ "labels"
