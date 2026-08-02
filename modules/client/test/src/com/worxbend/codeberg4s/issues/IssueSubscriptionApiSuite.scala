package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.repositories.Owner

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend

import munit.FunSuite

import scala.concurrent.Future

/** [[IssueSubscriptionApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The `WatchInfo` payload is hand-written to match `spec/swagger.v1.json` — it describes the authenticated account and
  * the golden harvest was anonymous. The `User` payload is the shape captured many times over in
  * `modules/codec/test/resources/golden`.
  */
final class IssueSubscriptionApiSuite extends FunSuite with IssueLaneHarness:

  private val Follower: Owner = orFail(Owner.from("jkassel"))

  private val WatchBody: String =
    """{
      |  "subscribed": true,
      |  "ignored": false,
      |  "url": "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966",
      |  "repository_url": "https://forge.example/api/v1/repos/Codeberg/Community",
      |  "created_at": "2026-07-31T17:20:04+02:00"
      |}""".stripMargin

  private val UserListBody: String = """[{"id": 532348, "login": "jkassel"}]"""

  test("the subscriber listing targets the issue's subscriptions and pages it"):
    val backend = RecordingBackend(responding(200, UserListBody))

    onApi(backend): api =>
      api.list(Handle, Name, Number, window(2, 25)).map: page =>
        assertEquals(
          pathOf(backend),
          "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/subscriptions",
        )
        assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "25"))
        assertEquals(page.items.map(_.login), Vector("jkassel"))

  test("the check reads the token's own status from a /check sub-path"):
    val backend = RecordingBackend(responding(200, WatchBody))

    onApi(backend): api =>
      api.check(Handle, Name, Number).map: status =>
        assertEquals(
          pathOf(backend),
          "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/subscriptions/check",
        )
        assertEquals(status.isSubscribed, true)
        assertEquals(status.isIgnored, false)

  test("a WatchInfo that says nothing decodes to neither subscribed nor ignored, rather than failing"):
    onApi(responding(200, "{}")): api =>
      api.check(Handle, Name, Number).map: status =>
        assertEquals(status.isSubscribed, false)
        assertEquals(status.isIgnored, false)
        assertEquals(status.url, None)

  test("subscribing is a PUT naming the account in the path, with an empty body"):
    val backend = RecordingBackend(responding(201, ""))

    onApi(backend): api =>
      api.subscribe(Handle, Name, Number, Follower).map: _ =>
        assertEquals(methodOf(backend), "PUT")
        assertEquals(
          pathOf(backend),
          "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/subscriptions/jkassel",
        )

  test("subscribing is retried, because it asks for an absolute end state and 200 means already subscribed"):
    val backend = RecordingBackend(flakyThen(200, ""))

    onApi(backend): api =>
      api
        .subscribe(Handle, Name, Number, Follower)
        .map(_ => assertEquals(backend.allInteractions.size, 2, "the 503 on a subscribe was not retried"))

  test("unsubscribing is a DELETE on the same path, and is retried for the same reason"):
    val backend = RecordingBackend(flakyThen(200, ""))

    onApi(backend): api =>
      api.unsubscribe(Handle, Name, Number, Follower).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(
          pathOf(backend),
          "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/subscriptions/jkassel",
        )
        assertEquals(backend.allInteractions.size, 2, "the 503 on an unsubscribe was not retried")

  test("a 304 is a failure, not a success — subscribing somebody else needs to be an administrator"):
    onApi(responding(304, "")): api =>
      api.attempt.subscribe(Handle, Name, Number, Follower).map:
        case Left(CodebergError.Api(ctx, status, _)) =>
          assertEquals(status, 304)
          assertEquals(ctx.operation, IssueSubscriptionApi.SubscribeOperation)
        case other                                   => fail(s"expected an Api failure, got $other")

  test("a 404 reaches both rails as the very same failure"):
    onApi(responding(404, IssueLaneHarness.NotFoundBody)): api =>
      for
        raised <- api.check(Handle, Name, Number).failed
        typed  <- api.attempt.check(Handle, Name, Number)
      yield assertRailsAgree(raised, typed)

  test("a bad element of the subscriber list reports its position, all the way through the pipeline"):
    onApi(responding(200, """[{"id":1,"login":"a"},{"login":"orphan"}]""")): api =>
      api.attempt.list(Handle, Name, Number, window(1, 30)).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[1].id")
        case other                                             => fail(s"expected a decoding failure, got $other")

  private def onApi[A](backend: Backend[Future])(use: IssueSubscriptionApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(IssueSubscriptionApi(pipeline)))
