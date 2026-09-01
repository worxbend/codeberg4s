package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.CodebergError

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend

import munit.FunSuite

import scala.concurrent.Future

import java.time.Instant

/** The operations added to [[IssueApi]] after its first ten, over a `BackendStub`: nothing here opens a socket.
  *
  * Kept apart from [[IssueApiSuite]] so that the original ten keep their own suite unchanged. The subject is the same:
  * which URI is dialled, which query parameters and which body are sent, and what each rail does with a failure.
  */
final class IssueApiTailSuite extends FunSuite with IssueLaneHarness:

  private val Other: IssueRef = IssueRef(Handle, Name, orFail(IssueNumber.from(4242L)))

  private val IssueBody: String =
    """{
      |  "id": 6557096,
      |  "number": 2966,
      |  "title": "Bye",
      |  "state": "open",
      |  "user": {"id": 1125174, "login": "personanon5"},
      |  "assignees": null,
      |  "labels": [],
      |  "comments": 2
      |}""".stripMargin

  // --- search ---------------------------------------------------------------

  test("the cross-repository search targets /repos/issues/search, which is not under a repository"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api.search(IssueSearchQuery.Empty, window(1, 30)).map: _ =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/issues/search")
        assertEquals(queryOf(backend), List("page" -> "1", "limit" -> "30"))

  test("the search decodes a BARE ARRAY, not the {ok,data} envelope the repository and user searches use"):
    onApi(responding(200, s"[$IssueBody]")): api =>
      api.search(IssueSearchQuery.Empty, window(1, 30)).map: page =>
        assertEquals(page.items.map(_.number.value), Vector(2966L))

  test("an {ok,data} envelope is therefore a decoding failure here, which is the whole point of saying so"):
    onApi(responding(200, s"""{"ok":true,"data":[$IssueBody]}""")): api =>
      api.attempt.search(IssueSearchQuery.Empty, window(1, 30)).map:
        case Left(CodebergError.DecodingFailed(_, _, _, _)) => ()
        case other                                          => fail(s"expected a decoding failure, got $other")

  test("the search sends only the filters the caller set, and only the true booleans"):
    val backend = RecordingBackend(responding(200, "[]"))
    val query   = IssueSearchQuery.Empty
      .withState(StateFilter.All)
      .withLabels(Vector(orFail(LabelName.from("bug"))))
      .matching("crash")
      .onlyOf(IssueKind.Pulls)
      .assignedToMe
      .ownedBy(Handle)
      .sortedBy(IssueSearchSort.RecentUpdate)

    onApi(backend): api =>
      api
        .search(query, window(1, 30))
        .map: _ =>
          assertEquals(
            queryOf(backend),
            List(
              "state"    -> "all",
              "labels"   -> "bug",
              "q"        -> "crash",
              "type"     -> "pulls",
              "assigned" -> "true",
              "owner"    -> "Codeberg",
              "sort"     -> "recentupdate",
              "page"     -> "1",
              "limit"    -> "30",
            ),
          )

  // --- delete, deadline, pinning -------------------------------------------

  test("deleting an issue is a bodiless DELETE on the issue itself, and is retried"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.delete(Handle, Name, Number).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966")
        assertEquals(backend.allInteractions.size, 2, "the 503 on a delete was not retried")

  test("setting a deadline POSTs the RFC-3339 form Go parses"):
    val backend = RecordingBackend(responding(201, """{"due_date":"2026-09-01T00:00:00Z"}"""))

    onApi(backend): api =>
      api.setDeadline(Handle, Name, Number, Instant.parse("2026-09-01T00:00:00Z")).map: deadline =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/deadline")
        assertEquals(bodyOf(backend), """{"due_date":"2026-09-01T00:00:00Z"}""")
        assertEquals(deadline.dueDate, Some(Instant.parse("2026-09-01T00:00:00Z")))

  test("a deadline response that echoes nothing is a success carrying no date, not a decoding failure"):
    onApi(responding(201, "{}")): api =>
      api.setDeadline(Handle, Name, Number, Instant.parse("2026-09-01T00:00:00Z")).map: deadline =>
        assertEquals(deadline.dueDate, None)

  test("setting a deadline is never retried, because it is a POST"):
    val backend = RecordingBackend(flakyThen(201, "{}"))

    onApi(backend): api =>
      api.attempt
        .setDeadline(Handle, Name, Number, Instant.parse("2026-09-01T00:00:00Z"))
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a POST must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("pinning POSTs to /pin with no body, and is never retried"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.attempt
        .pin(Handle, Name, Number)
        .map: _ =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/pin")
          assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("unpinning DELETEs the same path, and is retried"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.unpin(Handle, Name, Number).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/pin")
        assertEquals(backend.allInteractions.size, 2, "the 503 on an unpin was not retried")

  test("moving a pin PATCHes an absolute one-based position, and IS retried — the one PATCH here that is"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.movePin(Handle, Name, Number, PinPosition.First).map: _ =>
        assertEquals(methodOf(backend), "PATCH")
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/pin/1")
        assertEquals(bodyOf(backend), "empty")
        assertEquals(backend.allInteractions.size, 2, "the 503 on a pin move was not retried")

  // --- blocks and dependencies ---------------------------------------------

  test("the blocks listing pages, and reads the issues this one is blocking"):
    val backend = RecordingBackend(responding(200, s"[$IssueBody]"))

    onApi(backend): api =>
      api.blocks(Handle, Name, Number, window(1, 30)).map: page =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/blocks")
        assertEquals(queryOf(backend), List("page" -> "1", "limit" -> "30"))
        assertEquals(page.size, 1)

  test("adding a block POSTs the other issue as owner, repo and index, because it may be elsewhere"):
    val backend = RecordingBackend(responding(201, IssueBody))

    onApi(backend): api =>
      api.addBlock(Handle, Name, Number, Other).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(bodyOf(backend), """{"owner":"Codeberg","repo":"Community","index":4242}""")

  test("removing a block is a DELETE carrying the same body, and is retried"):
    val backend = RecordingBackend(flakyThen(200, IssueBody))

    onApi(backend): api =>
      api.removeBlock(Handle, Name, Number, Other).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(bodyOf(backend), """{"owner":"Codeberg","repo":"Community","index":4242}""")
        assertEquals(backend.allInteractions.size, 2, "the 503 on a block removal was not retried")

  test("dependencies are the other end of the same relation, on their own path"):
    val listed = RecordingBackend(responding(200, "[]"))
    val added  = RecordingBackend(responding(201, IssueBody))

    for
      _ <- onApi(listed)(_.dependencies(Handle, Name, Number, window(1, 30)))
      _ <- onApi(added)(_.addDependency(Handle, Name, Number, Other))
    yield
      assertEquals(
        pathOf(listed),
        "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/dependencies",
      )
      assertEquals(methodOf(added), "POST")
      assertEquals(bodyOf(added), """{"owner":"Codeberg","repo":"Community","index":4242}""")

  test("adding a dependency is never retried, because it is a POST"):
    val backend = RecordingBackend(flakyThen(201, IssueBody))

    onApi(backend): api =>
      api.attempt
        .addDependency(Handle, Name, Number, Other)
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a POST must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("removing a dependency is a DELETE with a body, and is retried"):
    val backend = RecordingBackend(flakyThen(200, IssueBody))

    onApi(backend): api =>
      api.removeDependency(Handle, Name, Number, Other).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(backend.allInteractions.size, 2, "the 503 on a dependency removal was not retried")

  // --- timeline -------------------------------------------------------------

  test("the timeline pages and takes the same since/before window the comment listing does"):
    val backend = RecordingBackend(responding(200, "[]"))
    val query   = CommentQuery.Empty.updatedBefore(Instant.parse("2026-08-01T00:00:00Z"))

    onApi(backend): api =>
      api.timeline(Handle, Name, Number, query, window(1, 30)).map: _ =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/timeline")
        assertEquals(
          queryOf(backend),
          List("before" -> "2026-08-01T00:00:00Z", "page" -> "1", "limit" -> "30"),
        )

  test("a timeline entry keeps Forgejo's discriminator verbatim, because this library does not map it"):
    onApi(responding(200, """[{"id":9,"type":"label","label":{"id":102,"name":"bug"}}]""")): api =>
      api.timeline(Handle, Name, Number, CommentQuery.Empty, window(1, 30)).map: page =>
        assertEquals(page.items.map(_.eventType), Vector(Some("label")))
        assertEquals(page.items.flatMap(_.label).map(_.name), Vector("bug"))

  // --- failures -------------------------------------------------------------

  test("a 404 on the search reaches both rails as the very same failure"):
    onApi(responding(404, IssueLaneHarness.NotFoundBody)): api =>
      for
        raised <- api.search(IssueSearchQuery.Empty, window(1, 30)).failed
        typed  <- api.attempt.search(IssueSearchQuery.Empty, window(1, 30))
      yield assertRailsAgree(raised, typed)

  test("a 404 on a delete reaches both rails as the very same failure too"):
    onApi(responding(404, IssueLaneHarness.NotFoundBody)): api =>
      for
        raised <- api.delete(Handle, Name, Number).failed
        typed  <- api.attempt.delete(Handle, Name, Number)
      yield assertRailsAgree(raised, typed)

  test("each new operation carries its own stable id, so an alert can name the endpoint"):
    onApi(responding(404, IssueLaneHarness.NotFoundBody)): api =>
      for
        pinned  <- api.attempt.pin(Handle, Name, Number)
        blocked <- api.attempt.addBlock(Handle, Name, Number, Other)
      yield
        assertEquals(operation(pinned), IssueApi.PinOperation)
        assertEquals(operation(blocked), IssueApi.AddBlockOperation)

  private def operation[A](result: Either[CodebergError, A]): String =
    result match
      case Left(CodebergError.Api(ctx, _, _, _)) => ctx.operation
      case other                                 => fail(s"expected an Api failure, got $other")

  private def onApi[A](backend: Backend[Future])(use: IssueApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(IssueApi(pipeline)))
