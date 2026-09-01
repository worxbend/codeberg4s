package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.CodebergError

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend

import munit.FunSuite

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import java.time.Instant

/** [[IssueTimeApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * '''The payloads here are hand-written to match `spec/swagger.v1.json`, not captured''' — every timetracking endpoint
  * needs a token and the golden harvest was anonymous. What is asserted is the wiring, and in particular the one thing
  * a unit boundary always gets wrong somewhere: seconds on the wire, `FiniteDuration` in the domain, in both
  * directions.
  */
final class IssueTimeApiSuite extends FunSuite with IssueLaneHarness:

  private val Entry: TrackedTimeId = orFail(TrackedTimeId.from(474L))

  private val TrackedTimeBody: String =
    """{"id": 474, "time": 7200, "user_name": "jkassel", "created": "2026-07-31T17:20:04+02:00"}"""

  test("starting a stopwatch POSTs to the start verb with no body at all"):
    val backend = RecordingBackend(responding(201, ""))

    onApi(backend): api =>
      api.startStopwatch(Handle, Name, Number).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(
          pathOf(backend),
          "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/stopwatch/start",
        )
        assertEquals(bodyOf(backend), "empty")

  test("starting a stopwatch is never retried, because a repeat after a lost success answers 409"):
    val backend = RecordingBackend(flakyThen(201, ""))

    onApi(backend): api =>
      api.attempt
        .startStopwatch(Handle, Name, Number)
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a stopwatch start must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("stopping a stopwatch is a different verb from starting it, and also a POST"):
    val backend = RecordingBackend(responding(201, ""))

    onApi(backend): api =>
      api
        .stopStopwatch(Handle, Name, Number)
        .map: _ =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(
            pathOf(backend),
            "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/stopwatch/stop",
          )

  test("abandoning a stopwatch is a DELETE on a path that still spells the word delete, and is retried"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.deleteStopwatch(Handle, Name, Number).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(
          pathOf(backend),
          "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/stopwatch/delete",
        )
        assertEquals(backend.allInteractions.size, 2, "the 503 on a stopwatch cancel was not retried")

  test("the times listing pages, and turns the wire's seconds into a duration"):
    val backend = RecordingBackend(responding(200, s"[$TrackedTimeBody]"))

    onApi(backend): api =>
      api.list(Handle, Name, Number, TrackedTimeQuery.Empty, window(1, 30)).map: page =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/times")
        assertEquals(queryOf(backend), List("page" -> "1", "limit" -> "30"))
        assertEquals(page.items.map(_.spent), Vector(2.hours))

  test("the times listing sends only the filters the caller set"):
    val backend = RecordingBackend(responding(200, "[]"))
    val query   = TrackedTimeQuery.Empty.forUser("jkassel").recordedSince(Instant.parse("2026-07-01T00:00:00Z"))

    onApi(backend): api =>
      api
        .list(Handle, Name, Number, query, window(1, 30))
        .map: _ =>
          assertEquals(
            queryOf(backend),
            List("user" -> "jkassel", "since" -> "2026-07-01T00:00:00Z", "page" -> "1", "limit" -> "30"),
          )

  test("adding time POSTs seconds, because that is the unit the wire has"):
    val backend = RecordingBackend(responding(200, TrackedTimeBody))

    onApi(backend): api =>
      api.add(Handle, Name, Number, orFail(AddTrackedTime.of(2.hours))).map: entry =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(bodyOf(backend), """{"time":7200}""")
        assertEquals(entry.spent, 2.hours)

  test("adding time sends the account and the timestamp only when the caller named them"):
    val backend = RecordingBackend(responding(200, TrackedTimeBody))
    val command = orFail(AddTrackedTime.of(90.seconds))
      .attributedTo("jkassel")
      .recordedAt(Instant.parse("2026-07-01T00:00:00Z"))

    onApi(backend): api =>
      api
        .add(Handle, Name, Number, command)
        .map: _ =>
          assertEquals(bodyOf(backend), """{"time":90,"user_name":"jkassel","created":"2026-07-01T00:00:00Z"}""")

  test("adding time is never retried, because a repeat would report twice the work"):
    val backend = RecordingBackend(flakyThen(200, TrackedTimeBody))

    onApi(backend): api =>
      api.attempt
        .add(Handle, Name, Number, orFail(AddTrackedTime.of(2.hours)))
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on adding time must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("deleting one entry addresses it by id and is retried"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.delete(Handle, Name, Number, Entry).map: _ =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/times/474")
        assertEquals(backend.allInteractions.size, 2, "the 503 on a delete was not retried")

  test("resetting drops the id from the path, which is what makes it a different operation"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.reset(Handle, Name, Number).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/times")

  test("a 403 — which a token that may not toggle a stopwatch gets — reaches both rails identically"):
    onApi(responding(403, IssueTimeApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.startStopwatch(Handle, Name, Number).failed
        typed  <- api.attempt.startStopwatch(Handle, Name, Number)
      yield assertRailsAgree(raised, typed)

  test("a 403 carries the stopwatch operation id, so an alert can name the endpoint"):
    onApi(responding(403, IssueTimeApiSuite.ForbiddenBody)): api =>
      api.attempt.startStopwatch(Handle, Name, Number).map:
        case Left(CodebergError.Api(ctx, _, _, _)) => assertEquals(ctx.operation, IssueTimeApi.StartStopwatchOperation)
        case other                                 => fail(s"expected an Api failure, got $other")

  test("an entry with no time becomes DecodingFailed at $.time, never a silent zero"):
    onApi(responding(200, """{"id":474}""")): api =>
      api.attempt.add(Handle, Name, Number, orFail(AddTrackedTime.of(1.hour))).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.time")
        case other                                             => fail(s"expected a decoding failure, got $other")

  private def onApi[A](backend: Backend[Future])(use: IssueTimeApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(IssueTimeApi(pipeline)))

/** The error bodies this suite stubs. */
object IssueTimeApiSuite:

  private val ForbiddenBody: String =
    """{"message":"StartIssueStopwatch","url":"https://codeberg.org/api/swagger","errors":["not repo writer"]}"""
