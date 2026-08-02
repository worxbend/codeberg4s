package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RequestBody
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.issues.wire.CommentDto
import com.worxbend.codeberg4s.issues.wire.CreateIssueCommentOptionDto
import com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto
import com.worxbend.codeberg4s.issues.wire.CreateLabelOptionDto
import com.worxbend.codeberg4s.issues.wire.EditDeadlineOptionDto
import com.worxbend.codeberg4s.issues.wire.EditIssueOptionDto
import com.worxbend.codeberg4s.issues.wire.IssueDto
import com.worxbend.codeberg4s.issues.wire.IssueMetaDto
import com.worxbend.codeberg4s.issues.wire.IssueQueries
import com.worxbend.codeberg4s.issues.wire.LabelDto
import com.worxbend.codeberg4s.issues.wire.MilestoneDto
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName

import scala.concurrent.Future

import java.time.Instant

/** Issue endpoints, together with the comments, labels and milestones that hang off them.
  *
  * Reached as `client.issues`. Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[IssueApi.attempt]] never fail and return
  * an `Either` instead. The typed rail is derived from this one by [[com.worxbend.codeberg4s.core.Exec.attempt]], so
  * the two cannot disagree about what an operation does.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository does not exist '''or''' is
  *     private to credentials the client does not have — Forgejo does not distinguish the two, on purpose — `401` when
  *     a token was required and none was sent, and `403` when the token lacks the scope. `422` '''and''' `400` both
  *     mean the request was rejected as invalid: `docs/HAZARDS.md` §4 captured Forgejo using `400` for a malformed
  *     identifier and `422` for a malformed timestamp, so a caller checking only for `422` will miss half of them.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type — [[com.worxbend.codeberg4s.repositories.Owner]], [[IssueNumber]], [[LabelName]] — so a
  * value that would forge a path or a query parameter is rejected by its own smart constructor before a client is ever
  * involved.
  *
  * ==Retries==
  *
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. Every `POST` and every `PATCH` here uses
  * [[com.worxbend.codeberg4s.core.RetryEligibility.Never]]: Forgejo offers no idempotency key, so a retried `POST`
  * files a second issue and a retried `PATCH` re-applies an edit against whatever the resource has become in the
  * meantime. Neither is a decision this library makes on a caller's behalf.
  *
  * The `PUT` and `DELETE` operations added later are decided per endpoint and each decision is justified where it is
  * made; the rule they are measured against is that [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]]
  * requires the request to name exactly which object it acts on, by an identifier the instance never reuses, so that
  * the state after N attempts is the state after one and nothing is created. [[IssueApi.movePin]] is the one `PATCH` in
  * this class that meets that bar, and it says why.
  *
  * ==The rest of the issue surface==
  *
  * Seven sub-APIs hang off this class, each owning one concept and each with its own `attempt` rail:
  * [[IssueApi.comments]], [[IssueApi.attachments]], [[IssueApi.reactions]], [[IssueApi.labels]],
  * [[IssueApi.milestones]], [[IssueApi.times]] and [[IssueApi.subscriptions]]. They are separate classes rather than
  * ninety more methods here for the reason [[com.worxbend.codeberg4s.repositories.RepositoryApi]] splits actions, Git
  * and publishing out: a class nobody can read is a class nobody can review.
  */
final class IssueApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: IssueApi.Attempt = IssueApi.Attempt(this)

  /** Reading, editing and deleting a comment, and listing every comment in the repository. */
  val comments: IssueCommentApi = IssueCommentApi(pipeline)

  /** The files attached to an issue and to a comment. */
  val attachments: IssueAttachmentApi = IssueAttachmentApi(pipeline)

  /** Emoji reactions on an issue and on a comment. */
  val reactions: IssueReactionApi = IssueReactionApi(pipeline)

  /** A repository's labels once they exist, and which of them are on an issue. */
  val labels: IssueLabelApi = IssueLabelApi(pipeline)

  /** Creating, editing and deleting a repository's milestones. */
  val milestones: IssueMilestoneApi = IssueMilestoneApi(pipeline)

  /** Timetracking: the stopwatch, and the log of worked time. */
  val times: IssueTimeApi = IssueTimeApi(pipeline)

  /** Who follows an issue, and whether the authenticated account does. */
  val subscriptions: IssueSubscriptionApi = IssueSubscriptionApi(pipeline)

  /** Lists a repository's issues — `GET /repos/{owner}/{repo}/issues`.
    *
    * '''The listing contains pull requests.''' Forgejo serves both from this endpoint; [[Issue.isPullRequest]] is how
    * they are told apart. `golden/issue/list-labelled.json` is a capture of this endpoint in which every element is a
    * pull request.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value, so `items.size < limit` fires on page
    * one of thirty-two (`docs/HAZARDS.md` §5). A page past the end is `200` with `[]`, not a `404`.
    *
    * '''Failures.''' The group contract above. A `422` here most often means a malformed `since` or `before`.
    *
    * @param query
    *   the filters to apply; [[IssueQuery.Empty]] asks for the instance's default, which is open issues only
    * @param page
    *   which window to fetch, and how large
    */
  def list(owner: Owner, name: RepoName, query: IssueQuery, page: PageParams): Future[Page[Issue]] =
    pipeline.callPage(IssueApi.listRequest(owner, name, query, page), page)(using IssueApi.IssuesDecoder)

  /** Reads one issue — `GET /repos/{owner}/{repo}/issues/{index}`.
    *
    * '''Failures.''' The group contract above. `404` covers both "no such issue in this repository" and "no such
    * repository"; the response body's `errors` array is the only thing that distinguishes them, and it is not always
    * present.
    *
    * @param number
    *   the per-repository issue number, '''not''' [[Issue.id]] — see [[IssueNumber]]
    */
  def get(owner: Owner, name: RepoName, number: IssueNumber): Future[Issue] =
    val request = IssueApi.getRequest(owner, name, number)

    pipeline.call(request, RetryEligibility.IdempotentOnly)(using IssueApi.IssueDecoder)

  /** Opens an issue — `POST /repos/{owner}/{repo}/issues`.
    *
    * '''Never retried.''' Repeating this call files a second issue, and Forgejo has no idempotency key that would let
    * the instance recognise the repeat. A transport failure therefore leaves the caller genuinely unsure whether the
    * issue exists, which is the honest state of affairs and better than two issues.
    *
    * '''Failures.''' The group contract above, plus `403` when issues are disabled on the repository and `423` when the
    * repository is archived. A `422` means Forgejo rejected the payload — an assignee who cannot be assigned, a
    * milestone from another repository.
    *
    * @param command
    *   what to create; built from [[CreateIssue.of]], which has already rejected a blank title
    */
  def create(owner: Owner, name: RepoName, command: CreateIssue): Future[Issue] =
    pipeline.call(IssueApi.createRequest(owner, name, command), RetryEligibility.Never)(using IssueApi.IssueDecoder)

  /** Edits an issue — `PATCH /repos/{owner}/{repo}/issues/{index}`.
    *
    * '''Never retried''', even though `PATCH` on a specific resource looks idempotent. It is not, here: the body is a
    * partial update applied to whatever the issue has become, so a repeat after a transport failure can overwrite a
    * change someone else made in between. A caller who knows their edit is safe to repeat can re-issue it themselves.
    *
    * '''Only what the command sets is sent'''; everything else keeps its current value. Clearing a deadline is
    * [[EditIssue.withoutDueDate]], and replacing assignees with none is [[EditIssue.assignedTo]] with an empty vector —
    * see [[EditIssue]] for why those are different from doing nothing.
    *
    * '''Failures.''' The group contract above. Forgejo answers a successful edit with `201`, not `200`; both are
    * success as far as [[com.worxbend.codeberg4s.core.StatusMapping]] is concerned.
    */
  def edit(owner: Owner, name: RepoName, number: IssueNumber, command: EditIssue): Future[Issue] =
    pipeline.call(IssueApi.editRequest(owner, name, number, command), RetryEligibility.Never)(using
      IssueApi.IssueDecoder)

  /** Lists an issue's comments — `GET /repos/{owner}/{repo}/issues/{index}/comments`.
    *
    * '''Paging on this endpoint is weaker than on the others, and a caller has to know it.''' The pinned spec declares
    * no `page` or `limit` parameter for this operation, and the captured response (`golden/issue/comments-list.json`)
    * carried `X-Total-Count` but '''no''' `Link` header. `page` and `limit` are still sent, because they cost nothing
    * and Forgejo honours them wherever it supports them — but two things follow that do not hold for [[list]]:
    *
    *   - the returned page always reports itself as the last one, since `nextPage` is read from `rel="next"` and there
    *     is no `Link` header to read it from;
    *   - an instance that ignores the parameters answers every request with the '''complete''' comment list, so asking
    *     for page two may return the same items as page one rather than nothing.
    *
    * Compare [[Page.totalCount]] with [[Page.size]] to find out which happened.
    *
    * '''Failures.''' The group contract above, plus `500`, which the spec declares for this operation and which arrives
    * as [[com.worxbend.codeberg4s.CodebergError.Api]] like any other status.
    */
  def listComments(owner: Owner, name: RepoName, number: IssueNumber, page: PageParams): Future[Page[Comment]] =
    pipeline.callPage(IssueApi.listCommentsRequest(owner, name, number, page), page)(using IssueApi.CommentsDecoder)

  /** Comments on an issue — `POST /repos/{owner}/{repo}/issues/{index}/comments`.
    *
    * '''Never retried''', for the reason [[create]] gives: a repeat posts the comment twice.
    *
    * '''Failures.''' The group contract above, plus `423` when the issue is locked and `403` when the token may read
    * the repository but not write to it.
    */
  def createComment(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      command: CreateComment,
  ): Future[Comment] =
    pipeline.call(IssueApi.createCommentRequest(owner, name, number, command), RetryEligibility.Never)(using
      IssueApi.CommentDecoder)

  /** Lists a repository's labels — `GET /repos/{owner}/{repo}/labels`.
    *
    * These are the labels the repository '''offers''', not the ones on any particular issue; an issue's own labels
    * arrive on [[Issue.labels]].
    *
    * '''Paging.''' The endpoint declares `page` and `limit` and reports `X-Total-Count`, but `golden/MANIFEST.md`
    * records that it sends no `Link` header, so — as with [[listComments]] — the returned page reports itself as the
    * last one whatever the total says. Compare [[Page.totalCount]] with [[Page.size]] to decide whether to ask for
    * another window.
    *
    * '''Failures.''' The group contract above.
    */
  def listLabels(owner: Owner, name: RepoName, page: PageParams): Future[Page[Label]] =
    pipeline.callPage(IssueApi.listLabelsRequest(owner, name, page), page)(using IssueApi.LabelsDecoder)

  /** Creates a label on a repository — `POST /repos/{owner}/{repo}/labels`.
    *
    * '''Never retried''', for the reason [[create]] gives: Forgejo does not reject a duplicate label name, so a repeat
    * leaves two identical labels with different ids.
    *
    * '''Failures.''' The group contract above. A `422` means Forgejo rejected the name or the colour — the colour is
    * sent in the `#rrggbb` form it documents, so a `422` on colour is unlikely.
    */
  def createLabel(owner: Owner, name: RepoName, command: CreateLabel): Future[Label] =
    val request = IssueApi.createLabelRequest(owner, name, command)

    pipeline.call(request, RetryEligibility.Never)(using IssueApi.LabelDecoder)

  /** Lists a repository's milestones — `GET /repos/{owner}/{repo}/milestones`.
    *
    * '''Paging.''' As [[listLabels]]: `page` and `limit` are declared and `X-Total-Count` is sent, but no `Link`
    * header, so the returned page always reports itself as the last one.
    *
    * '''Failures.''' The group contract above.
    *
    * @param state
    *   which milestones to include. Explicit rather than optional here, unlike [[IssueQuery.state]], because the
    *   endpoint has no other filter worth naming and Forgejo's silent default of open-only surprises callers
    */
  def listMilestones(owner: Owner, name: RepoName, state: StateFilter, page: PageParams): Future[Page[Milestone]] =
    pipeline.callPage(IssueApi.listMilestonesRequest(owner, name, state, page), page)(using IssueApi.MilestonesDecoder)

  /** Reads one milestone — `GET /repos/{owner}/{repo}/milestones/{id}`.
    *
    * '''Failures.''' The group contract above. Forgejo accepts either a milestone id or a milestone title in this path
    * segment; this library sends only the id, because a title is not unique and is freely renamed.
    */
  def getMilestone(owner: Owner, name: RepoName, id: MilestoneId): Future[Milestone] =
    pipeline.call(IssueApi.getMilestoneRequest(owner, name, id), RetryEligibility.IdempotentOnly)(using
      IssueApi.MilestoneDecoder)

  /** Searches issues across every repository the caller can see — `GET /repos/issues/search`.
    *
    * '''The body is a bare array, and that is worth knowing.''' `GET /repos/search` and `GET /users/search` both answer
    * a `{"ok": true, "data": [...]}` envelope — `docs/HAZARDS.md` §3 records them by name — and '''this endpoint does
    * not'''. `golden/issue/search.json` is a capture of exactly this operation and its first character is `[`. Decoding
    * it as an envelope fails.
    *
    * '''The results contain pull requests''' unless [[IssueSearchQuery.onlyOf]] says otherwise; see
    * [[Issue.isPullRequest]] and [[IssueKind]]. [[Issue.repository]] is worth reading here in a way it is not on a
    * single repository's listing, because the issues come from everywhere.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value (`docs/HAZARDS.md` §5). The capture
    * above reported 193 825 matching issues on a page of three, so this is an endpoint where treating a short page as
    * the end would silently lose almost everything.
    *
    * '''Anonymously this is still useful''', because the endpoint's security is optional: it then searches every public
    * repository. The five "me" filters on [[IssueSearchQuery]] simply match nothing.
    *
    * '''Failures.''' The group contract above. A `422` here most often means a malformed `since` or `before`.
    *
    * @param query
    *   the filters to apply; [[IssueSearchQuery.Empty]] asks for the instance's default, which is open issues
    */
  def search(query: IssueSearchQuery, page: PageParams): Future[Page[Issue]] =
    pipeline.callPage(IssueApi.searchRequest(query, page), page)(using IssueDecoders.issues)

  /** Deletes an issue — `DELETE /repos/{owner}/{repo}/issues/{index}`.
    *
    * '''There is no undo, and the issue takes its comments, attachments, reactions and tracked time with it.''' Closing
    * an issue is [[edit]] with [[EditIssue.close]]; that is almost always what a caller wants instead.
    *
    * '''Retried''', because the request names exactly one object — `owner`, `repo` and `index` together identify one
    * issue forever, since a repository's issue counter only ever increases and never reuses a number — so the state
    * after N attempts is the state after one and nothing is created. The cost is one a caller has to know: if the first
    * attempt succeeded and its response was lost, the retry addresses something that no longer exists and answers
    * `404`. A `404` from this call therefore means "it is gone", not necessarily "it was never there", and a caller who
    * needs to distinguish those must read the issue before deleting it.
    *
    * '''Answers `204`''', so there is nothing to return.
    *
    * '''Failures.''' The group contract above. `403` is what a token that may read the repository but not administer it
    * gets.
    */
  def delete(owner: Owner, name: RepoName, number: IssueNumber): Future[Unit] =
    pipeline.callUnit(IssueApi.deleteRequest(owner, name, number), RetryEligibility.AlwaysRetry)

  /** Sets an issue's deadline — `POST /repos/{owner}/{repo}/issues/{index}/deadline`.
    *
    * '''Only the date is used.''' The spec says so in words: "if using deadline only the date will be taken into
    * account, and time of day ignored". The instant is still sent whole, because truncating it here would decide the
    * caller's time zone for them.
    *
    * '''There is no way to clear a deadline through this endpoint''', despite the spec's summary claiming a null
    * deadline deletes it: `EditDeadlineOption.due_date` is `required`, so a null is a rejected request. Clearing is
    * [[edit]] with [[EditIssue.withoutDueDate]], which sends the separate `unset_due_date` flag Forgejo needs.
    *
    * '''Never retried''', because it is a `POST` and this library never repeats one. The end state would be identical —
    * the call sets an absolute date on a named issue — but the rule is the method, not the endpoint, and a caller who
    * knows their call is safe to repeat can re-issue it.
    *
    * '''Answers `201`''' with the deadline the instance now holds; see [[IssueDeadline]].
    *
    * '''Failures.''' The group contract above. A `422` carrying a raw Go parse error is what a timestamp Forgejo cannot
    * read produces (`docs/HAZARDS.md` §4), though [[com.worxbend.codeberg4s.issues.wire.WireInstant]] renders one it
    * can.
    */
  def setDeadline(owner: Owner, name: RepoName, number: IssueNumber, dueDate: Instant): Future[IssueDeadline] =
    pipeline.call(IssueApi.setDeadlineRequest(owner, name, number, dueDate), RetryEligibility.Never)(using
      IssueDecoders.deadline)

  /** Pins an issue to the top of the repository's issue list — `POST /repos/{owner}/{repo}/issues/{index}/pin`.
    *
    * '''Never retried''', because it is a `POST` and this library never repeats one. Repeating would be harmless — an
    * issue is either pinned or not — but the rule is the method; [[movePin]] is the call in this area that is retried,
    * and it says why.
    *
    * '''Answers `204`''', so there is nothing to return. Where the pinned issues can be '''read''' is
    * `GET /repos/{owner}/{repo}/issues/pinned`, which the repository group owns.
    *
    * '''Failures.''' The group contract above. A `403` is what exceeding the instance's limit on pinned issues looks
    * like, as well as an under-privileged token.
    */
  def pin(owner: Owner, name: RepoName, number: IssueNumber): Future[Unit] =
    pipeline.callUnit(IssueApi.pinRequest(owner, name, number), RetryEligibility.Never)

  /** Unpins an issue — `DELETE /repos/{owner}/{repo}/issues/{index}/pin`.
    *
    * '''Retried''', because the request names exactly one issue and asks for an absolute end state — that issue is not
    * pinned. Doing it twice leaves the repository exactly where doing it once would, nothing is created, and unlike
    * most deletes in this library a lost success does not turn into a `404`: the issue is still there, it simply has no
    * pin left to remove.
    *
    * '''Answers `204`''', so there is nothing to return.
    *
    * '''Failures.''' The group contract above.
    */
  def unpin(owner: Owner, name: RepoName, number: IssueNumber): Future[Unit] =
    pipeline.callUnit(IssueApi.unpinRequest(owner, name, number), RetryEligibility.AlwaysRetry)

  /** Moves a pinned issue to a given slot — `PATCH /repos/{owner}/{repo}/issues/{index}/pin/{position}`.
    *
    * '''The one `PATCH` in this class that is retried''', and the exception is deliberate. Every other `PATCH` here
    * carries a body that is applied to whatever the resource has become, so a repeat can overwrite somebody else's
    * change. This one carries '''no body at all''': the whole request is a URL naming one issue and one absolute
    * position, so the state after N attempts is the state after one, and nothing is created. That is the bar the class
    * note states, and this call meets it where [[edit]] does not.
    *
    * '''One-based''', because Forgejo's own `pin_order` is `0` for an unpinned issue; see [[PinPosition]].
    *
    * '''Answers `204`''', so there is nothing to return.
    *
    * '''Failures.''' The group contract above. A `404` covers an issue that is not pinned at all as well as one that
    * does not exist.
    */
  def movePin(owner: Owner, name: RepoName, number: IssueNumber, position: PinPosition): Future[Unit] =
    pipeline.callUnit(IssueApi.movePinRequest(owner, name, number, position), RetryEligibility.AlwaysRetry)

  /** Lists the issues this issue is blocking — `GET /repos/{owner}/{repo}/issues/{index}/blocks`.
    *
    * '''Read the direction carefully.''' These are the issues that cannot proceed until this one is done. The opposite
    * relation — what this issue is waiting on — is [[listDependencies]]. Forgejo stores one relation and serves both
    * ends of it, so adding a block here is the same edge as adding a dependency there, seen from the other side.
    *
    * '''Paging.''' As [[list]]: the `Link` header decides, not the number of items.
    *
    * '''Failures.''' The group contract above.
    */
  def listBlocks(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      page: PageParams,
  ): Future[Page[Issue]] =
    pipeline.callPage(IssueApi.listBlocksRequest(owner, name, number, page), page)(using IssueDecoders.issues)

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
    pipeline.call(IssueApi.addBlockRequest(owner, name, number, blocked), RetryEligibility.Never)(using
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
    pipeline.call(IssueApi.removeBlockRequest(owner, name, number, blocked), RetryEligibility.AlwaysRetry)(using
      IssueDecoders.issue)

  /** Lists the issues this issue is waiting on — `GET /repos/{owner}/{repo}/issues/{index}/dependencies`.
    *
    * The other end of the relation [[listBlocks]] reports; see that method for the direction.
    *
    * '''Paging.''' As [[list]]: the `Link` header decides, not the number of items.
    *
    * '''Failures.''' The group contract above.
    */
  def listDependencies(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      page: PageParams,
  ): Future[Page[Issue]] =
    pipeline.callPage(IssueApi.listDependenciesRequest(owner, name, number, page), page)(using IssueDecoders.issues)

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
    pipeline.call(IssueApi.addDependencyRequest(owner, name, number, blocker), RetryEligibility.Never)(using
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
    pipeline.call(IssueApi.removeDependencyRequest(owner, name, number, blocker), RetryEligibility.AlwaysRetry)(using
      IssueDecoders.issue)

  /** Lists everything that has happened to an issue — `GET /repos/{owner}/{repo}/issues/{index}/timeline`.
    *
    * '''Comments '''and''' events''', which is what makes this different from [[listComments]]: a label added, a
    * milestone changed, a title edited and a reference from another issue are all entries here, and each carries a
    * `type` a caller matches on. See [[TimelineEvent]] for why that discriminator is a raw `String` rather than an
    * enum, and for the one wire field that is deliberately not decoded.
    *
    * '''Paging.''' As [[list]]: the `Link` header decides, not the number of items.
    *
    * '''Failures.''' The group contract above, plus the `500` the spec declares for this operation. A `422` most often
    * means a malformed `since` or `before`.
    *
    * @param query
    *   the time window to restrict to; [[CommentQuery.Empty]] asks for the whole history
    */
  def timeline(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      query: CommentQuery,
      page: PageParams,
  ): Future[Page[TimelineEvent]] =
    pipeline.callPage(IssueApi.timelineRequest(owner, name, number, query, page), page)(using IssueDecoders.timeline)

/** The requests this group issues, its operation ids, and its typed rail. */
object IssueApi:

  /** The stable operation id [[IssueApi.list]] copies into every failure's [[com.worxbend.codeberg4s.CallContext]].
    * Safe to alert on.
    */
  val ListOperation: String = "issues.list"

  /** The stable operation id of the single-issue read on [[IssueApi]]. */
  val GetOperation: String = "issues.get"

  /** The stable operation id of [[IssueApi.create]]. */
  val CreateOperation: String = "issues.create"

  /** The stable operation id of [[IssueApi.edit]]. */
  val EditOperation: String = "issues.edit"

  /** The stable operation id of [[IssueApi.listComments]]. */
  val ListCommentsOperation: String = "issues.comments.list"

  /** The stable operation id of [[IssueApi.createComment]]. */
  val CreateCommentOperation: String = "issues.comments.create"

  /** The stable operation id of [[IssueApi.listLabels]]. */
  val ListLabelsOperation: String = "issues.labels.list"

  /** The stable operation id of [[IssueApi.createLabel]]. */
  val CreateLabelOperation: String = "issues.labels.create"

  /** The stable operation id of [[IssueApi.listMilestones]]. */
  val ListMilestonesOperation: String = "issues.milestones.list"

  /** The stable operation id of [[IssueApi.getMilestone]]. */
  val GetMilestoneOperation: String = "issues.milestones.get"

  /** The stable operation id of [[IssueApi.search]]. */
  val SearchOperation: String = "issues.search"

  /** The stable operation id of [[IssueApi.delete]]. */
  val DeleteOperation: String = "issues.delete"

  /** The stable operation id of [[IssueApi.setDeadline]]. */
  val SetDeadlineOperation: String = "issues.deadline.set"

  /** The stable operation id of [[IssueApi.pin]]. */
  val PinOperation: String = "issues.pin"

  /** The stable operation id of [[IssueApi.unpin]]. */
  val UnpinOperation: String = "issues.unpin"

  /** The stable operation id of [[IssueApi.movePin]]. */
  val MovePinOperation: String = "issues.pin.move"

  /** The stable operation id of [[IssueApi.listBlocks]]. */
  val ListBlocksOperation: String = "issues.blocks.list"

  /** The stable operation id of [[IssueApi.addBlock]]. */
  val AddBlockOperation: String = "issues.blocks.add"

  /** The stable operation id of [[IssueApi.removeBlock]]. */
  val RemoveBlockOperation: String = "issues.blocks.remove"

  /** The stable operation id of [[IssueApi.listDependencies]]. */
  val ListDependenciesOperation: String = "issues.dependencies.list"

  /** The stable operation id of [[IssueApi.addDependency]]. */
  val AddDependencyOperation: String = "issues.dependencies.add"

  /** The stable operation id of [[IssueApi.removeDependency]]. */
  val RemoveDependencyOperation: String = "issues.dependencies.remove"

  /** The stable operation id of [[IssueApi.timeline]]. */
  val TimelineOperation: String = "issues.timeline.list"

  /** The typed rail of [[IssueApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.issues.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: IssueApi)(using exec: Exec[Future]):

    /** [[IssueApi.list]] with its failure as a value. */
    def list(
        owner: Owner,
        name: RepoName,
        query: IssueQuery,
        page: PageParams,
    ): Future[Either[CodebergError, Page[Issue]]] =
      exec.attempt(rail.list(owner, name, query, page))

    /** The single-issue read on [[IssueApi]], with its failure as a value. */
    def get(owner: Owner, name: RepoName, number: IssueNumber): Future[Either[CodebergError, Issue]] =
      exec.attempt(rail.get(owner, name, number))

    /** [[IssueApi.create]] with its failure as a value. */
    def create(owner: Owner, name: RepoName, command: CreateIssue): Future[Either[CodebergError, Issue]] =
      exec.attempt(rail.create(owner, name, command))

    /** [[IssueApi.edit]] with its failure as a value. */
    def edit(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        command: EditIssue,
    ): Future[Either[CodebergError, Issue]] =
      exec.attempt(rail.edit(owner, name, number, command))

    /** [[IssueApi.listComments]] with its failure as a value. */
    def listComments(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        page: PageParams,
    ): Future[Either[CodebergError, Page[Comment]]] =
      exec.attempt(rail.listComments(owner, name, number, page))

    /** [[IssueApi.createComment]] with its failure as a value. */
    def createComment(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        command: CreateComment,
    ): Future[Either[CodebergError, Comment]] =
      exec.attempt(rail.createComment(owner, name, number, command))

    /** [[IssueApi.listLabels]] with its failure as a value. */
    def listLabels(owner: Owner, name: RepoName, page: PageParams): Future[Either[CodebergError, Page[Label]]] =
      exec.attempt(rail.listLabels(owner, name, page))

    /** [[IssueApi.createLabel]] with its failure as a value. */
    def createLabel(owner: Owner, name: RepoName, command: CreateLabel): Future[Either[CodebergError, Label]] =
      exec.attempt(rail.createLabel(owner, name, command))

    /** [[IssueApi.listMilestones]] with its failure as a value. */
    def listMilestones(
        owner: Owner,
        name: RepoName,
        state: StateFilter,
        page: PageParams,
    ): Future[Either[CodebergError, Page[Milestone]]] =
      exec.attempt(rail.listMilestones(owner, name, state, page))

    /** [[IssueApi.getMilestone]] with its failure as a value. */
    def getMilestone(owner: Owner, name: RepoName, id: MilestoneId): Future[Either[CodebergError, Milestone]] =
      exec.attempt(rail.getMilestone(owner, name, id))

    /** [[IssueApi.search]] with its failure as a value. */
    def search(query: IssueSearchQuery, page: PageParams): Future[Either[CodebergError, Page[Issue]]] =
      exec.attempt(rail.search(query, page))

    /** [[IssueApi.delete]] with its failure as a value. */
    def delete(owner: Owner, name: RepoName, number: IssueNumber): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.delete(owner, name, number))

    /** [[IssueApi.setDeadline]] with its failure as a value. */
    def setDeadline(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        dueDate: Instant,
    ): Future[Either[CodebergError, IssueDeadline]] =
      exec.attempt(rail.setDeadline(owner, name, number, dueDate))

    /** [[IssueApi.pin]] with its failure as a value. */
    def pin(owner: Owner, name: RepoName, number: IssueNumber): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.pin(owner, name, number))

    /** [[IssueApi.unpin]] with its failure as a value. */
    def unpin(owner: Owner, name: RepoName, number: IssueNumber): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.unpin(owner, name, number))

    /** [[IssueApi.movePin]] with its failure as a value. */
    def movePin(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        position: PinPosition,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.movePin(owner, name, number, position))

    /** [[IssueApi.listBlocks]] with its failure as a value. */
    def listBlocks(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        page: PageParams,
    ): Future[Either[CodebergError, Page[Issue]]] =
      exec.attempt(rail.listBlocks(owner, name, number, page))

    /** [[IssueApi.addBlock]] with its failure as a value. */
    def addBlock(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        blocked: IssueRef,
    ): Future[Either[CodebergError, Issue]] =
      exec.attempt(rail.addBlock(owner, name, number, blocked))

    /** [[IssueApi.removeBlock]] with its failure as a value. */
    def removeBlock(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        blocked: IssueRef,
    ): Future[Either[CodebergError, Issue]] =
      exec.attempt(rail.removeBlock(owner, name, number, blocked))

    /** [[IssueApi.listDependencies]] with its failure as a value. */
    def listDependencies(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        page: PageParams,
    ): Future[Either[CodebergError, Page[Issue]]] =
      exec.attempt(rail.listDependencies(owner, name, number, page))

    /** [[IssueApi.addDependency]] with its failure as a value. */
    def addDependency(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        blocker: IssueRef,
    ): Future[Either[CodebergError, Issue]] =
      exec.attempt(rail.addDependency(owner, name, number, blocker))

    /** [[IssueApi.removeDependency]] with its failure as a value. */
    def removeDependency(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        blocker: IssueRef,
    ): Future[Either[CodebergError, Issue]] =
      exec.attempt(rail.removeDependency(owner, name, number, blocker))

    /** [[IssueApi.timeline]] with its failure as a value. */
    def timeline(
        owner: Owner,
        name: RepoName,
        number: IssueNumber,
        query: CommentQuery,
        page: PageParams,
    ): Future[Either[CodebergError, Page[TimelineEvent]]] =
      exec.attempt(rail.timeline(owner, name, number, query, page))

  private def searchRequest(query: IssueSearchQuery, page: PageParams): CodebergRequest =
    IssueRequests.read(
      SearchOperation,
      List("repos", "issues", "search"),
      IssueQueries.search(query) ++ IssueQueries.paging(page),
    )

  private def deleteRequest(owner: Owner, name: RepoName, number: IssueNumber): CodebergRequest =
    IssueRequests.remove(DeleteOperation, issuePath(owner, name, number))

  private def setDeadlineRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      dueDate: Instant,
  ): CodebergRequest =
    IssueRequests.write(
      SetDeadlineOperation,
      HttpMethod.Post,
      issuePath(owner, name, number) :+ "deadline",
      EditDeadlineOptionDto.render(dueDate),
    )

  private def pinRequest(owner: Owner, name: RepoName, number: IssueNumber): CodebergRequest =
    bodiless(PinOperation, HttpMethod.Post, pinPath(owner, name, number))

  private def unpinRequest(owner: Owner, name: RepoName, number: IssueNumber): CodebergRequest =
    IssueRequests.remove(UnpinOperation, pinPath(owner, name, number))

  private def movePinRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      position: PinPosition,
  ): CodebergRequest =
    bodiless(MovePinOperation, HttpMethod.Patch, pinPath(owner, name, number) :+ position.value.toString)

  private def listBlocksRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      page: PageParams,
  ): CodebergRequest =
    IssueRequests.read(ListBlocksOperation, blocksPath(owner, name, number), IssueQueries.paging(page))

  private def addBlockRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      blocked: IssueRef,
  ): CodebergRequest =
    IssueRequests.write(
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
    IssueRequests.removeWithBody(
      RemoveBlockOperation,
      blocksPath(owner, name, number),
      IssueMetaDto.render(blocked),
    )

  private def listDependenciesRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      page: PageParams,
  ): CodebergRequest =
    IssueRequests.read(ListDependenciesOperation, dependenciesPath(owner, name, number), IssueQueries.paging(page))

  private def addDependencyRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      blocker: IssueRef,
  ): CodebergRequest =
    IssueRequests.write(
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
    IssueRequests.removeWithBody(
      RemoveDependencyOperation,
      dependenciesPath(owner, name, number),
      IssueMetaDto.render(blocker),
    )

  private def timelineRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      query: CommentQuery,
      page: PageParams,
  ): CodebergRequest =
    IssueRequests.read(
      TimelineOperation,
      issuePath(owner, name, number) :+ "timeline",
      IssueQueries.comments(query) ++ IssueQueries.paging(page),
    )

  /** A mutating call with no payload at all, which pinning and moving a pin both are. */
  private def bodiless(operation: String, method: HttpMethod, path: List[String]): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = method,
      path      = path,
      query     = Nil,
      headers   = Nil,
      body      = None,
    )

  private def pinPath(owner: Owner, name: RepoName, number: IssueNumber): List[String] =
    issuePath(owner, name, number) :+ "pin"

  private def blocksPath(owner: Owner, name: RepoName, number: IssueNumber): List[String] =
    issuePath(owner, name, number) :+ "blocks"

  private def dependenciesPath(owner: Owner, name: RepoName, number: IssueNumber): List[String] =
    issuePath(owner, name, number) :+ "dependencies"

  private def listRequest(owner: Owner, name: RepoName, query: IssueQuery, page: PageParams): CodebergRequest =
    read(ListOperation, issuesPath(owner, name), IssueQueries.issues(query) ++ IssueQueries.paging(page))

  private def getRequest(owner: Owner, name: RepoName, number: IssueNumber): CodebergRequest =
    read(GetOperation, issuePath(owner, name, number), Nil)

  private def createRequest(owner: Owner, name: RepoName, command: CreateIssue): CodebergRequest =
    write(CreateOperation, HttpMethod.Post, issuesPath(owner, name), CreateIssueOptionDto.render(command))

  private def editRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      command: EditIssue,
  ): CodebergRequest =
    write(EditOperation, HttpMethod.Patch, issuePath(owner, name, number), EditIssueOptionDto.render(command))

  private def listCommentsRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      page: PageParams,
  ): CodebergRequest =
    read(ListCommentsOperation, issuePath(owner, name, number) :+ "comments", IssueQueries.paging(page))

  private def createCommentRequest(
      owner: Owner,
      name: RepoName,
      number: IssueNumber,
      command: CreateComment,
  ): CodebergRequest =
    write(
      CreateCommentOperation,
      HttpMethod.Post,
      issuePath(owner, name, number) :+ "comments",
      CreateIssueCommentOptionDto.render(command),
    )

  private def listLabelsRequest(owner: Owner, name: RepoName, page: PageParams): CodebergRequest =
    read(ListLabelsOperation, labelsPath(owner, name), IssueQueries.paging(page))

  private def createLabelRequest(owner: Owner, name: RepoName, command: CreateLabel): CodebergRequest =
    write(CreateLabelOperation, HttpMethod.Post, labelsPath(owner, name), CreateLabelOptionDto.render(command))

  private def listMilestonesRequest(
      owner: Owner,
      name: RepoName,
      state: StateFilter,
      page: PageParams,
  ): CodebergRequest =
    read(
      ListMilestonesOperation,
      milestonesPath(owner, name),
      IssueQueries.milestones(state) ++ IssueQueries.paging(page),
    )

  private def getMilestoneRequest(owner: Owner, name: RepoName, id: MilestoneId): CodebergRequest =
    read(GetMilestoneOperation, milestonesPath(owner, name) :+ id.value.toString, Nil)

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

  private def issuesPath(owner: Owner, name: RepoName): List[String] =
    List("repos", owner.value, name.value, "issues")

  private def issuePath(owner: Owner, name: RepoName, number: IssueNumber): List[String] =
    issuesPath(owner, name) :+ number.value.toString

  private def labelsPath(owner: Owner, name: RepoName): List[String] =
    List("repos", owner.value, name.value, "labels")

  private def milestonesPath(owner: Owner, name: RepoName): List[String] =
    List("repos", owner.value, name.value, "milestones")

  private val IssueDecoder: Decode[Issue] =
    WireDecode.of(Json.decoder[IssueDto])(_.toDomain)

  private val IssuesDecoder: Decode[Vector[Issue]] =
    WireDecode.of(Json.decoder[Vector[IssueDto]])(dtos => IssueDto.toDomainAll(JsonPath.Root, dtos))

  private val CommentDecoder: Decode[Comment] =
    WireDecode.of(Json.decoder[CommentDto])(_.toDomain)

  private val CommentsDecoder: Decode[Vector[Comment]] =
    WireDecode.of(Json.decoder[Vector[CommentDto]])(dtos => CommentDto.toDomainAll(JsonPath.Root, dtos))

  private val LabelDecoder: Decode[Label] =
    WireDecode.of(Json.decoder[LabelDto])(_.toDomain)

  private val LabelsDecoder: Decode[Vector[Label]] =
    WireDecode.of(Json.decoder[Vector[LabelDto]])(dtos => LabelDto.toDomainAll(JsonPath.Root, dtos))

  private val MilestoneDecoder: Decode[Milestone] =
    WireDecode.of(Json.decoder[MilestoneDto])(_.toDomain)

  private val MilestonesDecoder: Decode[Vector[Milestone]] =
    WireDecode.of(Json.decoder[Vector[MilestoneDto]])(dtos => MilestoneDto.toDomainAll(JsonPath.Root, dtos))
