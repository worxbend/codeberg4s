package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.ClientSuiteHarness
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.users.Username

import sttp.client4.Backend
import sttp.client4.testing.BackendStub
import sttp.client4.testing.RecordingBackend
import sttp.client4.testing.ResponseStub
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.Future

/** The review-and-reviewer half of [[PullRequestApi]] over a `BackendStub`: nothing here opens a socket.
  *
  * `PullRequestReviewApiSuite` covers the eight operations this group started with. This one covers the eighteen added
  * after them, and the subject is the same: which URI is dialled, which method and body are sent, which failures are
  * retried, and that both rails report a failure identically.
  */
final class PullRequestReviewApiSuite extends FunSuite with ClientSuiteHarness:

  /** The prefix every asserted path starts with: the pull-request surface every path in this suite hangs off. */
  private val Endpoint: String = s"$Root/repos/forgejo/forgejo/pulls"

  private val Handle: Owner = orFail(Owner.from("forgejo"))

  private val Name: RepoName = orFail(RepoName.from("forgejo"))

  private val Number: PullRequestNumber = orFail(PullRequestNumber.from(13726L))

  private val Reviewed: ReviewId = orFail(ReviewId.from(1654076L))

  private val CommentId: ReviewCommentId = orFail(ReviewCommentId.from(918273L))

  private val Base: BranchName = orFail(BranchName.from("forgejo"))

  private val Topic: BranchName = orFail(BranchName.from("fix-pep691"))

  private val Head: CommitSha = orFail(CommitSha.from("48079baa8d387f3ab770cc144c367409ddc2a879"))

  private val Reviewer: Username = orFail(Username.from("mfenniak"))

  // --- the reads that are not paged -----------------------------------------

  test("pulls.pinned.list asks for the repository's pinned shortlist and sends no paging"):
    val backend = RecordingBackend(responding(200, PullRequestReviewApiSuite.PullListBody))

    onApi(backend): api =>
      api.listPinned(Handle, Name).map: pinned =>
        assertEquals(pathOf(backend), s"$Endpoint/pinned")
        assertEquals(queryOf(backend), Nil)
        assertEquals(pinned.map(_.number.value), Vector(13726L))

  test("nothing pinned is an empty vector, not a failure — Forgejo answers 200 with []"):
    onApi(responding(200, "[]")): api =>
      api.listPinned(Handle, Name).map(pinned => assertEquals(pinned, Vector.empty[PullRequest]))

  test("pulls.getByBaseHead puts the two branches in the path, base first"):
    val backend = RecordingBackend(responding(200, PullRequestReviewApiSuite.MergedBody))

    onApi(backend): api =>
      api.getByBaseHead(Handle, Name, Base, PullRequestHead.branch(Topic)).map: pull =>
        assertEquals(methodOf(backend), "GET")
        assertEquals(pathOf(backend), s"$Endpoint/forgejo/fix-pep691")
        assertEquals(pull.number.value, 13726L)

  // --- the diff and the patch -----------------------------------------------

  test("pulls.download puts the format in the path as an extension, not in the query"):
    val backend = RecordingBackend(responding(200, PullRequestReviewApiSuite.DiffBody))

    onApi(backend): api =>
      api.download(Handle, Name, Number, DiffRequest.of(DiffFormat.Diff)).map: text =>
        assertEquals(pathOf(backend), s"$Endpoint/13726.diff")
        assertEquals(queryOf(backend), Nil)
        assertEquals(text, PullRequestReviewApiSuite.DiffBody)

  test("a patch is a different path segment, and the body is handed back unparsed"):
    val backend = RecordingBackend(responding(200, PullRequestReviewApiSuite.DiffBody))

    onApi(backend): api =>
      api
        .download(Handle, Name, Number, DiffRequest.of(DiffFormat.Patch).includingBinary)
        .map: _ =>
          assertEquals(pathOf(backend), s"$Endpoint/13726.patch")
          assertEquals(queryOf(backend), List("binary" -> "true"))

  test("a diff that is not JSON still succeeds, because nothing parses it"):
    onApi(responding(200, "-----BEGIN NOT JSON-----")): api =>
      api
        .download(Handle, Name, Number, DiffRequest.of(DiffFormat.Diff))
        .map(text => assertEquals(text, "-----BEGIN NOT JSON-----"))

  // --- the merge status -----------------------------------------------------

  test("a 204 from the merge probe means merged"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.isMerged(Handle, Name, Number).map: merged =>
        assertEquals(pathOf(backend), s"$Endpoint/13726/merge")
        assertEquals(methodOf(backend), "GET")
        assertEquals(merged, true)

  test("a 404 from the merge probe is an answer, not a failure"):
    onApi(responding(404, PullRequestReviewApiSuite.NotFoundBody)): api =>
      api.isMerged(Handle, Name, Number).map(merged => assertEquals(merged, false))

  test("the typed rail reports the same 404 as Right(false), so the rails do not disagree about it"):
    onApi(responding(404, PullRequestReviewApiSuite.NotFoundBody)): api =>
      api.attempt.isMerged(Handle, Name, Number).map(outcome => assertEquals(outcome, Right(false)))

  test("every other status still travels on the error channel, so an unreadable repository is not 'unmerged'"):
    onApi(responding(403, PullRequestReviewApiSuite.NotFoundBody)): api =>
      for
        raised <- api.isMerged(Handle, Name, Number).failed
        typed  <- api.attempt.isMerged(Handle, Name, Number)
      yield
        assertEquals(operationOf(typed), PullRequestApi.MergeStatusOperation)
        assertRailsAgree(raised, typed)

  test("pulls.merge.cancel deletes the scheduled merge and may be repeated"):
    val backend = RecordingBackend(cycling(503, 204, ""))

    onApi(backend): api =>
      api.cancelScheduledMerge(Handle, Name, Number).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Endpoint/13726/merge")
        assertEquals(backend.allInteractions.size, 2, "an idempotent cancel was not retried")

  // --- updating the branch --------------------------------------------------

  test("pulls.update names the style in the query and sends no body"):
    val backend = RecordingBackend(responding(200, ""))

    onApi(backend): api =>
      api.updateBranch(Handle, Name, Number, UpdateStyle.Rebase).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Endpoint/13726/update")
        assertEquals(queryOf(backend), List("style" -> "rebase"))

  /** A retried rebase would rewrite the branch a second time, off a base that may have moved in between. */
  test("pulls.update is never retried, because it rewrites a branch"):
    val backend = RecordingBackend(cycling(503, 200, ""))

    onApi(backend): api =>
      api.attempt
        .updateBranch(Handle, Name, Number, UpdateStyle.Merge)
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on update must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the update was sent twice")

  // --- requested reviewers --------------------------------------------------

  test("pulls.reviewRequests.create posts the reviewers and decodes the rows it created"):
    val backend = RecordingBackend(responding(201, PullRequestReviewApiSuite.ReviewListBody))

    onApi(backend): api =>
      api.requestReviews(Handle, Name, Number, ReviewRequest.of(Reviewer)).map: reviews =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Endpoint/13726/requested_reviewers")
        assertEquals(bodyOf(backend), """{"reviewers":["mfenniak"]}""")
        assertEquals(reviews.map(_.state), Vector(Some(ReviewState.Approved)))

  test("pulls.reviewRequests.create is never retried, because it creates rows and re-notifies"):
    val backend = RecordingBackend(cycling(503, 201, PullRequestReviewApiSuite.ReviewListBody))

    onApi(backend): api =>
      api.attempt
        .requestReviews(Handle, Name, Number, ReviewRequest.of(Reviewer))
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a review request must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the request was sent twice")

  test("pulls.reviewRequests.delete is a DELETE that carries a JSON body, which this endpoint requires"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.removeReviewRequests(Handle, Name, Number, ReviewRequest.of(Reviewer)).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Endpoint/13726/requested_reviewers")
        assertEquals(bodyOf(backend), """{"reviewers":["mfenniak"]}""")

  test("withdrawing a request may be repeated, because the body names exactly who is to be removed"):
    val backend = RecordingBackend(cycling(503, 204, ""))

    onApi(backend): api =>
      api
        .removeReviewRequests(Handle, Name, Number, ReviewRequest.of(Reviewer))
        .map(_ => assertEquals(backend.allInteractions.size, 2, "an idempotent withdrawal was not retried"))

  // --- reviews --------------------------------------------------------------

  test("pulls.reviews.create posts the whole review, remarks included"):
    val backend = RecordingBackend(responding(200, PullRequestReviewApiSuite.ReviewBody))
    val command = CreateReview.Empty
      .saying(ReviewState.RequestChanges)
      .withBody("the quoting is still wrong")
      .against(Head)
      .commenting(orFail(NewReviewComment.onNewLine("modules/git/hook.go", 42L, "here")))

    onApi(backend): api =>
      api.createReview(Handle, Name, Number, command).map: review =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Endpoint/13726/reviews")
        assertEquals(
          bodyOf(backend),
          s"""{"body":"the quoting is still wrong","event":"REQUEST_CHANGES","commit_id":"${Head.value}",""" +
            """"comments":[{"body":"here","path":"modules/git/hook.go","new_position":42}]}""",
        )
        assertEquals(review.id.value, 1654076L)

  test("pulls.reviews.create is never retried, because a repeat leaves two reviews"):
    val backend = RecordingBackend(cycling(503, 200, PullRequestReviewApiSuite.ReviewBody))

    onApi(backend): api =>
      api.attempt
        .createReview(Handle, Name, Number, CreateReview.Empty)
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a review must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the review was posted twice")

  test("the single-review read addresses a review by its instance-wide id"):
    val backend = RecordingBackend(responding(200, PullRequestReviewApiSuite.ReviewBody))

    onApi(backend): api =>
      api.getReview(Handle, Name, Number, Reviewed).map: review =>
        assertEquals(pathOf(backend), s"$Endpoint/13726/reviews/1654076")
        assertEquals(review.state, Some(ReviewState.Approved))

  test("pulls.reviews.submit posts the event to the review's own path"):
    val backend = RecordingBackend(responding(200, PullRequestReviewApiSuite.ReviewBody))

    onApi(backend): api =>
      api
        .submitReview(Handle, Name, Number, Reviewed, SubmitReview.saying(ReviewState.Approved).withBody("ship it"))
        .map: _ =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), s"$Endpoint/13726/reviews/1654076")
          assertEquals(bodyOf(backend), """{"event":"APPROVED","body":"ship it"}""")

  test("pulls.reviews.submit is never retried, because a pending review is consumed by being submitted"):
    val backend = RecordingBackend(cycling(503, 200, PullRequestReviewApiSuite.ReviewBody))

    onApi(backend): api =>
      api.attempt
        .submitReview(Handle, Name, Number, Reviewed, SubmitReview.saying(ReviewState.Approved))
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the submission was sent twice"))

  test("pulls.reviews.delete removes the review and may be repeated, because ids are never reused"):
    val backend = RecordingBackend(cycling(503, 204, ""))

    onApi(backend): api =>
      api.deleteReview(Handle, Name, Number, Reviewed).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Endpoint/13726/reviews/1654076")
        assertEquals(backend.allInteractions.size, 2, "an idempotent delete was not retried")

  test("pulls.reviews.dismiss posts to the dismissals sub-resource and may be repeated"):
    val backend = RecordingBackend(cycling(503, 200, PullRequestReviewApiSuite.ReviewBody))

    onApi(backend): api =>
      api
        .dismissReview(Handle, Name, Number, Reviewed, DismissReview.Empty.withMessage("superseded"))
        .map: _ =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), s"$Endpoint/13726/reviews/1654076/dismissals")
          assertEquals(bodyOf(backend), """{"message":"superseded"}""")
          assertEquals(backend.allInteractions.size, 2, "an idempotent dismissal was not retried")

  test("pulls.reviews.undismiss posts an empty body, because the id is the whole request"):
    val backend = RecordingBackend(responding(200, PullRequestReviewApiSuite.ReviewBody))

    onApi(backend): api =>
      api.undismissReview(Handle, Name, Number, Reviewed).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Endpoint/13726/reviews/1654076/undismissals")
        assertEquals(bodyOf(backend), "")

  // --- review comments ------------------------------------------------------

  test("pulls.reviews.comments.list reads the whole set, because the endpoint declares no paging"):
    val backend = RecordingBackend(responding(200, PullRequestReviewApiSuite.CommentListBody))

    onApi(backend): api =>
      api.listReviewComments(Handle, Name, Number, Reviewed).map: comments =>
        assertEquals(pathOf(backend), s"$Endpoint/13726/reviews/1654076/comments")
        assertEquals(queryOf(backend), Nil)
        assertEquals(comments.map(_.id.value), Vector(918273L))
        assertEquals(comments.flatMap(_.path), Vector("modules/git/hook_generate.go"))

  test("pulls.reviews.comments.create posts one remark and is never retried"):
    val backend = RecordingBackend(cycling(503, 200, PullRequestReviewApiSuite.CommentBody))
    val remark  = orFail(NewReviewComment.onNewLine("modules/git/hook_generate.go", 42L, "still wrong"))

    onApi(backend): api =>
      api.attempt
        .createReviewComment(Handle, Name, Number, Reviewed, remark)
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a remark must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the remark was posted twice")

  test("a created remark is sent as CreatePullReviewComment, verbatim"):
    val backend = RecordingBackend(responding(200, PullRequestReviewApiSuite.CommentBody))
    val remark  = orFail(NewReviewComment.onOldLine("modules/git/hook_generate.go", 40L, "was fine"))

    onApi(backend): api =>
      api.createReviewComment(Handle, Name, Number, Reviewed, remark).map: comment =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Endpoint/13726/reviews/1654076/comments")
        assertEquals(
          bodyOf(backend),
          """{"body":"was fine","path":"modules/git/hook_generate.go","old_position":40}""",
        )
        assertEquals(comment.id.value, 918273L)

  test("the single-comment read keeps the two ids in the order the path declares them"):
    val backend = RecordingBackend(responding(200, PullRequestReviewApiSuite.CommentBody))

    onApi(backend): api =>
      api
        .getReviewComment(Handle, Name, Number, Reviewed, CommentId)
        .map(_ => assertEquals(pathOf(backend), s"$Endpoint/13726/reviews/1654076/comments/918273"))

  test("pulls.reviews.comments.delete removes one remark and may be repeated"):
    val backend = RecordingBackend(cycling(503, 204, ""))

    onApi(backend): api =>
      api.deleteReviewComment(Handle, Name, Number, Reviewed, CommentId).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Endpoint/13726/reviews/1654076/comments/918273")
        assertEquals(backend.allInteractions.size, 2, "an idempotent delete was not retried")

  // --- failures -------------------------------------------------------------

  test("both rails report a review read's 404 identically, so the choice of rail is only a choice of style"):
    onApi(responding(404, PullRequestReviewApiSuite.NotFoundBody)): api =>
      for
        raised <- api.getReview(Handle, Name, Number, Reviewed).failed
        typed  <- api.attempt.getReview(Handle, Name, Number, Reviewed)
      yield
        assertEquals(operationOf(typed), PullRequestApi.GetReviewOperation)
        assertRailsAgree(raised, typed)

  test("both rails report a remark's 422 identically as well"):
    val remark = orFail(NewReviewComment.onNewLine("a.go", 1L, "x"))

    onApi(responding(422, PullRequestReviewApiSuite.ValidationBody)): api =>
      for
        raised <- api.createReviewComment(Handle, Name, Number, Reviewed, remark).failed
        typed  <- api.attempt.createReviewComment(Handle, Name, Number, Reviewed, remark)
      yield assertRailsAgree(raised, typed)

  test("a review payload that does not fit the model becomes DecodingFailed, never an escaping exception"):
    onApi(responding(200, """{"state":"APPROVED"}""")): api =>
      api.attempt.getReview(Handle, Name, Number, Reviewed).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.id")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a bad element of a comment listing reports its position, all the way through the pipeline"):
    onApi(responding(200, """[{"id":1},{"id":0}]""")): api =>
      api.attempt.listReviewComments(Handle, Name, Number, Reviewed).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[1].id")
        case other                                             => fail(s"expected a decoding failure, got $other")

  // --- harness --------------------------------------------------------------

  /** Builds the API under test on a pipeline over `backend`, releasing the timer whatever happens. */
  private def onApi[A](backend: Backend[Future])(use: PullRequestApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(PullRequestApi(pipeline)))

  /** Fails once, then succeeds — the shape every retry-eligibility test needs. */
  private def cycling(first: Int, second: Int, body: String): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(
      ResponseStub.adjust("", StatusCode(first)),
      ResponseStub.adjust(body, StatusCode(second)),
    )

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour.
  *
  * The review payloads are reductions of `golden/pull/reviews-list.json`. The '''comment''' payloads are not reductions
  * of anything: `golden/MANIFEST.md` holds no review-comment capture, so they are shaped from `spec/swagger.v1.json`'s
  * `PullReviewComment` definition — see [[com.worxbend.codeberg4s.pulls.ReviewComment]].
  */
object PullRequestReviewApiSuite:

  /** `golden/pull/single-merged.json`, reduced to the keys these tests assert on. */
  private val MergedBody: String =
    """{
      |  "id": 2785448,
      |  "number": 13726,
      |  "title": "fix: bad quoting in hook scripts",
      |  "state": "closed",
      |  "merged": true
      |}""".stripMargin

  private val PullListBody: String = s"[$MergedBody]"

  private val ReviewBody: String =
    """{
      |  "id": 1654076,
      |  "state": "APPROVED",
      |  "user": {"id": 222642, "login": "mfenniak"},
      |  "team": null,
      |  "commit_id": "48079baa8d387f3ab770cc144c367409ddc2a879",
      |  "body": "",
      |  "html_url": ""
      |}""".stripMargin

  private val ReviewListBody: String = s"[$ReviewBody]"

  private val CommentBody: String =
    """{
      |  "id": 918273,
      |  "pull_request_review_id": 1654076,
      |  "body": "this quoting is still wrong",
      |  "path": "modules/git/hook_generate.go",
      |  "position": 42,
      |  "user": {"id": 222642, "login": "mfenniak"},
      |  "resolver": null
      |}""".stripMargin

  private val CommentListBody: String = s"[$CommentBody]"

  /** Not JSON, on purpose: the diff endpoint answers `text/plain` and nothing parses it. */
  private val DiffBody: String =
    "diff --git a/modules/git/hook_generate.go b/modules/git/hook_generate.go\n@@ -40,3 +40,3 @@\n"

  private val NotFoundBody: String =
    """{"message":"GetPullReview","url":"https://codeberg.org/api/swagger","errors":["review does not exist"]}"""

  private val ValidationBody: String =
    """{"message":"CreatePullReviewComment","url":"https://codeberg.org/api/swagger",""" +
      """"errors":["the file is not part of this pull request"]}"""
