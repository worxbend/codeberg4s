package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ClientSuiteHarness
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams

import sttp.client4.Backend
import sttp.client4.testing.BackendStub
import sttp.client4.testing.RecordingBackend
import sttp.client4.testing.ResponseStub
import sttp.model.Header
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.Future

import java.time.Instant

/** [[IssueApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The subject is the wiring — which URI is dialled, which query parameters and which body are sent, what each rail
  * does with a failure, and what the paging headers are allowed to decide. Decoding itself is asserted against the
  * golden captures in `modules/codec`, so the payloads here are small hand-written bodies chosen to exercise a seam.
  */
final class IssueApiSuite extends FunSuite with ClientSuiteHarness:

  private val Handle: Owner = orFail(Owner.from("Codeberg"))

  private val Name: RepoName = orFail(RepoName.from("Community"))

  private val Number: IssueNumber = orFail(IssueNumber.from(2966L))

  private val Release: MilestoneId = orFail(MilestoneId.from(3109L))

  // --- reads ----------------------------------------------------------------

  test("a single-issue read maps the instance's payload to a domain issue"):
    onApi(responding(200, IssueApiSuite.IssueBody)): api =>
      api.get(Handle, Name, Number).map: issue =>
        assertEquals(issue.number.value, 2966L)
        assertEquals(issue.title, "Bye")
        assertEquals(issue.state, LifecycleState.Open)
        assertEquals(issue.author.map(_.login), Some("personanon5"))

  test("a single-issue read targets /repos/{owner}/{repo}/issues/{index} on the configured instance"):
    val backend = RecordingBackend(responding(200, IssueApiSuite.IssueBody))

    onApi(backend): api =>
      api
        .get(Handle, Name, Number)
        .map(_ => assertEquals(dialled(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966"))

  test("issues.list sends page and limit together, because limit alone is silently ignored"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .list(Handle, Name, IssueQuery.Empty, window(2, 25))
        .map(_ => assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "25")))

  test("issues.list sends only the filters the caller set"):
    val backend = RecordingBackend(responding(200, "[]"))
    val query   = IssueQuery.Empty
      .withState(StateFilter.All)
      .withLabels(Vector(orFail(LabelName.from("bug")), orFail(LabelName.from("upstream"))))
      .updatedSince(Instant.parse("2026-07-01T00:00:00Z"))

    onApi(backend): api =>
      api
        .list(Handle, Name, query, PageParams.First)
        .map: _ =>
          assertEquals(
            queryOf(backend),
            List(
              "state"  -> "all",
              "labels" -> "bug,upstream",
              "since"  -> "2026-07-01T00:00:00Z",
              "page"   -> "1",
              "limit"  -> "30",
            ),
          )

  test("issues.list ends where rel=next says it ends, not where a short page suggests"):
    val backend = responding(200, IssueApiSuite.IssueListBody, IssueApiSuite.PagedHeaders)

    onApi(backend): api =>
      api.list(Handle, Name, IssueQuery.Empty, window(1, 30)).map: page =>
        assertEquals(page.size, 1)
        assertEquals(page.totalCount, Some(1590))
        assertEquals(page.nextPage.map(_.value), Some(2))
        assertEquals(page.isLast, false)

  test("a page whose response carries no Link header reports itself as the last one"):
    onApi(responding(200, IssueApiSuite.IssueListBody)): api =>
      api.list(Handle, Name, IssueQuery.Empty, PageParams.First).map: page =>
        assertEquals(page.isLast, true)
        assertEquals(page.nextPage, None)

  test("a page past the end is an empty page, not a failure — Forgejo answers 200 with []"):
    onApi(responding(200, "[]")): api =>
      api.list(Handle, Name, IssueQuery.Empty, PageParams.First).map: page =>
        assertEquals(page.items, Vector.empty[Issue])
        assertEquals(page.isLast, true)

  test("issues.comments.list targets the issue's comments and pages it"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .listComments(Handle, Name, Number, PageParams.First)
        .map: _ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/comments")
          assertEquals(queryOf(backend), List("page" -> "1", "limit" -> "30"))

  test("issues.labels.list targets the repository's labels, not an issue's"):
    val backend = RecordingBackend(responding(200, IssueApiSuite.LabelListBody))

    onApi(backend): api =>
      api.listLabels(Handle, Name, PageParams.First).map: page =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/labels")
        assertEquals(page.items.map(_.name), Vector("bug"))

  test("issues.milestones.list always states which states it wants"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .listMilestones(Handle, Name, StateFilter.All, PageParams.First)
        .map: _ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/milestones")
          assertEquals(queryOf(backend), List("state" -> "all", "page" -> "1", "limit" -> "30"))

  test("a single-milestone read addresses a milestone by id"):
    val backend = RecordingBackend(responding(200, IssueApiSuite.MilestoneBody))

    onApi(backend): api =>
      api.getMilestone(Handle, Name, Release).map: milestone =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/milestones/3109")
        assertEquals(milestone.title, "Forgejo v1.18.0-0")
        assertEquals(milestone.state.isClosed, true)

  // --- writes ---------------------------------------------------------------

  test("issues.create POSTs the rendered CreateIssueOption to the repository's issues"):
    val backend = RecordingBackend(responding(201, IssueApiSuite.IssueBody))
    val command = orFail(CreateIssue.of("Bye")).withBody("403 on every clone")

    onApi(backend): api =>
      api.create(Handle, Name, command).map: issue =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues")
        assertEquals(bodyOf(backend), """{"title":"Bye","body":"403 on every clone"}""")
        assertEquals(issue.number.value, 2966L)

  test("issues.create is never retried, because a repeat would file a second issue"):
    val backend = RecordingBackend(
      BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(
        ResponseStub.adjust("", StatusCode(503)),
        ResponseStub.adjust(IssueApiSuite.IssueBody, StatusCode(201)),
      )
    )

    onApi(backend): api =>
      api.attempt
        .create(Handle, Name, orFail(CreateIssue.of("Bye")))
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on create must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("a read is retried, so the eligibility difference is real and not a comment"):
    val backend = RecordingBackend(
      BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(
        ResponseStub.adjust("", StatusCode(503)),
        ResponseStub.adjust(IssueApiSuite.IssueBody, StatusCode(200)),
      )
    )

    onApi(backend): api =>
      api.get(Handle, Name, Number).map: issue =>
        assertEquals(issue.number.value, 2966L)
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  test("issues.edit PATCHes only what the command sets"):
    val backend = RecordingBackend(responding(201, IssueApiSuite.IssueBody))

    onApi(backend): api =>
      api
        .edit(Handle, Name, Number, EditIssue.Empty.close)
        .map: _ =>
          assertEquals(methodOf(backend), "PATCH")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966")
          assertEquals(bodyOf(backend), """{"state":"closed"}""")

  test("issues.edit is never retried either, because a partial update is not idempotent here"):
    val backend = RecordingBackend(
      BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(
        ResponseStub.adjust("", StatusCode(503)),
        ResponseStub.adjust(IssueApiSuite.IssueBody, StatusCode(201)),
      )
    )

    onApi(backend): api =>
      api.attempt
        .edit(Handle, Name, Number, EditIssue.Empty.close)
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the PATCH was retried"))

  test("issues.comments.create POSTs the comment body to the issue's comments"):
    val backend = RecordingBackend(responding(201, IssueApiSuite.CommentBody))

    onApi(backend): api =>
      api
        .createComment(Handle, Name, Number, orFail(CreateComment.of("looks right")))
        .map: comment =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/comments")
          assertEquals(bodyOf(backend), """{"body":"looks right"}""")
          assertEquals(comment.id.value, 20366420L)

  test("issues.labels.create POSTs the hashed colour Forgejo documents for input"):
    val backend = RecordingBackend(responding(201, IssueApiSuite.LabelBody))
    val command = CreateLabel.of(orFail(LabelName.from("bug")), orFail(LabelColor.from("ee0701")))

    onApi(backend): api =>
      api.createLabel(Handle, Name, command).map: label =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(bodyOf(backend), """{"name":"bug","color":"#ee0701"}""")
        assertEquals(label.color.map(_.value), Some("ee0701"))

  // --- failures -------------------------------------------------------------

  test("a 404 fails the convenience rail with a CodebergException carrying the Api failure"):
    onApi(responding(404, IssueApiSuite.NotFoundBody)): api =>
      api.get(Handle, Name, Number).failed.map:
        case CodebergException(error) =>
          assertEquals(summary(error), (IssueApi.GetOperation, 404, Some("GetIssueByIndex")))
        case other                    => fail(s"expected a CodebergException, got $other")

  test("a 404 reaches the typed rail as a Left reporting the very same failure"):
    onApi(responding(404, IssueApiSuite.NotFoundBody)): api =>
      for
        raised <- api.get(Handle, Name, Number).failed
        typed  <- api.attempt.get(Handle, Name, Number)
      yield assertRailsAgree(raised, typed)

  test("a 422 on a create reaches both rails identically, carrying Forgejo's errors array"):
    onApi(responding(422, IssueApiSuite.ValidationBody)): api =>
      for
        raised <- api.create(Handle, Name, orFail(CreateIssue.of("Bye"))).failed
        typed  <- api.attempt.create(Handle, Name, orFail(CreateIssue.of("Bye")))
      yield
        assertEquals(detailsOf(typed), List("title is required"))
        assertRailsAgree(raised, typed)

  test("a 422 carries the create operation id, so an alert can name the endpoint"):
    onApi(responding(422, IssueApiSuite.ValidationBody)): api =>
      api.attempt.create(Handle, Name, orFail(CreateIssue.of("Bye"))).map: outcome =>
        assertEquals(operationOf(outcome), IssueApi.CreateOperation)

  test("a 400 is an Api failure too — Forgejo uses it for validation alongside 422"):
    onApi(responding(400, IssueApiSuite.ValidationBody)): api =>
      api.attempt.list(Handle, Name, IssueQuery.Empty, PageParams.First).map:
        case Left(CodebergError.Api(_, status, _, _)) => assertEquals(status, 400)
        case other                                    => fail(s"expected an Api failure, got $other")

  test("a 200 whose payload does not fit the model becomes DecodingFailed, never an escaping codec exception"):
    onApi(responding(200, """{"id":1,"title":"t","state":"open"}""")): api =>
      api.attempt.get(Handle, Name, Number).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.number")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a bad element of a list body reports its position, all the way through the pipeline"):
    onApi(responding(200, """[{"id":1,"number":1,"title":"t","state":"open"},{"id":2,"number":2,"title":"t"}]""")):
      api =>
        api.attempt.list(Handle, Name, IssueQuery.Empty, PageParams.First).map:
          case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[1].state")
          case other                                             => fail(s"expected a decoding failure, got $other")

  test("both rails agree on a comment listing failure as well, so the choice of rail is only a choice of style"):
    onApi(responding(404, IssueApiSuite.NotFoundBody)): api =>
      for
        raised <- api.listComments(Handle, Name, Number, PageParams.First).failed
        typed  <- api.attempt.listComments(Handle, Name, Number, PageParams.First)
      yield assertRailsAgree(raised, typed)

  // --- harness --------------------------------------------------------------

  /** Builds the API under test on a pipeline over `backend`, releasing the timer whatever happens. */
  private def onApi[A](backend: Backend[Future])(use: IssueApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(IssueApi(pipeline)))

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour. */
object IssueApiSuite:

  /** `golden/issue/single.json`, reduced to the keys these tests assert on. */
  private val IssueBody: String =
    """{
      |  "id": 6557096,
      |  "number": 2966,
      |  "title": "Bye",
      |  "state": "open",
      |  "user": {"id": 1125174, "login": "personanon5"},
      |  "assignee": null,
      |  "assignees": null,
      |  "closed_at": null,
      |  "due_date": null,
      |  "milestone": null,
      |  "labels": [],
      |  "comments": 2
      |}""".stripMargin

  private val IssueListBody: String = s"[$IssueBody]"

  private val CommentBody: String =
    """{"id": 20366420, "body": "looks right", "user": {"id": 532348, "login": "jkassel"}}"""

  private val LabelBody: String =
    """{"id": 102, "name": "bug", "color": "ee0701", "exclusive": false, "is_archived": false}"""

  private val LabelListBody: String = s"[$LabelBody]"

  private val MilestoneBody: String =
    """{
      |  "id": 3109,
      |  "title": "Forgejo v1.18.0-0",
      |  "state": "closed",
      |  "closed_at": "2023-01-08T01:08:47+01:00",
      |  "open_issues": 0,
      |  "closed_issues": 15
      |}""".stripMargin

  /** The paging headers `docs/HAZARDS.md` §5 captured verbatim, with the host swapped for the stub's. */
  private val PagedHeaders: List[Header] =
    List(
      Header("X-Total-Count", "1590"),
      Header(
        "Link",
        "<https://forge.example/api/v1/repos/Codeberg/Community/issues?limit=30&page=2>; rel=\"next\"," +
          "<https://forge.example/api/v1/repos/Codeberg/Community/issues?limit=30&page=53>; rel=\"last\"",
      ),
    )

  /** A 404 shaped like `golden/error/404-repo-not-found.json`: a Go symbol for a message, and the useful text in
    * `errors`.
    */
  private val NotFoundBody: String =
    """{"message":"GetIssueByIndex","url":"https://codeberg.org/api/swagger","errors":["issue does not exist"]}"""

  private val ValidationBody: String =
    """{"message":"CreateIssue","url":"https://codeberg.org/api/swagger","errors":["title is required"]}"""
