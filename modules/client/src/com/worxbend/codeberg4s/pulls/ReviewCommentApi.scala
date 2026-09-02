package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.core.CodebergRequest.{read, remove, write}
import com.worxbend.codeberg4s.core.{ApiPipeline, CodebergRequest, Exec, RetryEligibility}
import com.worxbend.codeberg4s.pulls.PullRequestRequests.reviewPath
import com.worxbend.codeberg4s.pulls.wire.NewReviewCommentDto
import com.worxbend.codeberg4s.{CodebergError, HttpMethod, Owner, RepoName}

import scala.concurrent.Future

/** The remarks anchored to diff lines inside one review.
  *
  * Reached as `client.pulls.reviews.comments`. It is a group of its own rather than more methods on
  * [[PullRequestReviewApi]] because a review comment is addressed one level deeper than a review — every path here
  * carries both a [[ReviewId]] and, for three of the four, a [[ReviewCommentId]] — and because holding both in one
  * class made a file no reader could keep in their head.
  *
  * '''These are not the pull request's conversation.''' Ordinary comments live on the issue endpoints, because Forgejo
  * stores them there; see [[ReviewComment]] for the difference and for why the two line numbers are the way they are.
  *
  * Both error rails are here (ADR-0005): the methods on this class fail the `Future` with
  * [[com.worxbend.codeberg4s.CodebergException]], and the same operations on [[ReviewCommentApi.attempt]] never fail
  * and return an `Either` instead. The typed rail is derived from this one by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so the two cannot disagree about what an operation does.
  *
  * ==Failures==
  *
  * Every operation here can produce the same four remote failures, so they are stated once rather than repeated on each
  * method; the per-method Scaladoc adds only what is specific to that endpoint.
  *
  *   - [[com.worxbend.codeberg4s.CodebergError.Api]] with status `404` when the repository, the pull request, the
  *     review or the comment does not exist '''or''' is invisible to the credentials in use, `401` when a token was
  *     required and none was sent, and `403` when the token lacks the scope. `422` '''and''' `400` both mean the
  *     request was rejected as invalid.
  *   - [[com.worxbend.codeberg4s.CodebergError.Transport]] when nothing reached the instance.
  *   - [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] when a `2xx` payload did not match the model; `path`
  *     names the offending field.
  *   - [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] when a retryable failure outlived the policy.
  *
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is '''not''' produced by any operation here. Every argument is
  * an already-validated type, so a value that would forge a path is rejected by its own smart constructor before a
  * client is ever involved.
  *
  * ==Not paged==
  *
  * [[comments]] declares no `page` or `limit` in the pinned spec, so the instance sends the whole set and the listing
  * returns a plain `Vector` rather than a [[com.worxbend.codeberg4s.paging.Page]] that would have nothing to report.
  *
  * ==Evidence==
  *
  * '''No golden capture backs any of this.''' `golden/MANIFEST.md` holds no review-comment payload — every review the
  * anonymous harvest could reach carried `comments_count: 0` — so the model is derived from `spec/swagger.v1.json` and
  * says so on its own type. Should a capture ever contradict it, the capture wins.
  */

final class ReviewCommentApi private[codeberg4s] (pipeline: ApiPipeline[Future])(using exec: Exec[Future]):

  /** The same operations, with failures as values instead of as a failed `Future`. */
  val attempt: ReviewCommentApi.Attempt = ReviewCommentApi.Attempt(this)

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
      ReviewCommentApi.reviewCommentsRequest(owner, name, number, review),
      RetryEligibility.IdempotentOnly,
    )(using PullRequestDecoders.reviewComments)

  /** Adds one inline comment to a review — `POST /repos/{owner}/{repo}/pulls/{index}/reviews/{id}/comments`.
    *
    * Used to build up a '''pending''' review one remark at a time, then finish it with [[submit]]. Posting every remark
    * with the review in a single call is [[create]] with [[CreateReview.commenting]] instead, and it is the cheaper of
    * the two when the remarks are known up front.
    *
    * '''Never retried.''' It creates a comment, and a repeat after a lost response leaves the same remark on the diff
    * twice — Forgejo does not deduplicate. Confirming with [[comments]] is the reliable way to find out whether the
    * first attempt landed.
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
      ReviewCommentApi.createReviewCommentRequest(owner, name, number, review, comment),
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
      ReviewCommentApi.getReviewCommentRequest(owner, name, number, review, comment),
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
      ReviewCommentApi.deleteReviewCommentRequest(owner, name, number, review, comment),
      RetryEligibility.AlwaysRetry,
    )

/** The requests this group issues, its operation ids, and its typed rail. */
object ReviewCommentApi:

  /** The stable operation id of [[ReviewCommentApi.comments]]. */
  val ListReviewCommentsOperation: String = "pulls.reviews.comments.list"

  /** The stable operation id of [[ReviewCommentApi.createComment]]. */
  val CreateReviewCommentOperation: String = "pulls.reviews.comments.create"

  /** The stable operation id of [[ReviewCommentApi.getComment]]. */
  val GetReviewCommentOperation: String = "pulls.reviews.comments.get"

  /** The stable operation id of [[ReviewCommentApi.deleteComment]]. */
  val DeleteReviewCommentOperation: String = "pulls.reviews.comments.delete"

  /** The typed rail of [[ReviewCommentApi]]: every operation, with [[com.worxbend.codeberg4s.CodebergError]] as a
    * value.
    *
    * Obtained as `client.pulls.reviews.comments.attempt`. Each method is the convenience-rail method with its failure
    * channel materialised and nothing else, so an operation exists on exactly one of the rails only if it is missing
    * from both.
    */
  final class Attempt private[codeberg4s] (rail: ReviewCommentApi)(using exec: Exec[Future]):

    /** [[ReviewCommentApi.comments]] with its failure as a value. */
    def comments(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
    ): Future[Either[CodebergError, Vector[ReviewComment]]] =
      exec.attempt(rail.comments(owner, name, number, review))

    /** [[ReviewCommentApi.createComment]] with its failure as a value. */
    def createComment(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
        comment: NewReviewComment,
    ): Future[Either[CodebergError, ReviewComment]] =
      exec.attempt(rail.createComment(owner, name, number, review, comment))

    /** [[ReviewCommentApi.getComment]] with its failure as a value. */
    def getComment(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
        comment: ReviewCommentId,
    ): Future[Either[CodebergError, ReviewComment]] =
      exec.attempt(rail.getComment(owner, name, number, review, comment))

    /** [[ReviewCommentApi.deleteComment]] with its failure as a value. */
    def deleteComment(
        owner: Owner,
        name: RepoName,
        number: PullRequestNumber,
        review: ReviewId,
        comment: ReviewCommentId,
    ): Future[Either[CodebergError, Unit]] =
      exec.attempt(rail.deleteComment(owner, name, number, review, comment))

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
