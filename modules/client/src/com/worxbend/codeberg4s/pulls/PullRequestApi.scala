package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.ArrayElements
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.CodebergRequest.read
import com.worxbend.codeberg4s.core.CodebergRequest.remove
import com.worxbend.codeberg4s.core.CodebergRequest.write
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.RequestBody
import com.worxbend.codeberg4s.core.RetryEligibility
import com.worxbend.codeberg4s.miscellaneous.PlainText
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.pulls.wire.ChangedFileDto
import com.worxbend.codeberg4s.pulls.wire.CreatePullRequestOptionDto
import com.worxbend.codeberg4s.pulls.wire.CreatePullReviewOptionsDto
import com.worxbend.codeberg4s.pulls.wire.DismissPullReviewOptionsDto
import com.worxbend.codeberg4s.pulls.wire.EditPullRequestOptionDto
import com.worxbend.codeberg4s.pulls.wire.MergePullRequestOptionDto
import com.worxbend.codeberg4s.pulls.wire.NewReviewCommentDto
import com.worxbend.codeberg4s.pulls.wire.PullRequestDto
import com.worxbend.codeberg4s.pulls.wire.PullRequestQueries
import com.worxbend.codeberg4s.pulls.wire.PullReviewRequestOptionsDto
import com.worxbend.codeberg4s.pulls.wire.ReviewCommentDto
import com.worxbend.codeberg4s.pulls.wire.ReviewDto
import com.worxbend.codeberg4s.pulls.wire.SubmitPullReviewOptionsDto
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.Commit
import com.worxbend.codeberg4s.repositories.wire.CommitDto

import scala.concurrent.Future

/** Pull-request endpoints, together with the reviews, commits and changed files that hang off them.
  *
  * Reached as `client.pulls`. Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[PullRequestApi.attempt]] never fail and
  * return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository or the pull request does not
  *     exist '''or''' is private to credentials the client does not have — Forgejo does not distinguish the two, on
  *     purpose — `401` when a token was required and none was sent, and `403` when the token lacks the scope. `422`
  *     '''and''' `400` both mean the request was rejected as invalid: `docs/HAZARDS.md` §4 captured Forgejo using `400`
  *     for a malformed identifier and `422` for a malformed timestamp, so a caller checking only for `422` will miss
  *     half of them.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type — [[com.worxbend.codeberg4s.Owner]], [[PullRequestNumber]], [[PullRequestHead]],
  * [[com.worxbend.codeberg4s.repositories.CommitSha]] — so a value that would forge a path or a query parameter is
  * rejected by its own smart constructor before a client is ever involved.
  *
  * ==Retries==
  *
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]. Every write here uses
  * [[com.worxbend.codeberg4s.core.RetryEligibility.Never]], and for [[merge]] that is the most consequential decision
  * in this class — see its own note.
  *
  * ==Paging==
  *
  * Only [[list]] gets a `Link` header. `golden/MANIFEST.md` records that `/pulls/{n}/reviews`, `/pulls/{n}/commits` and
  * `/pulls/{n}/files` send `X-Total-Count` and '''no''' `Link`, so the pages those three return always report
  * themselves as the last one. Each method below says so again where it matters.
  *
  * The review-and-reviewer operations added after those five are not paged '''at all''': `/pulls/pinned`,
  * `/pulls/{n}/requested_reviewers` and `/pulls/{n}/reviews/{id}/comments` declare no `page` or `limit` parameter in
  * the pinned spec, so they return a plain `Vector` rather than a [[com.worxbend.codeberg4s.paging.Page]] that would
  * have nothing to report. A `Vector` here is an honest "the instance sends the whole collection", not an unbounded
  * listing this library forgot to page.
  *
  * ==Evidence==
  *
  * The eight operations this class started with are checked against golden captures. Most of what was added after them
  * is '''not''': `golden/MANIFEST.md` holds no review-comment payload — every review the anonymous harvest could reach
  * carried `comments_count: 0` — and none of the write endpoints can be exercised without credentials. Those models and
  * bodies are derived from `spec/swagger.v1.json`, and each says so on its own type rather than implying a measured
  * shape. Should a capture ever contradict one, the capture wins.
  */
final class PullRequestApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: PullRequestApi.Attempt = PullRequestApi.Attempt(this)

  /** The status `GET /pulls/{index}/merge` answers with when the pull request is '''not''' merged.
    *
    * A `404` from that one endpoint is an answer rather than a failure, and [[isMerged]] is the only place in this
    * library that reads a status that way. Named rather than written inline so the pattern below says why it exists.
    */
  private val NotMerged: Int = 404

  /** Lists a repository's pull requests — `GET /repos/{owner}/{repo}/pulls`.
    *
    * '''This is not the issue listing.''' `GET /repos/{owner}/{repo}/issues` also returns pull requests, but as
    * issue-shaped projections without a base, a head or a merge; this endpoint returns the real thing. Going the other
    * way, a pull request's ordinary comments live on the '''issue''' endpoints, because Forgejo stores them there.
    *
    * '''`state = closed` includes merged pull requests.''' Forgejo has three lifecycle outcomes and two `state`
    * spellings — `golden/pull/list-closed.json` holds one merged pull request beside two rejected ones — so a caller
    * who wants only rejections filters the result on [[PullRequestState]] rather than on the query.
    *
    * '''Paging.''' Ends where the response's `Link` header says it ends, never where a short page suggests it does —
    * Forgejo clamps `limit` to its own maximum while echoing the requested value, so `items.size < limit` fires on page
    * one of thirty-two (`docs/HAZARDS.md` §5). A page past the end is `200` with `[]`, not a `404`.
    *
    * '''Failures.''' The group contract above. This operation also declares `500`, which arrives as
    * [[com.worxbend.codeberg4s.CodebergError.Api]] like any other status.
    *
    * @param query
    *   the filters to apply; [[PullRequestQuery.Empty]] asks for the instance's default, which is open pull requests
    *   only
    * @param page
    *   which window to fetch, and how large
    */
  def list(owner: Owner, name: RepoName, query: PullRequestQuery, params: PageParams): Future[Page[PullRequest]] =
    pipeline.callPage(PullRequestApi.listRequest(owner, name, query, params), params)(using PullRequestApi.PullsDecoder)

  /** Reads one pull request — `GET /repos/{owner}/{repo}/pulls/{index}`.
    *
    * '''Worth preferring over the listing when the merge matters.''' `merged_by` is populated here and `null` on the
    * listing for the very same pull request — `golden/pull/single-merged.json` against `golden/pull/list-closed.json` —
    * so a caller that needs to know who merged something has to read it individually.
    *
    * '''Failures.''' The group contract above. `404` covers "no such pull request in this repository", "no such
    * repository", and "that index is a plain issue, not a pull request"; the response body's `errors` array is the only
    * thing that distinguishes them, and it is not always present.
    *
    * @param number
    *   the per-repository index, '''not''' [[PullRequest.id]] — see [[PullRequestNumber]]
    */
  def get(owner: Owner, name: RepoName, number: PullRequestNumber): Future[PullRequest] =
    pipeline.call(PullRequestApi.getRequest(owner, name, number), RetryEligibility.IdempotentOnly)(using
      PullRequestApi.PullDecoder)

  /** Opens a pull request — `POST /repos/{owner}/{repo}/pulls`.
    *
    * '''Never retried.''' Repeating this call opens a second pull request, and Forgejo has no idempotency key that
    * would let the instance recognise the repeat. A transport failure therefore leaves the caller genuinely unsure
    * whether the pull request exists, which is the honest state of affairs and better than two of them.
    *
    * '''Failures.''' The group contract above, plus `409` when a pull request for that head and base is already open —
    * which is also what a blind retry runs into — `413` when the diff exceeds the instance's limit, and `423` when the
    * repository is archived. A `422` means Forgejo rejected the payload: an unknown head branch, a base that does not
    * exist, or a head and base that are the same.
    *
    * @param command
    *   what to open; built from [[CreatePullRequest.of]], which has already rejected a blank title
    */
  def create(owner: Owner, name: RepoName, command: CreatePullRequest): Future[PullRequest] =
    pipeline.call(PullRequestApi.createRequest(owner, name, command), RetryEligibility.Never)(using
      PullRequestApi.PullDecoder)

  /** Edits a pull request — `PATCH /repos/{owner}/{repo}/pulls/{index}`.
    *
    * '''Never retried''', even though `PATCH` on a specific resource looks idempotent. It is not, here: the body is a
    * partial update applied to whatever the pull request has become, so a repeat after a transport failure can
    * overwrite a change someone else made in between.
    *
    * '''Only what the command sets is sent'''; everything else keeps its current value. Clearing a deadline is
    * [[EditPullRequest.withoutDueDate]], and clearing labels or assignees is the corresponding builder with an empty
    * vector — see [[EditPullRequest]] for why those are different from doing nothing.
    *
    * '''This cannot merge anything.''' [[EditPullRequest.close]] closes a pull request without merging it; merging is
    * [[merge]] and a different endpoint. Reopening a '''merged''' pull request is refused with a `422`.
    *
    * '''Failures.''' The group contract above, plus `409` and `412` when the pull request moved under the edit. Forgejo
    * answers a successful edit with `201`, not `200`; both are success as far as
    * [[com.worxbend.codeberg4s.core.StatusMapping]] is concerned.
    */
  def edit(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      command: EditPullRequest,
  ): Future[PullRequest] =
    pipeline.call(PullRequestApi.editRequest(owner, name, number, command), RetryEligibility.Never)(using
      PullRequestApi.PullDecoder)

  /** Merges a pull request — `POST /repos/{owner}/{repo}/pulls/{index}/merge`.
    *
    * '''The most destructive operation in this library.''' It writes to the repository's default history, and with
    * [[MergePullRequest.deletingSourceBranch]] it deletes a branch as well. Read the next two paragraphs before calling
    * it from anything automated.
    *
    * ==Never retried, and what a retry would do==
    *
    * This call uses [[com.worxbend.codeberg4s.core.RetryEligibility.Never]], so a `503`, a read timeout or a dropped
    * connection is reported to the caller and not repeated. That is deliberate, because the request is '''not'''
    * idempotent and the failure mode is silent:
    *
    *   - a merge that succeeded on the instance and whose response was lost would, on a repeat, be answered `405`
    *     ("pull request is already merged") — which a caller would read as the merge having failed;
    *   - worse, if the branch moved between the two attempts — a maintainer pushes, an auto-update runs — the second
    *     attempt merges commits the caller never saw and never approved. Forgejo has no idempotency key that would let
    *     it recognise the repeat, so nothing on the server side prevents this;
    *   - with [[MergePullRequest.deletingSourceBranch]], the repeat can also delete a branch that had been recreated in
    *     between.
    *
    * A caller who wants the retry anyway should make it safe first with [[MergePullRequest.expecting]], which sends
    * `head_commit_id` and makes Forgejo refuse the merge if the head has moved, and then re-issue the call themselves.
    * That is a decision this library will not make on anyone's behalf.
    *
    * ==What success means==
    *
    * The endpoint answers `200` with an empty body, so this returns `Unit` rather than the merged pull request; read it
    * back with [[get]] if the merged state is needed. And a success does '''not''' always mean "merged now": with
    * [[MergePullRequest.whenChecksSucceed]] Forgejo records a scheduled merge and answers successfully, then merges
    * later or never.
    *
    * '''Failures.''' The group contract above, plus `405` when the merge is refused — the pull request is already
    * merged, is a draft, has conflicts, fails a required check, or asks for a [[MergeStyle]] the repository forbids —
    * `409` when the head moved and [[MergePullRequest.expecting]] caught it, `413` when the diff is too large, and
    * `423` when the repository or the pull request is locked. A `405` is the ordinary "no" here and deserves handling,
    * not logging.
    */
  def merge(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      command: MergePullRequest,
  ): Future[Unit] =
    pipeline.callUnit(PullRequestApi.mergeRequest(owner, name, number, command), RetryEligibility.Never)

  /** Lists a pull request's reviews — `GET /repos/{owner}/{repo}/pulls/{index}/reviews`.
    *
    * '''The listing contains review requests.''' Asking someone to review produces a row here with state
    * [[ReviewState.RequestReview]] and no commit — two of the three rows of `golden/pull/reviews-list.json` are of that
    * kind — so a caller counting approvals filters on [[Review.state]] rather than counting rows.
    *
    * '''Paging is weaker here than on [[list]], and a caller has to know it.''' `golden/MANIFEST.md` records that this
    * endpoint sends `X-Total-Count` but '''no''' `Link` header. `page` and `limit` are still sent, because the spec
    * declares them and they cost nothing, but two things follow:
    *
    *   - the returned page always reports itself as the last one, since `nextPage` is read from `rel="next"` and there
    *     is no `Link` header to read it from;
    *   - an instance that ignores the parameters answers with the '''complete''' review list, so asking for page two
    *     may return the same rows as page one rather than nothing.
    *
    * Compare [[Page.totalCount]] with [[Page.size]] to find out which happened.
    *
    * '''Failures.''' The group contract above.
    */
  def listReviews(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      params: PageParams,
  ): Future[Page[Review]] =
    pipeline.callPage(PullRequestApi.reviewsRequest(owner, name, number, params), params)(using
      PullRequestApi.ReviewsDecoder)

  /** Lists the commits a pull request would bring — `GET /repos/{owner}/{repo}/pulls/{index}/commits`.
    *
    * The elements are the repository wave's [[com.worxbend.codeberg4s.repositories.Commit]], the same model
    * `GET /repos/{owner}/{repo}/commits` returns, reused rather than forked per `docs/LEDGER.md`. Note the two views of
    * authorship it carries: `author` and `committer` are instance accounts, while `details.author` and
    * `details.committer` are what the Git objects record, and on a backported commit they differ —
    * `golden/pull/commits-list.json` shows a commit written by `erik` and committed by `forgejo-backport-action`.
    *
    * '''`files` and `stats` are empty here.''' The endpoint accepts `files=true` and `verification=true` query
    * parameters to populate them; this library does not send either, because both multiply the response size and
    * neither is what a caller listing commits usually wants. An empty
    * [[com.worxbend.codeberg4s.repositories.Commit.files]] therefore means "not requested", not "touched nothing" — the
    * changed files are [[listFiles]].
    *
    * '''Paging.''' As [[listReviews]]: `X-Total-Count` but no `Link`, so the page always reports itself as the last
    * one.
    *
    * '''Failures.''' The group contract above.
    */
  def listCommits(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      params: PageParams,
  ): Future[Page[Commit]] =
    pipeline.callPage(PullRequestApi.commitsRequest(owner, name, number, params), params)(using
      PullRequestApi.CommitsDecoder)

  /** Lists the files a pull request changes — `GET /repos/{owner}/{repo}/pulls/{index}/files`.
    *
    * Counts and URLs, never diff text: the patch is a separate, non-JSON document (`GET
    * /repos/{owner}/{repo}/pulls/{index}.diff`) that this library does not fetch. See [[ChangedFile]].
    *
    * The endpoint also accepts `skip-to` and `whitespace` parameters, which this library does not send. `skip-to`
    * resumes a listing from a named path rather than from a page number, and `whitespace` changes how the instance
    * computes the counts; both would need a modelled type of their own, and neither has a fixture to model it from.
    *
    * '''Paging.''' As [[listReviews]]: `X-Total-Count` but no `Link`, so the page always reports itself as the last
    * one. Compare [[Page.totalCount]] with [[PullRequest.changedFileCount]] to tell whether the listing is complete.
    *
    * '''Failures.''' The group contract above.
    */
  def listFiles(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      params: PageParams,
  ): Future[Page[ChangedFile]] =
    pipeline.callPage(PullRequestApi.filesRequest(owner, name, number, params), params)(using
      PullRequestApi.FilesDecoder)

  /** Lists the repository's pinned pull requests — `GET /repos/{owner}/{repo}/pulls/pinned`.
    *
    * Pinning is a per-repository shortlist a maintainer curates; Forgejo caps it at a handful of entries, which is why
    * the endpoint declares no `page` and no `limit` and why this returns a `Vector` rather than a
    * [[com.worxbend.codeberg4s.paging.Page]]. The elements are ordinary [[PullRequest]] values, the same model [[list]]
    * returns.
    *
    * '''An empty result is the normal case.''' Most repositories pin nothing, and Forgejo answers that with `200` and
    * `[]` rather than with a `404` — so an empty vector says "nothing is pinned", never "no such repository".
    *
    * '''Failures.''' The group contract above.
    */
  def listPinned(owner: Owner, name: RepoName): Future[Vector[PullRequest]] =
    pipeline.call(PullRequestApi.pinnedRequest(owner, name), RetryEligibility.IdempotentOnly)(using
      PullRequestApi.PullsDecoder)

  /** Finds the pull request between two branches — `GET /repos/{owner}/{repo}/pulls/{base}/{head}`.
    *
    * The one way to reach a pull request without knowing its [[PullRequestNumber]]: given the branches, Forgejo answers
    * with the pull request that has them, which is what a CI job holding a branch name and nothing else needs.
    *
    * '''It finds one pull request, not all of them.''' A repository can have several pull requests for the same branch
    * pair over its lifetime — one merged, one closed, one open — and this endpoint returns whichever Forgejo considers
    * current rather than a listing. A caller who needs the history filters [[list]] with [[PullRequestQuery.withBase]]
    * and [[PullRequestQuery.withHead]] instead.
    *
    * '''Failures.''' The group contract above. `404` also covers "those branches exist but no pull request joins them",
    * which is an ordinary outcome here rather than a sign of a bad request.
    *
    * @param base
    *   the branch merged '''into''', always a branch of this repository
    * @param head
    *   the branch merged '''from''', in the one spelling Forgejo accepts — `branch` for this repository and
    *   `owner:branch` for a fork; see [[PullRequestHead]]
    */
  def getByBaseHead(
      owner: Owner,
      name: RepoName,
      base: BranchName,
      head: PullRequestHead,
  ): Future[PullRequest] =
    pipeline.call(PullRequestApi.baseHeadRequest(owner, name, base, head), RetryEligibility.IdempotentOnly)(using
      PullRequestApi.PullDecoder)

  /** Fetches a pull request's diff or patch — `GET /repos/{owner}/{repo}/pulls/{index}.{diffType}`.
    *
    * '''The one operation in this group whose response is not JSON.''' Forgejo answers `text/plain`, so the body is
    * handed back verbatim through [[com.worxbend.codeberg4s.miscellaneous.PlainText]] and nothing is parsed. It follows
    * that this method can never produce [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] — there is nothing to
    * decode — and that an empty `String` is a legitimate success for a pull request that changes nothing.
    *
    * '''Which document arrives is [[DiffFormat]]'s decision, and it matters''': a `diff` applies with `git apply` and
    * discards authorship, a `patch` applies with `git am` and preserves it. [[DiffRequest.includingBinary]] embeds
    * binary changes, which is what makes a `diff` complete enough to apply — and what can turn a two-kilobyte response
    * into a megabyte one.
    *
    * '''Whole response in memory.''' The body is materialised as a `String` like every other response in this library,
    * so a pull request touching thousands of files produces a correspondingly large value. There is no streaming
    * variant; a caller expecting that size should fetch the commits and files instead.
    *
    * '''Failures.''' The group contract above, minus the decoding case.
    */
  def download(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      request: DiffRequest,
  ): Future[String] =
    pipeline.call(PullRequestApi.downloadRequest(owner, name, number, request), RetryEligibility.IdempotentOnly)(using
      PlainText.decoder)

  /** Asks whether a pull request has been merged — `GET /repos/{owner}/{repo}/pulls/{index}/merge`.
    *
    * '''This endpoint answers with a status, not a body.''' `204` means merged and `404` means not merged, and there is
    * no payload either way. So this is the one operation in the library that reads a `404` as an answer rather than as
    * a failure: it is turned into `false` on the success channel, and every other status keeps travelling on the error
    * channel exactly as it would elsewhere.
    *
    * '''The `404` is genuinely ambiguous, and the ambiguity is Forgejo's.''' The same status answers "this pull request
    * is open", "there is no pull request with that number" and "there is no such repository". A caller who needs to
    * tell them apart reads the pull request with [[get]], which distinguishes them; a caller who only wants to know
    * whether to skip a merge does not care.
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]] like any other `GET`. A `404`
    * is not a retryable status ([[com.worxbend.codeberg4s.core.StatusMapping.RetryableStatuses]]), so the `false`
    * answer is never reached by way of an exhausted retry budget.
    *
    * '''Failures.''' The group contract above, minus `404` and minus the decoding case — nothing is parsed. `401` and
    * `403` still arrive as [[com.worxbend.codeberg4s.CodebergError.Api]], so an unreadable repository does not
    * masquerade as an unmerged pull request.
    */
  def isMerged(owner: Owner, name: RepoName, number: PullRequestNumber): Future[Boolean] =
    val probe = pipeline.callUnit(
      PullRequestApi.mergeStatusRequest(owner, name, number),
      RetryEligibility.IdempotentOnly,
    )

    exec.flatMap(exec.attempt(probe)):
      case Right(_)                                 => exec.pure(true)
      case Left(CodebergError.Api(_, NotMerged, _)) => exec.pure(false)
      case Left(error)                              => exec.raise(error)

  /** Cancels a merge that was scheduled but has not happened — `DELETE /repos/{owner}/{repo}/pulls/{index}/merge`.
    *
    * The counterpart to [[MergePullRequest.whenChecksSucceed]]. That variant makes [[merge]] answer successfully while
    * merging nothing; this withdraws the standing instruction, so the pull request stops waiting for its checks and
    * stays open.
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], which is unusual for a mutating
    * method here and is justified rather than assumed. The request names one pull request and carries no body, its end
    * state is "nothing is scheduled" however many times it arrives, and — unlike [[merge]] — repeating it cannot write
    * to a branch. The one cost of a repeat is that a second attempt after a lost `204` answers `404`; that reads as
    * "there was nothing scheduled", which is true by then.
    *
    * '''Answers `204` with no body''', so nothing is decoded.
    *
    * '''Failures.''' The group contract above, plus `423` when the repository is archived. A `404` means either that no
    * merge was scheduled or that the pull request does not exist, and Forgejo does not distinguish them.
    */
  def cancelScheduledMerge(owner: Owner, name: RepoName, number: PullRequestNumber): Future[Unit] =
    pipeline.callUnit(PullRequestApi.cancelMergeRequest(owner, name, number), RetryEligibility.AlwaysRetry)

  /** Brings a pull request's branch up to date with its base — `POST /repos/{owner}/{repo}/pulls/{index}/update`.
    *
    * '''This writes to the head branch''', and with [[UpdateStyle.Rebase]] it '''force-pushes''' it: every commit gets
    * a new sha, every existing checkout is orphaned, and every review pinned to one of the old commits points at
    * history that no longer exists. [[UpdateStyle.Merge]] adds a merge commit and leaves the existing shas alone. There
    * is no third option and no instance default worth deferring to — Forgejo's handler treats anything that is not the
    * literal `rebase` as a merge — which is why naming one is mandatory here.
    *
    * '''Never retried.''' The call creates a commit or rewrites a branch, and Forgejo offers no idempotency key that
    * would let it recognise a repeat. A rebase sent twice rewrites the branch twice, off a base that may have moved in
    * between; a merge sent twice can leave two merge commits. A transport failure therefore leaves the caller unsure
    * whether the branch moved, which is the honest state of affairs — read it back with [[get]] and compare
    * [[PullRequest.head]].
    *
    * '''Answers `200` with an empty body''', so this returns `Unit` rather than the updated pull request.
    *
    * '''Failures.''' The group contract above, plus `403` when the credentials may not write to the head branch — which
    * includes the common case of a pull request from a fork whose owner did not allow maintainer edits — `409` when the
    * update cannot be performed because the branches conflict, and `413` when the result would exceed the instance's
    * quota.
    */
  def updateBranch(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      style: UpdateStyle,
  ): Future[Unit] =
    pipeline.callUnit(PullRequestApi.updateBranchRequest(owner, name, number, style), RetryEligibility.Never)

  /** Asks accounts or teams to review — `POST /repos/{owner}/{repo}/pulls/{index}/requested_reviewers`.
    *
    * Answers with the [[Review]] rows the request created, each in state [[ReviewState.RequestReview]] — the same rows
    * [[listReviews]] then reports, which is why a caller counting approvals has to filter on [[Review.state]]. The
    * endpoint declares no paging, so this is a `Vector`.
    *
    * '''Never retried.''' It creates rows, and `docs/HAZARDS.md` records no idempotency key anywhere in this API. In
    * practice Forgejo ignores a reviewer who is already requested, so a repeat is usually harmless — but "usually" is
    * not a guarantee this library will make on a caller's behalf, and a repeat also re-sends every requested reviewer's
    * notification. Withdrawing a request is [[removeReviewRequests]].
    *
    * '''Failures.''' The group contract above, plus `403` when the credentials may not request reviews on this
    * repository. `422` is the common one and covers a request naming nobody — see [[ReviewRequest.isEmpty]] — an
    * account that cannot see the repository, a team that does not exist, and asking the pull request's own author to
    * review it.
    *
    * @param request
    *   who to ask; accounts and teams are separate lists and Forgejo will not look one up in the other
    */
  def requestReviews(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      request: ReviewRequest,
  ): Future[Vector[Review]] =
    pipeline.call(PullRequestApi.requestReviewsRequest(owner, name, number, request), RetryEligibility.Never)(using
      PullRequestApi.ReviewsDecoder)

  /** Withdraws review requests — `DELETE /repos/{owner}/{repo}/pulls/{index}/requested_reviewers`.
    *
    * '''A `DELETE` that carries a JSON body''', which is unusual and is what this endpoint requires: the body is the
    * same [[ReviewRequest]] [[requestReviews]] sends, and it names exactly whose requests to withdraw. Sending
    * [[ReviewRequest.Empty]] withdraws nothing and is answered `422`, not "all of them".
    *
    * '''This does not delete reviews.''' A request that has already been answered is a submitted [[Review]], and
    * removing the request leaves that review in place. Getting rid of a review is [[dismissReview]] or
    * [[deleteReview]].
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]]. The body names exactly who is
    * to be removed, so the end state is the same after one attempt or five, and nothing is created. The residual risk
    * is narrow and worth stating: if someone re-requests one of the named reviewers between two attempts, the second
    * attempt withdraws that new request too.
    *
    * '''Answers `204` with no body''', so nothing is decoded.
    *
    * '''Failures.''' The group contract above, plus `403` when the credentials may not manage reviewers here and `422`
    * when the body names nobody or names an account with no request outstanding.
    */
  def removeReviewRequests(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      request: ReviewRequest,
  ): Future[Unit] =
    pipeline.callUnit(
      PullRequestApi.removeReviewRequestsRequest(owner, name, number, request),
      RetryEligibility.AlwaysRetry,
    )

  /** Writes a review — `POST /repos/{owner}/{repo}/pulls/{index}/reviews`.
    *
    * One call posts the summary, the verdict and every inline remark together, which is what the web UI's "submit
    * review" button does. Whether the review is '''submitted''' or left as the reviewer's own pending draft is decided
    * by [[CreateReview.saying]] — see [[CreateReview]] for which [[ReviewState]] cases are meaningful as an event and
    * which come back as a `422`.
    *
    * '''Never retried.''' This creates a review, and a repeat after a lost response creates a second one: Forgejo
    * neither recognises the repeat nor refuses a duplicate approval. A transport failure therefore leaves the caller
    * genuinely unsure whether the review exists, which is better than two of them appearing on the pull request.
    *
    * '''Answers `200`''', not `201`, with the created review as the body — Forgejo's own choice, and success either way
    * as far as [[com.worxbend.codeberg4s.core.StatusMapping]] is concerned.
    *
    * '''Failures.''' The group contract above. `422` is the interesting one and covers a great deal: an event Forgejo
    * will not accept, a missing body where the event requires one, a `commit_id` that is not on the pull request, an
    * inline remark whose `path` the pull request does not touch, and a reviewer reviewing their own pull request on an
    * instance that forbids it.
    *
    * @param command
    *   what to say; built from [[CreateReview.Empty]]
    */
  def createReview(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      command: CreateReview,
  ): Future[Review] =
    pipeline.call(PullRequestApi.createReviewRequest(owner, name, number, command), RetryEligibility.Never)(using
      PullRequestApi.ReviewDecoder)

  /** Reads one review — `GET /repos/{owner}/{repo}/pulls/{index}/reviews/{id}`.
    *
    * The same [[Review]] model [[listReviews]] returns, addressed by its instance-wide [[ReviewId]] rather than by a
    * position on the listing. Worth using after [[createReview]] or [[submitReview]] to read back what was recorded.
    *
    * '''Failures.''' The group contract above. `404` covers "no such review", "that review belongs to a different pull
    * request", and "no such pull request"; Forgejo does not distinguish them.
    */
  def getReview(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
  ): Future[Review] =
    pipeline.call(PullRequestApi.getReviewRequest(owner, name, number, review), RetryEligibility.IdempotentOnly)(using
      PullRequestApi.ReviewDecoder)

  /** Submits a pending review — `POST /repos/{owner}/{repo}/pulls/{index}/reviews/{id}`.
    *
    * Finishes the draft a reviewer built up with [[createReview]] and [[createReviewComment]], turning it into a
    * verdict everyone can see. [[SubmitReview]] makes the event mandatory, because a submission that says nothing is a
    * `422`.
    *
    * '''Never retried, and this is a create in everything but name.''' A pending review is consumed by being submitted,
    * so a repeat after a lost response finds nothing to submit and answers `422` — which a caller would read as the
    * submission having been rejected rather than as it having already succeeded. Confirming with [[getReview]] is the
    * reliable way to find out; retrying is not.
    *
    * '''Answers `200`''' with the submitted review as the body.
    *
    * '''Failures.''' The group contract above, plus `422` for an event Forgejo will not accept, for a review that is
    * not pending, and for a [[ReviewState.Comment]] or [[ReviewState.RequestChanges]] submission whose draft carries no
    * body and whose command supplies none either.
    */
  def submitReview(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
      command: SubmitReview,
  ): Future[Review] =
    pipeline.call(
      PullRequestApi.submitReviewRequest(owner, name, number, review, command),
      RetryEligibility.Never,
    )(using PullRequestApi.ReviewDecoder)

  /** Deletes a review — `DELETE /repos/{owner}/{repo}/pulls/{index}/reviews/{id}`.
    *
    * '''Irreversible, and different from dismissing.''' [[dismissReview]] leaves the review on the pull request with
    * [[Review.isDismissed]] set, so the reasoning stays readable and [[undismissReview]] can put it back; this removes
    * the row and its inline comments outright, and nothing undoes it.
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], which is safe here for a reason
    * that does not hold for [[merge]]: the request names one review by an instance-wide id, and Forgejo never reuses an
    * id, so a repeat cannot reach a different review than the one the caller named. The end state is "that review is
    * gone" however many attempts it took. A second attempt after a lost `204` answers `404`, which is by then a true
    * statement.
    *
    * '''Answers `204` with no body''', so nothing is decoded.
    *
    * '''Failures.''' The group contract above, plus `403` when the credentials may not delete this review — a reviewer
    * may delete their own pending review, deleting someone else's needs repository administration.
    */
  def deleteReview(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
  ): Future[Unit] =
    pipeline.callUnit(PullRequestApi.deleteReviewRequest(owner, name, number, review), RetryEligibility.AlwaysRetry)

  /** Dismisses a review — `POST /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/dismissals`.
    *
    * Takes a review out of the base branch's required-approval count without deleting it: the row stays on
    * [[listReviews]] with [[Review.isDismissed]] set, and [[undismissReview]] reverses it.
    * [[DismissReview.withMessage]] is worth setting — the message is the only explanation the reviewer ever sees — and
    * [[DismissReview.includingPriors]] extends the dismissal to that reviewer's earlier reviews of the same pull
    * request.
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], despite being a `POST`. It
    * creates nothing: it sets a flag on one review named by an instance-wide id, and setting it twice leaves exactly
    * the state setting it once does. The message is overwritten with the same message, and the priors reached are the
    * same priors.
    *
    * '''Answers `200`''' with the dismissed review as the body, so [[Review.isDismissed]] can be read back immediately.
    *
    * '''Failures.''' The group contract above, plus `403` when the credentials may not dismiss reviews here and `422`
    * when the review cannot be dismissed — a pending review has nothing to dismiss, and an already-dismissed one is
    * refused rather than accepted as a no-op.
    */
  def dismissReview(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
      command: DismissReview,
  ): Future[Review] =
    pipeline.call(
      PullRequestApi.dismissReviewRequest(owner, name, number, review, command),
      RetryEligibility.AlwaysRetry,
    )(using PullRequestApi.ReviewDecoder)

  /** Reverses a dismissal — `POST /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/undismissals`.
    *
    * Puts a dismissed review back into the approval count. It carries no body: the review id is the whole request, and
    * there is nothing to say about restoring one.
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], for the reason
    * [[dismissReview]] gives — it clears a flag on one review named by an instance-wide id, and clearing it twice
    * leaves the same state.
    *
    * '''Answers `200`''' with the restored review as the body.
    *
    * '''Failures.''' The group contract above, plus `403` when the credentials may not manage reviews here and `422`
    * when the review was not dismissed in the first place.
    */
  def undismissReview(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
  ): Future[Review] =
    pipeline.call(
      PullRequestApi.undismissReviewRequest(owner, name, number, review),
      RetryEligibility.AlwaysRetry,
    )(using PullRequestApi.ReviewDecoder)

  /** Lists a review's inline comments — `GET /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments`.
    *
    * The remarks anchored to diff lines, which are '''not''' the pull request's conversation: ordinary comments live on
    * the issue endpoints, because Forgejo stores them there. See [[ReviewComment]] for the difference and for why the
    * two line numbers are the way they are.
    *
    * '''Not paged.''' The endpoint declares no `page` and no `limit`, so the instance sends the whole set and this
    * returns a `Vector`. [[Review.commentCount]] is the same number the listing has elements, which is a cheap way to
    * decide whether fetching them is worth a round trip.
    *
    * '''Failures.''' The group contract above.
    */
  def listReviewComments(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
  ): Future[Vector[ReviewComment]] =
    pipeline.call(
      PullRequestApi.reviewCommentsRequest(owner, name, number, review),
      RetryEligibility.IdempotentOnly,
    )(using PullRequestApi.ReviewCommentsDecoder)

  /** Adds one inline comment to a review — `POST /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments`.
    *
    * Used to build up a '''pending''' review one remark at a time, then finish it with [[submitReview]]. Posting every
    * remark with the review in a single call is [[createReview]] with [[CreateReview.commenting]] instead, and it is
    * the cheaper of the two when the remarks are known up front.
    *
    * '''Never retried.''' It creates a comment, and a repeat after a lost response leaves the same remark on the diff
    * twice — Forgejo does not deduplicate. Confirming with [[listReviewComments]] is the reliable way to find out
    * whether the first attempt landed.
    *
    * '''Answers `200`''' with the created comment as the body.
    *
    * '''Failures.''' The group contract above, plus `422` when Forgejo rejects the anchor: a `path` the pull request
    * does not touch, a line that is not part of the diff on the side that was named, or a review that is not the
    * caller's own pending one.
    *
    * @param comment
    *   what to write and where; [[NewReviewComment]]'s constructors have already rejected a blank body, a blank path
    *   and a non-positive line, and they are what decide which side of the diff the remark lands on
    */
  def createReviewComment(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
      comment: NewReviewComment,
  ): Future[ReviewComment] =
    pipeline.call(
      PullRequestApi.createReviewCommentRequest(owner, name, number, review, comment),
      RetryEligibility.Never,
    )(using PullRequestApi.ReviewCommentDecoder)

  /** Reads one inline comment — `GET /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments/{comment}`.
    *
    * '''Both ids are needed and they are not interchangeable.''' The review's [[ReviewId]] and the comment's
    * [[ReviewCommentId]] occupy adjacent path segments and are both `int64`; swapping them produces a `404` that reads
    * like a deleted comment. The two opaque types are what keep that from compiling.
    *
    * '''Failures.''' The group contract above, plus `403`, which this endpoint declares and its listing does not —
    * Forgejo can refuse an individual comment on a diff the credentials may not read.
    */
  def getReviewComment(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
      comment: ReviewCommentId,
  ): Future[ReviewComment] =
    pipeline.call(
      PullRequestApi.getReviewCommentRequest(owner, name, number, review, comment),
      RetryEligibility.IdempotentOnly,
    )(using PullRequestApi.ReviewCommentDecoder)

  /** Deletes one inline comment — `DELETE /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments/{comment}`.
    *
    * '''Irreversible''', and it removes the remark from the diff rather than marking it resolved — resolving a
    * conversation is a web-UI action this API does not expose, and it shows up on [[ReviewComment.resolver]].
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], for the reason [[deleteReview]]
    * gives: the comment is named by an instance-wide id that Forgejo never reuses, so a repeat cannot reach a different
    * comment, and the end state is the same however many attempts it took.
    *
    * '''Answers `204` with no body''', so nothing is decoded.
    *
    * '''Failures.''' The group contract above, plus `403` when the credentials may not delete this comment.
    */
  def deleteReviewComment(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
      comment: ReviewCommentId,
  ): Future[Unit] =
    pipeline.callUnit(
      PullRequestApi.deleteReviewCommentRequest(owner, name, number, review, comment),
      RetryEligibility.AlwaysRetry,
    )

/** The requests this group issues, its operation ids, and its typed rail. */
object PullRequestApi:

  /** The stable operation id [[PullRequestApi.list]] copies into every failure's
    * [[com.worxbend.codeberg4s.CallContext]]. Safe to alert on.
    */
  val ListOperation: String = "pulls.list"

  /** The stable operation id of the single-pull-request read on [[PullRequestApi]]. */
  val GetOperation: String = "pulls.get"

  /** The stable operation id of [[PullRequestApi.create]]. */
  val CreateOperation: String = "pulls.create"

  /** The stable operation id of [[PullRequestApi.edit]]. */
  val EditOperation: String = "pulls.edit"

  /** The stable operation id of [[PullRequestApi.merge]]. The one worth alerting on by itself. */
  val MergeOperation: String = "pulls.merge"

  /** The stable operation id of [[PullRequestApi.listReviews]]. */
  val ListReviewsOperation: String = "pulls.reviews.list"

  /** The stable operation id of [[PullRequestApi.listCommits]]. */
  val ListCommitsOperation: String = "pulls.commits.list"

  /** The stable operation id of [[PullRequestApi.listFiles]]. */
  val ListFilesOperation: String = "pulls.files.list"

  /** The stable operation id of [[PullRequestApi.listPinned]]. */
  val ListPinnedOperation: String = "pulls.pinned.list"

  /** The stable operation id of [[PullRequestApi.getByBaseHead]]. */
  val GetByBaseHeadOperation: String = "pulls.getByBaseHead"

  /** The stable operation id of [[PullRequestApi.download]], whatever [[DiffFormat]] was asked for. */
  val DownloadOperation: String = "pulls.download"

  /** The stable operation id of [[PullRequestApi.isMerged]]. */
  val MergeStatusOperation: String = "pulls.merge.status"

  /** The stable operation id of [[PullRequestApi.cancelScheduledMerge]]. */
  val CancelScheduledMergeOperation: String = "pulls.merge.cancel"

  /** The stable operation id of [[PullRequestApi.updateBranch]]. Worth alerting on by itself: it rewrites a branch. */
  val UpdateBranchOperation: String = "pulls.update"

  /** The stable operation id of [[PullRequestApi.requestReviews]]. */
  val RequestReviewsOperation: String = "pulls.reviewRequests.create"

  /** The stable operation id of [[PullRequestApi.removeReviewRequests]]. */
  val RemoveReviewRequestsOperation: String = "pulls.reviewRequests.delete"

  /** The stable operation id of [[PullRequestApi.createReview]]. */
  val CreateReviewOperation: String = "pulls.reviews.create"

  /** The stable operation id of [[PullRequestApi.getReview]]. */
  val GetReviewOperation: String = "pulls.reviews.get"

  /** The stable operation id of [[PullRequestApi.submitReview]]. */
  val SubmitReviewOperation: String = "pulls.reviews.submit"

  /** The stable operation id of [[PullRequestApi.deleteReview]]. */
  val DeleteReviewOperation: String = "pulls.reviews.delete"

  /** The stable operation id of [[PullRequestApi.dismissReview]]. */
  val DismissReviewOperation: String = "pulls.reviews.dismiss"

  /** The stable operation id of [[PullRequestApi.undismissReview]]. */
  val UndismissReviewOperation: String = "pulls.reviews.undismiss"

  /** The stable operation id of [[PullRequestApi.listReviewComments]]. */
  val ListReviewCommentsOperation: String = "pulls.reviews.comments.list"

  /** The stable operation id of [[PullRequestApi.createReviewComment]]. */
  val CreateReviewCommentOperation: String = "pulls.reviews.comments.create"

  /** The stable operation id of [[PullRequestApi.getReviewComment]]. */
  val GetReviewCommentOperation: String = "pulls.reviews.comments.get"

  /** The stable operation id of [[PullRequestApi.deleteReviewComment]]. */
  val DeleteReviewCommentOperation: String = "pulls.reviews.comments.delete"

  /** The typed rail of [[PullRequestApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a value.
    *
    * Obtained as `client.pulls.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: PullRequestApi)(using exec: Exec[Future]):

    /** [[PullRequestApi.list]] with its failure as a value. */
    def list(
        owner: Owner,
        name: RepoName,
        query: PullRequestQuery,
        params: PageParams,
    ): Future[Either[CodebergError, Page[PullRequest]]] =
      exec.attempt(rail.list(owner, name, query, params))

    /** The single-pull-request read on [[PullRequestApi]], with its failure as a value. */
    def get(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
    ): Future[Either[CodebergError, PullRequest]] =
      exec.attempt(rail.get(owner, name, number))

    /** [[PullRequestApi.create]] with its failure as a value. */
    def create(
        owner: Owner,
        name: RepoName,
        command: CreatePullRequest,
    ): Future[Either[CodebergError, PullRequest]] =
      exec.attempt(rail.create(owner, name, command))

    /** [[PullRequestApi.edit]] with its failure as a value. */
    def edit(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        command: EditPullRequest,
    ): Future[Either[CodebergError, PullRequest]] =
      exec.attempt(rail.edit(owner, name, number, command))

    /** [[PullRequestApi.merge]] with its failure as a value — including the `405` that means the merge was refused. */
    def merge(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        command: MergePullRequest,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.merge(owner, name, number, command))

    /** [[PullRequestApi.listReviews]] with its failure as a value. */
    def listReviews(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        params: PageParams,
    ): Future[Either[CodebergError, Page[Review]]] =
      exec.attempt(rail.listReviews(owner, name, number, params))

    /** [[PullRequestApi.listCommits]] with its failure as a value. */
    def listCommits(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        params: PageParams,
    ): Future[Either[CodebergError, Page[Commit]]] =
      exec.attempt(rail.listCommits(owner, name, number, params))

    /** [[PullRequestApi.listFiles]] with its failure as a value. */
    def listFiles(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        params: PageParams,
    ): Future[Either[CodebergError, Page[ChangedFile]]] =
      exec.attempt(rail.listFiles(owner, name, number, params))

    /** [[PullRequestApi.listPinned]] with its failure as a value. */
    def listPinned(owner: Owner, name: RepoName): Future[Either[CodebergError, Vector[PullRequest]]] =
      exec.attempt(rail.listPinned(owner, name))

    /** [[PullRequestApi.getByBaseHead]] with its failure as a value — including the `404` that means no pull request
      * joins those two branches.
      */
    def getByBaseHead(
        owner: Owner,
        name: RepoName,
        base: BranchName,
        head: PullRequestHead,
    ): Future[Either[CodebergError, PullRequest]] =
      exec.attempt(rail.getByBaseHead(owner, name, base, head))

    /** [[PullRequestApi.download]] with its failure as a value. Never a
      * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]]; nothing is parsed.
      */
    def download(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        request: DiffRequest,
    ): Future[Either[CodebergError, String]] =
      exec.attempt(rail.download(owner, name, number, request))

    /** [[PullRequestApi.isMerged]] with its failure as a value. The `404` that means "not merged" is still a
      * `Right(false)` here, not a `Left`.
      */
    def isMerged(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
    ): Future[Either[CodebergError, Boolean]] =
      exec.attempt(rail.isMerged(owner, name, number))

    /** [[PullRequestApi.cancelScheduledMerge]] with its failure as a value. */
    def cancelScheduledMerge(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.cancelScheduledMerge(owner, name, number))

    /** [[PullRequestApi.updateBranch]] with its failure as a value. */
    def updateBranch(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        style: UpdateStyle,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.updateBranch(owner, name, number, style))

    /** [[PullRequestApi.requestReviews]] with its failure as a value. */
    def requestReviews(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        request: ReviewRequest,
    ): Future[Either[CodebergError, Vector[Review]]] =
      exec.attempt(rail.requestReviews(owner, name, number, request))

    /** [[PullRequestApi.removeReviewRequests]] with its failure as a value. */
    def removeReviewRequests(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        request: ReviewRequest,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.removeReviewRequests(owner, name, number, request))

    /** [[PullRequestApi.createReview]] with its failure as a value. */
    def createReview(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        command: CreateReview,
    ): Future[Either[CodebergError, Review]] =
      exec.attempt(rail.createReview(owner, name, number, command))

    /** [[PullRequestApi.getReview]] with its failure as a value. */
    def getReview(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
    ): Future[Either[CodebergError, Review]] =
      exec.attempt(rail.getReview(owner, name, number, review))

    /** [[PullRequestApi.submitReview]] with its failure as a value — including the `422` that means the review was no
      * longer pending.
      */
    def submitReview(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
        command: SubmitReview,
    ): Future[Either[CodebergError, Review]] =
      exec.attempt(rail.submitReview(owner, name, number, review, command))

    /** [[PullRequestApi.deleteReview]] with its failure as a value. */
    def deleteReview(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteReview(owner, name, number, review))

    /** [[PullRequestApi.dismissReview]] with its failure as a value. */
    def dismissReview(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
        command: DismissReview,
    ): Future[Either[CodebergError, Review]] =
      exec.attempt(rail.dismissReview(owner, name, number, review, command))

    /** [[PullRequestApi.undismissReview]] with its failure as a value. */
    def undismissReview(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
    ): Future[Either[CodebergError, Review]] =
      exec.attempt(rail.undismissReview(owner, name, number, review))

    /** [[PullRequestApi.listReviewComments]] with its failure as a value. */
    def listReviewComments(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
    ): Future[Either[CodebergError, Vector[ReviewComment]]] =
      exec.attempt(rail.listReviewComments(owner, name, number, review))

    /** [[PullRequestApi.createReviewComment]] with its failure as a value. */
    def createReviewComment(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
        comment: NewReviewComment,
    ): Future[Either[CodebergError, ReviewComment]] =
      exec.attempt(rail.createReviewComment(owner, name, number, review, comment))

    /** [[PullRequestApi.getReviewComment]] with its failure as a value. */
    def getReviewComment(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
        comment: ReviewCommentId,
    ): Future[Either[CodebergError, ReviewComment]] =
      exec.attempt(rail.getReviewComment(owner, name, number, review, comment))

    /** [[PullRequestApi.deleteReviewComment]] with its failure as a value. */
    def deleteReviewComment(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
        comment: ReviewCommentId,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteReviewComment(owner, name, number, review, comment))

  private def listRequest(
      owner: Owner,
      name: RepoName,
      query: PullRequestQuery,
      params: PageParams,
  ): CodebergRequest =
    read(ListOperation, pullsPath(owner, name), PullRequestQueries.pulls(query) ++ PullRequestQueries.paging(params))

  private def getRequest(owner: Owner, name: RepoName, number: PullRequestNumber): CodebergRequest =
    read(GetOperation, pullPath(owner, name, number), Nil)

  private def createRequest(owner: Owner, name: RepoName, command: CreatePullRequest): CodebergRequest =
    write(CreateOperation, HttpMethod.Post, pullsPath(owner, name), CreatePullRequestOptionDto.render(command))

  private def editRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      command: EditPullRequest,
  ): CodebergRequest =
    write(
      EditOperation,
      HttpMethod.Patch,
      pullPath(owner, name, number),
      EditPullRequestOptionDto.render(command),
    )

  private def mergeRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      command: MergePullRequest,
  ): CodebergRequest =
    write(
      MergeOperation,
      HttpMethod.Post,
      pullPath(owner, name, number) :+ "merge",
      MergePullRequestOptionDto.render(command),
    )

  private def reviewsRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      params: PageParams,
  ): CodebergRequest =
    read(ListReviewsOperation, pullPath(owner, name, number) :+ "reviews", PullRequestQueries.paging(params))

  private def commitsRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      params: PageParams,
  ): CodebergRequest =
    read(ListCommitsOperation, pullPath(owner, name, number) :+ "commits", PullRequestQueries.paging(params))

  private def filesRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      params: PageParams,
  ): CodebergRequest =
    read(ListFilesOperation, pullPath(owner, name, number) :+ "files", PullRequestQueries.paging(params))

  private def pinnedRequest(owner: Owner, name: RepoName): CodebergRequest =
    read(ListPinnedOperation, pullsPath(owner, name) :+ "pinned", Nil)

  private def baseHeadRequest(
      owner: Owner,
      name: RepoName,
      base: BranchName,
      head: PullRequestHead,
  ): CodebergRequest =
    read(GetByBaseHeadOperation, pullsPath(owner, name) ++ List(base.value, head.value), Nil)

  private def downloadRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      request: DiffRequest,
  ): CodebergRequest =
    read(
      DownloadOperation,
      pullsPath(owner, name) :+ s"${number.value}.${request.format.wireValue}",
      PullRequestQueries.diff(request),
    )

  private def mergeStatusRequest(owner: Owner, name: RepoName, number: PullRequestNumber): CodebergRequest =
    read(MergeStatusOperation, pullPath(owner, name, number) :+ "merge", Nil)

  private def cancelMergeRequest(owner: Owner, name: RepoName, number: PullRequestNumber): CodebergRequest =
    remove(CancelScheduledMergeOperation, pullPath(owner, name, number) :+ "merge")

  private def updateBranchRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      style: UpdateStyle,
  ): CodebergRequest =
    post(UpdateBranchOperation, pullPath(owner, name, number) :+ "update", PullRequestQueries.update(style), None)

  private def requestReviewsRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      request: ReviewRequest,
  ): CodebergRequest =
    write(
      RequestReviewsOperation,
      HttpMethod.Post,
      reviewersPath(owner, name, number),
      PullReviewRequestOptionsDto.render(request),
    )

  private def removeReviewRequestsRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      request: ReviewRequest,
  ): CodebergRequest =
    write(
      RemoveReviewRequestsOperation,
      HttpMethod.Delete,
      reviewersPath(owner, name, number),
      PullReviewRequestOptionsDto.render(request),
    )

  private def createReviewRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      command: CreateReview,
  ): CodebergRequest =
    write(
      CreateReviewOperation,
      HttpMethod.Post,
      reviewsPath(owner, name, number),
      CreatePullReviewOptionsDto.render(command),
    )

  private def getReviewRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
  ): CodebergRequest =
    read(GetReviewOperation, reviewPath(owner, name, number, review), Nil)

  private def submitReviewRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
      command: SubmitReview,
  ): CodebergRequest =
    write(
      SubmitReviewOperation,
      HttpMethod.Post,
      reviewPath(owner, name, number, review),
      SubmitPullReviewOptionsDto.render(command),
    )

  private def deleteReviewRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
  ): CodebergRequest =
    remove(DeleteReviewOperation, reviewPath(owner, name, number, review))

  private def dismissReviewRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
      command: DismissReview,
  ): CodebergRequest =
    write(
      DismissReviewOperation,
      HttpMethod.Post,
      reviewPath(owner, name, number, review) :+ "dismissals",
      DismissPullReviewOptionsDto.render(command),
    )

  /** The one write in this group that sends no body at all.
    *
    * [[com.worxbend.codeberg4s.core.RequestBody.Empty]] rather than `None`: Forgejo's router accepts the `POST` either
    * way, but an explicit zero-length body keeps the `Content-Length: 0` header that some proxies insist on for a
    * `POST`.
    */
  private def undismissReviewRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
  ): CodebergRequest =
    post(
      UndismissReviewOperation,
      reviewPath(owner, name, number, review) :+ "undismissals",
      Nil,
      Some(RequestBody.Empty),
    )

  private def reviewCommentsRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
  ): CodebergRequest =
    read(ListReviewCommentsOperation, reviewCommentsPath(owner, name, number, review), Nil)

  private def createReviewCommentRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
      comment: NewReviewComment,
  ): CodebergRequest =
    write(
      CreateReviewCommentOperation,
      HttpMethod.Post,
      reviewCommentsPath(owner, name, number, review),
      NewReviewCommentDto.render(comment),
    )

  private def getReviewCommentRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
      comment: ReviewCommentId,
  ): CodebergRequest =
    read(GetReviewCommentOperation, reviewCommentPath(owner, name, number, review, comment), Nil)

  private def deleteReviewCommentRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
      comment: ReviewCommentId,
  ): CodebergRequest =
    remove(DeleteReviewCommentOperation, reviewCommentPath(owner, name, number, review, comment))

  /** The two `POST`s in this group that carry no JSON body, which no shared builder covers.
    *
    * `update` is the only mutation here with query parameters, and `undismissals` is the only one that wants
    * [[com.worxbend.codeberg4s.core.RequestBody.Empty]] rather than no body at all.
    */
  private def post(
      operation: String,
      path: List[String],
      query: List[(String, String)],
      body: Option[RequestBody],
  ): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Post,
      path      = path,
      query     = query,
      headers   = Nil,
      body      = body,
    )

  private def pullsPath(owner: Owner, name: RepoName): List[String] =
    List("repos", owner.value, name.value, "pulls")

  private def pullPath(owner: Owner, name: RepoName, number: PullRequestNumber): List[String] =
    pullsPath(owner, name) :+ number.value.toString

  private def reviewersPath(owner: Owner, name: RepoName, number: PullRequestNumber): List[String] =
    pullPath(owner, name, number) :+ "requested_reviewers"

  private def reviewsPath(owner: Owner, name: RepoName, number: PullRequestNumber): List[String] =
    pullPath(owner, name, number) :+ "reviews"

  private def reviewPath(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
  ): List[String] =
    reviewsPath(owner, name, number) :+ review.value.toString

  private def reviewCommentsPath(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
  ): List[String] =
    reviewPath(owner, name, number, review) :+ "comments"

  private def reviewCommentPath(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
      comment: ReviewCommentId,
  ): List[String] =
    reviewCommentsPath(owner, name, number, review) :+ comment.value.toString

  private val PullDecoder: Decode[PullRequest] =
    WireDecode.single(Json.decoder[PullRequestDto])(_.toDomain)

  private val PullsDecoder: Decode[Vector[PullRequest]] =
    WireDecode.vector(Json.decoder[Vector[PullRequestDto]])(PullRequestDto.toDomainAll)

  private val ReviewDecoder: Decode[Review] =
    WireDecode.single(Json.decoder[ReviewDto])(_.toDomain)

  private val ReviewsDecoder: Decode[Vector[Review]] =
    WireDecode.vector(Json.decoder[Vector[ReviewDto]])(ReviewDto.toDomainAll)

  private val ReviewCommentDecoder: Decode[ReviewComment] =
    WireDecode.single(Json.decoder[ReviewCommentDto])(_.toDomain)

  private val ReviewCommentsDecoder: Decode[Vector[ReviewComment]] =
    WireDecode.vector(Json.decoder[Vector[ReviewCommentDto]])(ReviewCommentDto.toDomainAll)

  private val CommitsDecoder: Decode[Vector[Commit]] =
    WireDecode.vector(Json.decoder[Vector[CommitDto]]): (at, dtos) =>
      ArrayElements.convert(at, dtos)(_.toDomainAt(_))

  private val FilesDecoder: Decode[Vector[ChangedFile]] =
    WireDecode.vector(Json.decoder[Vector[ChangedFileDto]])(ChangedFileDto.toDomainAll)
