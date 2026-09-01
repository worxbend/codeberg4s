package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.CodebergError

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend

import munit.FunSuite

import scala.concurrent.Future

import java.time.Instant

/** [[IssueMilestoneApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * Decoding is asserted against `golden/issue/milestones-list.json` in `modules/codec`, so the payload here is a small
  * hand-written body chosen to exercise a seam. What is under test is which keys a create and an edit put on the wire —
  * on a `PATCH` that is the difference between "leave this alone" and "set it to this".
  */
final class IssueMilestoneApiSuite extends FunSuite with IssueLaneHarness:

  private val Release: MilestoneId = orFail(MilestoneId.from(3109L))

  private val Deadline: Instant = Instant.parse("2026-09-01T00:00:00Z")

  private val MilestoneBody: String =
    """{
      |  "id": 3109,
      |  "title": "Forgejo v1.18.0-0",
      |  "state": "closed",
      |  "closed_at": "2023-01-08T01:08:47+01:00",
      |  "open_issues": 0,
      |  "closed_issues": 15
      |}""".stripMargin

  test("a create POSTs the title alone when nothing else was named"):
    val backend = RecordingBackend(responding(201, MilestoneBody))

    onApi(backend): api =>
      api.create(Handle, Name, orFail(CreateMilestone.of("v1.0"))).map: milestone =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/milestones")
        assertEquals(bodyOf(backend), """{"title":"v1.0"}""")
        assertEquals(milestone.id.value, 3109L)

  test("a create sends the deadline and the state when the caller named them"):
    val backend = RecordingBackend(responding(201, MilestoneBody))
    val command = orFail(CreateMilestone.of("v1.0")).describedAs("hardening").dueBy(Deadline).createdClosed

    onApi(backend): api =>
      api
        .create(Handle, Name, command)
        .map: _ =>
          assertEquals(
            bodyOf(backend),
            """{"title":"v1.0","description":"hardening","due_on":"2026-09-01T00:00:00Z","state":"closed"}""",
          )

  test("a create is never retried, because Forgejo does not reject a duplicate title"):
    val backend = RecordingBackend(flakyThen(201, MilestoneBody))

    onApi(backend): api =>
      api.attempt
        .create(Handle, Name, orFail(CreateMilestone.of("v1.0")))
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a create must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("an edit that changes nothing sends an empty object, not a request that blanks the milestone"):
    val backend = RecordingBackend(responding(200, MilestoneBody))

    onApi(backend): api =>
      api.edit(Handle, Name, Release, EditMilestone.Empty).map: _ =>
        assertEquals(methodOf(backend), "PATCH")
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/milestones/3109")
        assertEquals(bodyOf(backend), "{}")

  test("an edit sends only what it was told to change"):
    val backend = RecordingBackend(responding(200, MilestoneBody))

    onApi(backend): api =>
      api
        .edit(Handle, Name, Release, EditMilestone.Empty.renamedTo("v1.1").reopen)
        .map(_ => assertEquals(bodyOf(backend), """{"title":"v1.1","state":"open"}"""))

  test("an edit is never retried, because a repeat could undo somebody else's change"):
    val backend = RecordingBackend(flakyThen(200, MilestoneBody))

    onApi(backend): api =>
      api.attempt
        .edit(Handle, Name, Release, EditMilestone.Empty.close)
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the PATCH was retried"))

  test("a delete is retried, because it names one milestone row id"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.delete(Handle, Name, Release).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/milestones/3109")
        assertEquals(backend.allInteractions.size, 2, "the 503 on a delete was not retried")

  test("a 404 reaches both rails as the very same failure"):
    onApi(responding(404, IssueLaneHarness.NotFoundBody)): api =>
      for
        raised <- api.edit(Handle, Name, Release, EditMilestone.Empty).failed
        typed  <- api.attempt.edit(Handle, Name, Release, EditMilestone.Empty)
      yield assertRailsAgree(raised, typed)

  test("a 404 carries the edit operation id, so an alert can name the endpoint"):
    onApi(responding(404, IssueLaneHarness.NotFoundBody)): api =>
      api.attempt.edit(Handle, Name, Release, EditMilestone.Empty).map:
        case Left(CodebergError.Api(ctx, _, _, _)) => assertEquals(ctx.operation, IssueMilestoneApi.EditOperation)
        case other                                 => fail(s"expected an Api failure, got $other")

  test("a milestone with no state becomes DecodingFailed at $.state, because the domain has no third case"):
    onApi(responding(201, """{"id":1,"title":"v1.0"}""")): api =>
      api.attempt.create(Handle, Name, orFail(CreateMilestone.of("v1.0"))).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.state")
        case other                                             => fail(s"expected a decoding failure, got $other")

  private def onApi[A](backend: Backend[Future])(use: IssueMilestoneApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(IssueMilestoneApi(pipeline)))
