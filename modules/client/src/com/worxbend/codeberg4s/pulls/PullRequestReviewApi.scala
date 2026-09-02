package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.codec.PagingQuery
import com.worxbend.codeberg4s.core.CodebergRequest.{empty, read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.pulls.PullRequestRequests.pullPath
import com.worxbend.codeberg4s.pulls.wire.{
  CreatePullReviewOptionsDto,
  DismissPullReviewOptionsDto,
  NewReviewCommentDto,
  PullReviewRequestOptionsDto,
  SubmitPullReviewOptionsDto
}
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName}

import scala.concurrent.Future

/** The review half of the pull-request surface: reviews, their inline comments, and who has been asked to review.
  *
  * Reached as `client.pulls.reviews`. It is a group of its own rather than more methods on [[PullRequestApi]] because
  * the two answer different questions — [[PullRequestApi]] is about the proposed change, this is about the verdicts on
  * it — and because keeping them in one class had grown a file no reader could hold in their head.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[PullRequestReviewApi.attempt]] never
  * fail and return an `Either` instead.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on
  * each method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository, the pull request or the
  *     review does not exist '''or''' is private to credentials the client does not have — Forgejo does not
  *     distinguish the two, on purpose — `401` when a token was required and none was sent, and `403` when the token
  *     lacks the scope. `422` '''and''' `400` both mean the request was rejected as invalid.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here: every
  * argument is an already-validated type, so a value that would forge a path is rejected by its own smart constructor
  * before a client is ever involved.
  *
  * ==Retries==
  *
  * Reads use [[com.worxbend.codeberg4s.core.RetryEligibility.IdempotentOnly]]; every write here uses
  * [[com.worxbend.codeberg4s.core.RetryEligibility.Never]], because repeating one of them leaves a second review or a
  * second comment behind and Forgejo has no idempotency key that would let the instance recognise the repeat.
  *
  * ==Paging==
  *
  * Only [[list]] takes a [[com.worxbend.codeberg4s.paging.PageParams]], and even that one gets no `Link` header —
  * `golden/MANIFEST.md` records `X-Total-Count` and nothing else — so the page it returns always reports itself as the
  * last one. The rest of the reads here declare no `page` or `limit` at all and return a plain `Vector`.
  *
  * ==Evidence==
  *
  * Only the review listing is checked against a golden capture. `golden/MANIFEST.md` holds no review-comment payload —
  * every review the anonymous harvest could reach carried `comments_count: 0` — and none of the writes can be
  * exercised without credentials, so those models are derived from `spec/swagger.v1.json` and say so on their own
  * types. Should a capture ever contradict one, the capture wins.
  */

final class PullRequestReviewApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: PullRequestReviewApi.Attempt = PullRequestReviewApi.Attempt(this)

  /** Lists a pull request's reviews — `GET /repos/{owner}/{repo}/pulls/{index}/reviews`.
    *
    * '''The listing contains review requests.''' Asking someone to review produces a row here with state
    * [[ReviewState.RequestReview]] and no commit — two of the three rows of `golden/pull/reviews-list.json` are of that
    * kind — so a caller counting approvals filters on [[Review.state]] rather than counting rows.
    *
    * '''Paging is weaker here than on [[PullRequestApi.list]], and a caller has to know it.''' `golden/MANIFEST.md` records that this
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
  def list(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      params: PageParams,
  ): Future[Page[Review]] =
    pipeline.callPage(PullRequestReviewApi.reviewsRequest(owner, name, number, params), params)(using
      PullRequestDecoders.reviews)

  /** Asks accounts or teams to review — `POST /repos/{owner}/{repo}/pulls/{index}/requested_reviewers`.
    *
    * Answers with the [[Review]] rows the request created, each in state [[ReviewState.RequestReview]] — the same rows
    * [[list]] then reports, which is why a caller counting approvals has to filter on [[Review.state]]. The endpoint
    * declares no paging, so this is a `Vector`.
    *
    * '''Never retried.''' It creates rows, and `docs/HAZARDS.md` records no idempotency key anywhere in this API. In
    * practice Forgejo ignores a reviewer who is already requested, so a repeat is usually harmless — but "usually" is
    * not a guarantee this library will make on a caller's behalf, and a repeat also re-sends every requested reviewer's
    * notification. Withdrawing a request is [[removeRequests]].
    *
    * '''Failures.''' The group contract above, plus `403` when the credentials may not request reviews on this
    * repository. `422` is the common one and covers a request naming nobody — see [[ReviewRequest.isEmpty]] — an
    * account that cannot see the repository, a team that does not exist, and asking the pull request's own author to
    * review it.
    *
    * @param request
    *   who to ask; accounts and teams are separate lists and Forgejo will not look one up in the other
    */
  def request(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      request: ReviewRequest,
  ): Future[Vector[Review]] =
    pipeline.call(PullRequestReviewApi.requestReviewsRequest(owner, name, number, request), RetryEligibility.Never)(using
      PullRequestDecoders.reviews)

  /** Withdraws review requests — `DELETE /repos/{owner}/{repo}/pulls/{index}/requested_reviewers`.
    *
    * '''A `DELETE` that carries a JSON body''', which is unusual and is what this endpoint requires: the body is the
    * same [[ReviewRequest]] [[request]] sends, and it names exactly whose requests to withdraw. Sending
    * [[ReviewRequest.Empty]] withdraws nothing and is answered `422`, not "all of them".
    *
    * '''This does not delete reviews.''' A request that has already been answered is a submitted [[Review]], and
    * removing the request leaves that review in place. Getting rid of a review is [[dismiss]] or
    * [[delete]].
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
  def removeRequests(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      request: ReviewRequest,
  ): Future[Unit] =
    pipeline.callUnit(
      PullRequestReviewApi.removeReviewRequestsRequest(owner, name, number, request),
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
  def create(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      command: CreateReview,
  ): Future[Review] =
    pipeline.call(PullRequestReviewApi.createReviewRequest(owner, name, number, command), RetryEligibility.Never)(using
      PullRequestDecoders.review)

  /** Reads one review — `GET /repos/{owner}/{repo}/pulls/{index}/reviews/{id}`.
    *
    * The same [[Review]] model [[list]] returns, addressed by its instance-wide [[ReviewId]] rather than by a
    * position on the listing. Worth using after [[create]] or [[submit]] to read back what was recorded.
    *
    * '''Failures.''' The group contract above. `404` covers "no such review", "that review belongs to a different pull
    * request", and "no such pull request"; Forgejo does not distinguish them.
    */
  def get(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
  ): Future[Review] =
    pipeline.call(PullRequestReviewApi.getReviewRequest(owner, name, number, review), RetryEligibility.IdempotentOnly)(using
      PullRequestDecoders.review)

  /** Submits a pending review — `POST /repos/{owner}/{repo}/pulls/{index}/reviews/{id}`.
    *
    * Finishes the draft a reviewer built up with [[create]] and [[createComment]], turning it into a
    * verdict everyone can see. [[SubmitReview]] makes the event mandatory, because a submission that says nothing is a
    * `422`.
    *
    * '''Never retried, and this is a create in everything but name.''' A pending review is consumed by being submitted,
    * so a repeat after a lost response finds nothing to submit and answers `422` — which a caller would read as the
    * submission having been rejected rather than as it having already succeeded. Confirming with [[get]] is the
    * reliable way to find out; retrying is not.
    *
    * '''Answers `200`''' with the submitted review as the body.
    *
    * '''Failures.''' The group contract above, plus `422` for an event Forgejo will not accept, for a review that is
    * not pending, and for a [[ReviewState.Comment]] or [[ReviewState.RequestChanges]] submission whose draft carries no
    * body and whose command supplies none either.
    */
  def submit(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
      command: SubmitReview,
  ): Future[Review] =
    pipeline.call(
      PullRequestReviewApi.submitReviewRequest(owner, name, number, review, command),
      RetryEligibility.Never,
    )(using PullRequestDecoders.review)

  /** Deletes a review — `DELETE /repos/{owner}/{repo}/pulls/{index}/reviews/{id}`.
    *
    * '''Irreversible, and different from dismissing.''' [[dismiss]] leaves the review on the pull request with
    * [[Review.isDismissed]] set, so the reasoning stays readable and [[undismiss]] can put it back; this removes
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
  def delete(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
  ): Future[Unit] =
    pipeline.callUnit(PullRequestReviewApi.deleteReviewRequest(owner, name, number, review), RetryEligibility.AlwaysRetry)

  /** Dismisses a review — `POST /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/dismissals`.
    *
    * Takes a review out of the base branch's required-approval count without deleting it: the row stays on [[list]]
    * with [[Review.isDismissed]] set, and [[undismiss]] reverses it. [[DismissReview.withMessage]] is worth
    * setting — the message is the only explanation the reviewer ever sees — and [[DismissReview.includingPriors]]
    * extends the dismissal to that reviewer's earlier reviews of the same pull request.
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
  def dismiss(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
      command: DismissReview,
  ): Future[Review] =
    pipeline.call(
      PullRequestReviewApi.dismissReviewRequest(owner, name, number, review, command),
      RetryEligibility.AlwaysRetry,
    )(using PullRequestDecoders.review)

  /** Reverses a dismissal — `POST /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/undismissals`.
    *
    * Puts a dismissed review back into the approval count. It carries no body: the review id is the whole request, and
    * there is nothing to say about restoring one.
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], for the reason
    * [[dismiss]] gives — it clears a flag on one review named by an instance-wide id, and clearing it twice
    * leaves the same state.
    *
    * '''Answers `200`''' with the restored review as the body.
    *
    * '''Failures.''' The group contract above, plus `403` when the credentials may not manage reviews here and `422`
    * when the review was not dismissed in the first place.
    */
  def undismiss(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
  ): Future[Review] =
    pipeline.call(
      PullRequestReviewApi.undismissReviewRequest(owner, name, number, review),
      RetryEligibility.AlwaysRetry,
    )(using PullRequestDecoders.review)

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
  def comments(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
  ): Future[Vector[ReviewComment]] =
    pipeline.call(
      PullRequestReviewApi.reviewCommentsRequest(owner, name, number, review),
      RetryEligibility.IdempotentOnly,
    )(using PullRequestDecoders.reviewComments)

  /** Adds one inline comment to a review — `POST /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments`.
    *
    * Used to build up a '''pending''' review one remark at a time, then finish it with [[submit]]. Posting every
    * remark with the review in a single call is [[create]] with [[CreateReview.commenting]] instead, and it is
    * the cheaper of the two when the remarks are known up front.
    *
    * '''Never retried.''' It creates a comment, and a repeat after a lost response leaves the same remark on the diff
    * twice — Forgejo does not deduplicate. Confirming with [[comments]] is the reliable way to find out whether
    * the first attempt landed.
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
  def createComment(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
      comment: NewReviewComment,
  ): Future[ReviewComment] =
    pipeline.call(
      PullRequestReviewApi.createReviewCommentRequest(owner, name, number, review, comment),
      RetryEligibility.Never,
    )(using PullRequestDecoders.reviewComment)

  /** Reads one inline comment — `GET /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments/{comment}`.
    *
    * '''Both ids are needed and they are not interchangeable.''' The review's [[ReviewId]] and the comment's
    * [[ReviewCommentId]] occupy adjacent path segments and are both `int64`; swapping them produces a `404` that reads
    * like a deleted comment. The two opaque types are what keep that from compiling.
    *
    * '''Failures.''' The group contract above, plus `403`, which this endpoint declares and its listing does not —
    * Forgejo can refuse an individual comment on a diff the credentials may not read.
    */
  def getComment(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
      comment: ReviewCommentId,
  ): Future[ReviewComment] =
    pipeline.call(
      PullRequestReviewApi.getReviewCommentRequest(owner, name, number, review, comment),
      RetryEligibility.IdempotentOnly,
    )(using PullRequestDecoders.reviewComment)

  /** Deletes one inline comment — `DELETE /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments/{comment}`.
    *
    * '''Irreversible''', and it removes the remark from the diff rather than marking it resolved — resolving a
    * conversation is a web-UI action this API does not expose, and it shows up on [[ReviewComment.resolver]].
    *
    * '''Retried''' under [[com.worxbend.codeberg4s.core.RetryEligibility.AlwaysRetry]], for the reason [[delete]]
    * gives: the comment is named by an instance-wide id that Forgejo never reuses, so a repeat cannot reach a different
    * comment, and the end state is the same however many attempts it took.
    *
    * '''Answers `204` with no body''', so nothing is decoded.
    *
    * '''Failures.''' The group contract above, plus `403` when the credentials may not delete this comment.
    */
  def deleteComment(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      review: ReviewId,
      comment: ReviewCommentId,
  ): Future[Unit] =
    pipeline.callUnit(
      PullRequestReviewApi.deleteReviewCommentRequest(owner, name, number, review, comment),
      RetryEligibility.AlwaysRetry,
    )

/** The requests this group issues, its operation ids, and its typed rail. */
object PullRequestReviewApi:

  /** The stable operation id of [[PullRequestReviewApi.list]]. */
  val ListReviewsOperation: String = "pulls.reviews.list"

  /** The stable operation id of [[PullRequestReviewApi.request]]. */
  val RequestReviewsOperation: String = "pulls.reviewRequests.create"

  /** The stable operation id of [[PullRequestReviewApi.removeRequests]]. */
  val RemoveReviewRequestsOperation: String = "pulls.reviewRequests.delete"

  /** The stable operation id of [[PullRequestReviewApi.create]]. */
  val CreateReviewOperation: String = "pulls.reviews.create"

  /** The stable operation id of [[PullRequestReviewApi.get]]. */
  val GetReviewOperation: String = "pulls.reviews.get"

  /** The stable operation id of [[PullRequestReviewApi.submit]]. */
  val SubmitReviewOperation: String = "pulls.reviews.submit"

  /** The stable operation id of [[PullRequestReviewApi.delete]]. */
  val DeleteReviewOperation: String = "pulls.reviews.delete"

  /** The stable operation id of [[PullRequestReviewApi.dismiss]]. */
  val DismissReviewOperation: String = "pulls.reviews.dismiss"

  /** The stable operation id of [[PullRequestReviewApi.undismiss]]. */
  val UndismissReviewOperation: String = "pulls.reviews.undismiss"

  /** The stable operation id of [[PullRequestReviewApi.comments]]. */
  val ListReviewCommentsOperation: String = "pulls.reviews.comments.list"

  /** The stable operation id of [[PullRequestReviewApi.createComment]]. */
  val CreateReviewCommentOperation: String = "pulls.reviews.comments.create"

  /** The stable operation id of [[PullRequestReviewApi.getComment]]. */
  val GetReviewCommentOperation: String = "pulls.reviews.comments.get"

  /** The stable operation id of [[PullRequestReviewApi.deleteComment]]. */
  val DeleteReviewCommentOperation: String = "pulls.reviews.comments.delete"

  /** The typed rail of [[PullRequestReviewApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as
    * a value.
    *
    * Obtained as `client.pulls.reviews.attempt`. Each method is the convenience-rail method with its failure channel
    * materialised, so an operation exists on exactly one of the rails only if it is missing from both.
    */
  final class Attempt private[codeberg4s] (rail: PullRequestReviewApi)(using exec: Exec[Future]):

    /** [[PullRequestReviewApi.list]] with its failure as a value. */
    def list(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        params: PageParams,
    ): Future[Either[CodebergError, Page[Review]]] =
      exec.attempt(rail.list(owner, name, number, params))

    /** [[PullRequestReviewApi.request]] with its failure as a value. */
    def request(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        request: ReviewRequest,
    ): Future[Either[CodebergError, Vector[Review]]] =
      exec.attempt(rail.request(owner, name, number, request))

    /** [[PullRequestReviewApi.removeRequests]] with its failure as a value. */
    def removeRequests(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        request: ReviewRequest,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.removeRequests(owner, name, number, request))

    /** [[PullRequestReviewApi.create]] with its failure as a value. */
    def create(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        command: CreateReview,
    ): Future[Either[CodebergError, Review]] =
      exec.attempt(rail.create(owner, name, number, command))

    /** [[PullRequestReviewApi.get]] with its failure as a value. */
    def get(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
    ): Future[Either[CodebergError, Review]] =
      exec.attempt(rail.get(owner, name, number, review))

    /** [[PullRequestReviewApi.submit]] with its failure as a value — including the `422` that means the review was no
      * longer pending.
      */
    def submit(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
        command: SubmitReview,
    ): Future[Either[CodebergError, Review]] =
      exec.attempt(rail.submit(owner, name, number, review, command))

    /** [[PullRequestReviewApi.delete]] with its failure as a value. */
    def delete(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.delete(owner, name, number, review))

    /** [[PullRequestReviewApi.dismiss]] with its failure as a value. */
    def dismiss(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
        command: DismissReview,
    ): Future[Either[CodebergError, Review]] =
      exec.attempt(rail.dismiss(owner, name, number, review, command))

    /** [[PullRequestReviewApi.undismiss]] with its failure as a value. */
    def undismiss(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
    ): Future[Either[CodebergError, Review]] =
      exec.attempt(rail.undismiss(owner, name, number, review))

    /** [[PullRequestReviewApi.comments]] with its failure as a value. */
    def comments(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
    ): Future[Either[CodebergError, Vector[ReviewComment]]] =
      exec.attempt(rail.comments(owner, name, number, review))

    /** [[PullRequestReviewApi.createComment]] with its failure as a value. */
    def createComment(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
        comment: NewReviewComment,
    ): Future[Either[CodebergError, ReviewComment]] =
      exec.attempt(rail.createComment(owner, name, number, review, comment))

    /** [[PullRequestReviewApi.getComment]] with its failure as a value. */
    def getComment(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
        comment: ReviewCommentId,
    ): Future[Either[CodebergError, ReviewComment]] =
      exec.attempt(rail.getComment(owner, name, number, review, comment))

    /** [[PullRequestReviewApi.deleteComment]] with its failure as a value. */
    def deleteComment(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
        comment: ReviewCommentId,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteComment(owner, name, number, review, comment))

  private def reviewsRequest(
      owner: Owner,
      name: RepoName,
      number: PullRequestNumber,
      params: PageParams,
  ): CodebergRequest =
    read(ListReviewsOperation, reviewsPath(owner, name, number), PagingQuery.window(params))

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
    empty(UndismissReviewOperation, HttpMethod.Post, reviewPath(owner, name, number, review) :+ "undismissals")

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

