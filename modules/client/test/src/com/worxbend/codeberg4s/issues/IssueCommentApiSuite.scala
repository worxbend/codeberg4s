package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.CodebergError

import sttp.client4.testing.RecordingBackend

import munit.FunSuite

import scala.concurrent.Future

import java.time.Instant

/** [[IssueCommentApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The subject is the wiring — which URI is dialled, which body is sent, and what each rail does with a failure.
  * Decoding is asserted against `golden/issue/comments-list.json` in `modules/codec`, so the payloads here are small
  * hand-written bodies chosen to exercise a seam.
  */
final class IssueCommentApiSuite extends FunSuite with IssueLaneHarness:

  private val CommentBody: String =
    """{"id": 20366420, "body": "looks right", "user": {"id": 532348, "login": "jkassel"}}"""

  test("the repository-wide comment listing targets /issues/comments and pages it"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .listForRepository(Handle, Name, CommentQuery.Empty, window(2, 25))
        .map: _ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/comments")
          assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "25"))

  test("the repository-wide comment listing sends only the window the caller set"):
    val backend = RecordingBackend(responding(200, "[]"))
    val query   = CommentQuery.Empty.updatedSince(Instant.parse("2026-07-01T00:00:00Z"))

    onApi(backend): api =>
      api
        .listForRepository(Handle, Name, query, window(1, 30))
        .map: _ =>
          assertEquals(
            queryOf(backend),
            List("since" -> "2026-07-01T00:00:00Z", "page" -> "1", "limit" -> "30"),
          )

  test("a single-comment read addresses the comment without its issue, because comment ids are instance-wide"):
    val backend = RecordingBackend(responding(200, CommentBody))

    onApi(backend): api =>
      api.get(Handle, Name, CommentRef).map: comment =>
        assertEquals(
          pathOf(backend),
          "https://forge.example/api/v1/repos/Codeberg/Community/issues/comments/20366420",
        )
        assertEquals(comment.map(_.body), Some(Some("looks right")))

  test("a 204 on the single-comment read is a success carrying nothing, not a decoding failure"):
    onApi(responding(204, "")): api =>
      api.get(Handle, Name, CommentRef).map(comment => assertEquals(comment, None))

  test("an edit PATCHes the replacement body, and nothing else unless the caller asked"):
    val backend = RecordingBackend(responding(200, CommentBody))

    onApi(backend): api =>
      api
        .edit(Handle, Name, CommentRef, orFail(EditComment.of("looks right")))
        .map: _ =>
          assertEquals(methodOf(backend), "PATCH")
          assertEquals(bodyOf(backend), """{"body":"looks right"}""")

  test("an edit that backdates itself sends updated_at as well"):
    val backend = RecordingBackend(responding(200, CommentBody))
    val command = orFail(EditComment.of("looks right")).recordedAt(Instant.parse("2026-07-01T00:00:00Z"))

    onApi(backend): api =>
      api
        .edit(Handle, Name, CommentRef, command)
        .map: _ =>
          assertEquals(bodyOf(backend), """{"body":"looks right","updated_at":"2026-07-01T00:00:00Z"}""")

  test("an edit is never retried, because the body replaces the comment rather than patching it"):
    val backend = RecordingBackend(flakyThen(200, CommentBody))

    onApi(backend): api =>
      api.attempt
        .edit(Handle, Name, CommentRef, orFail(EditComment.of("looks right")))
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on an edit must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the PATCH was retried")

  test("a delete is a bodiless DELETE and is retried, because it names one row id"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.delete(Handle, Name, CommentRef).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(bodyOf(backend), "empty")
        assertEquals(backend.allInteractions.size, 2, "the 503 on a delete was not retried")

  test("the deprecated edit route carries the issue number the current route does not"):
    val backend = RecordingBackend(responding(200, CommentBody))

    onApi(backend): api =>
      api
        .editDeprecated(Handle, Name, Number, CommentRef, orFail(EditComment.of("looks right")))
        .map: _ =>
          assertEquals(
            pathOf(backend),
            "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/comments/20366420",
          )

  test("the deprecated delete route carries the issue number too, and reports its own operation id"):
    val backend = RecordingBackend(responding(404, IssueLaneHarness.NotFoundBody))

    onApi(backend): api =>
      api.attempt.deleteDeprecated(Handle, Name, Number, CommentRef).map:
        case Left(CodebergError.Api(ctx, _, _, _)) =>
          assertEquals(ctx.operation, IssueCommentApi.DeleteDeprecatedOperation)
          assertEquals(
            pathOf(backend),
            "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/comments/20366420",
          )
        case other                                 => fail(s"expected an Api failure, got $other")

  test("a 404 reaches both rails as the very same failure"):
    onApi(responding(404, IssueLaneHarness.NotFoundBody)): api =>
      for
        raised <- api.get(Handle, Name, CommentRef).failed
        typed  <- api.attempt.get(Handle, Name, CommentRef)
      yield assertRailsAgree(raised, typed)

  test("a 200 whose payload does not fit the model becomes DecodingFailed, never an escaping codec exception"):
    onApi(responding(200, """{"body":"orphan"}""")): api =>
      api.attempt.get(Handle, Name, CommentRef).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.id")
        case other                                             => fail(s"expected a decoding failure, got $other")

  private def onApi[A](backend: sttp.client4.Backend[Future])(use: IssueCommentApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(IssueCommentApi(pipeline)))
