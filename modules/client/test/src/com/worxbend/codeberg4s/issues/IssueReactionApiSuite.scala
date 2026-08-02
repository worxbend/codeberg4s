package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.CodebergError

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend

import munit.FunSuite

import scala.concurrent.Future

/** [[IssueReactionApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * '''The payloads here are hand-written to match `spec/swagger.v1.json`, not captured''' — no golden fixture contains
  * a reaction. What is asserted is the wiring, including the two things about this endpoint family that are easy to get
  * wrong: the removals are `DELETE`s '''with''' a body, and the comment listing is not paged while the issue listing
  * is.
  */
final class IssueReactionApiSuite extends FunSuite with IssueLaneHarness:

  private val ThumbsUp: ReactionContent = ReactionContent.ThumbsUp

  private val Rocket: ReactionContent = orFail(ReactionContent.from("rocket"))

  private val ReactionBody: String =
    """{"content": "+1", "user": {"id": 532348, "login": "jkassel"}, "created_at": "2026-07-31T17:20:04+02:00"}"""

  test("the issue reaction listing is paged, because the spec declares page and limit for it"):
    val backend = RecordingBackend(responding(200, s"[$ReactionBody]"))

    onApi(backend): api =>
      api.listOnIssue(Handle, Name, Number, window(2, 25)).map: page =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/reactions")
        assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "25"))
        assertEquals(page.items.map(_.content.value), Vector("+1"))

  test("the comment reaction listing is not paged, because the spec declares no page or limit for it"):
    val backend = RecordingBackend(responding(200, s"[$ReactionBody]"))

    onApi(backend): api =>
      api.listOnComment(Handle, Name, CommentRef).map: reactions =>
        assertEquals(
          pathOf(backend),
          "https://forge.example/api/v1/repos/Codeberg/Community/issues/comments/20366420/reactions",
        )
        assertEquals(queryOf(backend), Nil)
        assertEquals(reactions.size, 1)

  test("adding a reaction POSTs the content, and a custom emoji is sent verbatim"):
    val backend = RecordingBackend(responding(201, ReactionBody))

    onApi(backend): api =>
      api.addToIssue(Handle, Name, Number, Rocket).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(bodyOf(backend), """{"content":"rocket"}""")

  test("a 200 on an add is a success too, which is what an account that had already reacted gets"):
    onApi(responding(200, ReactionBody)): api =>
      api.addToIssue(Handle, Name, Number, ThumbsUp).map(reaction => assertEquals(reaction.content.value, "+1"))

  test("adding a reaction is never retried, because it is a POST"):
    val backend = RecordingBackend(flakyThen(201, ReactionBody))

    onApi(backend): api =>
      api.attempt
        .addToIssue(Handle, Name, Number, ThumbsUp)
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a POST must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("removing a reaction is a DELETE that carries the content in its body, because the URL cannot say which"):
    val backend = RecordingBackend(responding(200, ""))

    onApi(backend): api =>
      api.removeFromIssue(Handle, Name, Number, ThumbsUp).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(bodyOf(backend), """{"content":"+1"}""")

  test("removing a reaction is retried, because the end state after N attempts is the end state after one"):
    val backend = RecordingBackend(flakyThen(200, ""))

    onApi(backend): api =>
      api
        .removeFromIssue(Handle, Name, Number, ThumbsUp)
        .map(_ => assertEquals(backend.allInteractions.size, 2, "the 503 on a removal was not retried"))

  test("removing a comment reaction targets the comment and carries the same body"):
    val backend = RecordingBackend(responding(200, ""))

    onApi(backend): api =>
      api
        .removeFromComment(Handle, Name, CommentRef, Rocket)
        .map: _ =>
          assertEquals(
            pathOf(backend),
            "https://forge.example/api/v1/repos/Codeberg/Community/issues/comments/20366420/reactions",
          )
          assertEquals(bodyOf(backend), """{"content":"rocket"}""")

  test("a 403 — which is what an unsupported reaction produces — reaches both rails as the very same failure"):
    onApi(responding(403, IssueReactionApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.addToIssue(Handle, Name, Number, Rocket).failed
        typed  <- api.attempt.addToIssue(Handle, Name, Number, Rocket)
      yield assertRailsAgree(raised, typed)

  test("a reaction with no content becomes DecodingFailed at $.content, never a silent empty string"):
    onApi(responding(200, """{"user":{"id":1,"login":"someone"}}""")): api =>
      api.attempt.addToIssue(Handle, Name, Number, ThumbsUp).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.content")
        case other                                             => fail(s"expected a decoding failure, got $other")

  private def onApi[A](backend: Backend[Future])(use: IssueReactionApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(IssueReactionApi(pipeline)))

/** The error bodies this suite stubs. */
object IssueReactionApiSuite:

  private val ForbiddenBody: String =
    """{"message":"PostIssueReaction","url":"https://codeberg.org/api/swagger","errors":["reaction not allowed"]}"""
