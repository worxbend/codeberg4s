package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.client.FutureExec
import com.worxbend.codeberg4s.client.FutureTimer
import com.worxbend.codeberg4s.codec.ApiErrorBodyCodec
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.Telemetry
import com.worxbend.codeberg4s.issues.LabelId
import com.worxbend.codeberg4s.issues.StateFilter
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.repositories.BranchName
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.retry.Jitter
import com.worxbend.codeberg4s.retry.RetryPolicy
import com.worxbend.codeberg4s.transport.SttpHttpPort

import sttp.client4.Backend
import sttp.client4.testing.BackendStub
import sttp.client4.testing.RecordingBackend
import sttp.client4.testing.ResponseStub
import sttp.model.Header
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/** [[PullRequestApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The subject is the wiring — which URI is dialled, which query parameters and which body are sent, what each rail
  * does with a failure, and what the paging headers are allowed to decide. Decoding itself is asserted against the
  * golden captures in `modules/codec`, so the payloads here are small hand-written bodies chosen to exercise a seam.
  */
final class PullRequestApiSuite extends FunSuite:

  private given ExecutionContext = munitExecutionContext

  private val Handle: Owner = orFail(Owner.from("forgejo"))

  private val Name: RepoName = orFail(RepoName.from("forgejo"))

  private val Number: PullRequestNumber = orFail(PullRequestNumber.from(13726L))

  private val Base: BranchName = orFail(BranchName.from("forgejo"))

  private val Topic: BranchName = orFail(BranchName.from("fix-pep691"))

  private val Head: CommitSha = orFail(CommitSha.from("48079baa8d387f3ab770cc144c367409ddc2a879"))

  private val Instance: BaseUri = orFail(BaseUri.from("https://forge.example/api/v1"))

  // --- reads ----------------------------------------------------------------

  test("a single-pull-request read maps the instance's payload to a domain pull request"):
    onStub(responding(200, PullRequestApiSuite.MergedBody)): api =>
      api.get(Handle, Name, Number).map: pull =>
        assertEquals(pull.number.value, 13726L)
        assertEquals(pull.title, "fix: bad quoting in hook scripts")
        assertEquals(pull.state.isMerged, true)
        assertEquals(pull.author.map(_.login), Some("patdyn"))

  test("a single-pull-request read targets /repos/{owner}/{repo}/pulls/{index} on the configured instance"):
    val backend = RecordingBackend(responding(200, PullRequestApiSuite.MergedBody))

    onBackend(backend): api =>
      api
        .get(Handle, Name, Number)
        .map(_ => assertEquals(dialled(backend), "https://forge.example/api/v1/repos/forgejo/forgejo/pulls/13726"))

  test("pulls.list sends page and limit together, because limit alone is silently ignored"):
    val backend = RecordingBackend(responding(200, "[]"))

    onBackend(backend): api =>
      api
        .list(Handle, Name, PullRequestQuery.Empty, window(2, 25))
        .map: _ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/forgejo/forgejo/pulls")
          assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "25"))

  test("pulls.list sends only the filters the caller set, and repeats labels rather than joining them"):
    val backend = RecordingBackend(responding(200, "[]"))
    val query   = PullRequestQuery.Empty
      .withState(StateFilter.All)
      .sortedBy(PullRequestSort.RecentUpdate)
      .withLabels(Vector(orFail(LabelId.from(201023L)), orFail(LabelId.from(201030L))))
      .withBase(Base)

    onBackend(backend): api =>
      api
        .list(Handle, Name, query, PageParams.First)
        .map: _ =>
          assertEquals(
            queryOf(backend),
            List(
              "state"  -> "all",
              "sort"   -> "recentupdate",
              "base"   -> "forgejo",
              "labels" -> "201023",
              "labels" -> "201030",
              "page"   -> "1",
              "limit"  -> "30",
            ),
          )

  test("pulls.list ends where rel=next says it ends, not where a short page suggests"):
    onStub(responding(200, PullRequestApiSuite.PullListBody, PullRequestApiSuite.PagedHeaders)): api =>
      api.list(Handle, Name, PullRequestQuery.Empty, window(1, 30)).map: page =>
        assertEquals(page.size, 1)
        assertEquals(page.totalCount, Some(167))
        assertEquals(page.nextPage.map(_.value), Some(2))
        assertEquals(page.isLast, false)

  test("a page past the end is an empty page, not a failure — Forgejo answers 200 with []"):
    onStub(responding(200, "[]")): api =>
      api.list(Handle, Name, PullRequestQuery.Empty, PageParams.First).map: page =>
        assertEquals(page.items, Vector.empty[PullRequest])
        assertEquals(page.isLast, true)

  test("pulls.reviews.list targets the pull request's reviews and always reports itself as the last page"):
    val backend = RecordingBackend(responding(200, PullRequestApiSuite.ReviewListBody, PullRequestApiSuite.TotalOnly))

    onBackend(backend): api =>
      api.listReviews(Handle, Name, Number, PageParams.First).map: page =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/forgejo/forgejo/pulls/13726/reviews")
        assertEquals(queryOf(backend), List("page" -> "1", "limit" -> "30"))
        assertEquals(page.items.map(_.state), Vector(Some(ReviewState.Approved)))
        assertEquals(page.totalCount, Some(3))
        assertEquals(page.isLast, true, "this endpoint sends no Link header, so there is no next page to report")

  test("pulls.commits.list decodes the repository wave's commit model"):
    val backend = RecordingBackend(responding(200, PullRequestApiSuite.CommitListBody))

    onBackend(backend): api =>
      api.listCommits(Handle, Name, Number, PageParams.First).map: page =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/forgejo/forgejo/pulls/13726/commits")
        assertEquals(page.items.map(_.sha.value), Vector(Head.value))
        assertEquals(page.items.flatMap(_.author).map(_.login), Vector("patdyn"))

  test("pulls.files.list decodes the changed files and their counts"):
    val backend = RecordingBackend(responding(200, PullRequestApiSuite.FileListBody))

    onBackend(backend): api =>
      api.listFiles(Handle, Name, Number, PageParams.First).map: page =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/forgejo/forgejo/pulls/13726/files")
        assertEquals(page.items.map(_.filename), Vector("modules/git/hook_generate.go"))
        assertEquals(page.items.map(_.changes), Vector(34L))

  // --- writes ---------------------------------------------------------------

  test("pulls.create POSTs the rendered CreatePullRequestOption to the repository's pulls"):
    val backend = RecordingBackend(responding(201, PullRequestApiSuite.MergedBody))
    val command = orFail(CreatePullRequest.of("fix the quoting", PullRequestHead.branch(Topic), Base))

    onBackend(backend): api =>
      api.create(Handle, Name, command).map: pull =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/forgejo/forgejo/pulls")
        assertEquals(bodyOf(backend), """{"title":"fix the quoting","head":"fix-pep691","base":"forgejo"}""")
        assertEquals(pull.number.value, 13726L)

  test("pulls.create is never retried, because a repeat would open a second pull request"):
    val backend = RecordingBackend(cycling(503, 201, PullRequestApiSuite.MergedBody))
    val command = orFail(CreatePullRequest.of("fix the quoting", PullRequestHead.branch(Topic), Base))

    onBackend(backend): api =>
      api.attempt
        .create(Handle, Name, command)
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on create must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("a read is retried, so the eligibility difference is real and not a comment"):
    val backend = RecordingBackend(cycling(503, 200, PullRequestApiSuite.MergedBody))

    onBackend(backend): api =>
      api.get(Handle, Name, Number).map: pull =>
        assertEquals(pull.number.value, 13726L)
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  test("pulls.edit PATCHes only what the command sets"):
    val backend = RecordingBackend(responding(201, PullRequestApiSuite.MergedBody))

    onBackend(backend): api =>
      api
        .edit(Handle, Name, Number, EditPullRequest.Empty.close)
        .map: _ =>
          assertEquals(methodOf(backend), "PATCH")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/forgejo/forgejo/pulls/13726")
          assertEquals(bodyOf(backend), """{"state":"closed"}""")

  test("pulls.edit is never retried either, because a partial update is not idempotent here"):
    val backend = RecordingBackend(cycling(503, 201, PullRequestApiSuite.MergedBody))

    onBackend(backend): api =>
      api.attempt
        .edit(Handle, Name, Number, EditPullRequest.Empty.close)
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the PATCH was retried"))

  // --- the merge ------------------------------------------------------------

  test("pulls.merge POSTs the merge form to the pull request's merge endpoint"):
    val backend = RecordingBackend(responding(200, ""))

    onBackend(backend): api =>
      api
        .merge(Handle, Name, Number, MergePullRequest.using(MergeStyle.Squash))
        .map: _ =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/forgejo/forgejo/pulls/13726/merge")
          assertEquals(bodyOf(backend), """{"Do":"squash"}""")

  /** The most consequential assertion in the suite: a retried merge can merge commits the caller never approved. */
  test("pulls.merge is never retried, whatever the failure"):
    val backend = RecordingBackend(cycling(503, 200, ""))

    onBackend(backend): api =>
      api.attempt
        .merge(Handle, Name, Number, MergePullRequest.using(MergeStyle.Merge))
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on merge must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the merge was sent twice")

  test("a merge sends the head guard when the caller asked for one, which is what makes a repeat safe"):
    val backend = RecordingBackend(responding(200, ""))

    onBackend(backend): api =>
      api
        .merge(Handle, Name, Number, MergePullRequest.using(MergeStyle.Merge).expecting(Head))
        .map(_ => assertEquals(bodyOf(backend), s"""{"Do":"merge","head_commit_id":"${Head.value}"}"""))

  test("a merge ignores the response body, so a 200 decorated with a payload still succeeds"):
    onStub(responding(200, """{"unexpected":true}""")): api =>
      api
        .merge(Handle, Name, Number, MergePullRequest.using(MergeStyle.Merge))
        .map(outcome => assertEquals(outcome, ()))

  test("a 405 refusal reaches both rails identically, carrying the merge operation id"):
    onStub(responding(405, PullRequestApiSuite.RefusedBody)): api =>
      for
        raised <- api.merge(Handle, Name, Number, MergePullRequest.using(MergeStyle.Merge)).failed
        typed  <- api.attempt.merge(Handle, Name, Number, MergePullRequest.using(MergeStyle.Merge))
      yield
        assertEquals(operation(typed), PullRequestApi.MergeOperation)
        assertRailsAgree(raised, typed)

  // --- failures -------------------------------------------------------------

  test("a 404 fails the convenience rail with a CodebergException carrying the Api failure"):
    onStub(responding(404, PullRequestApiSuite.NotFoundBody)): api =>
      api.get(Handle, Name, Number).failed.map:
        case CodebergException(error) =>
          assertEquals(summary(error), (PullRequestApi.GetOperation, 404, Some("GetPullRequestByIndex")))
        case other                    => fail(s"expected a CodebergException, got $other")

  test("a 404 reaches the typed rail as a Left reporting the very same failure"):
    onStub(responding(404, PullRequestApiSuite.NotFoundBody)): api =>
      for
        raised <- api.get(Handle, Name, Number).failed
        typed  <- api.attempt.get(Handle, Name, Number)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a review listing failure as well, so the choice of rail is only a choice of style"):
    onStub(responding(404, PullRequestApiSuite.NotFoundBody)): api =>
      for
        raised <- api.listReviews(Handle, Name, Number, PageParams.First).failed
        typed  <- api.attempt.listReviews(Handle, Name, Number, PageParams.First)
      yield assertRailsAgree(raised, typed)

  test("a 400 is an Api failure too — Forgejo uses it for validation alongside 422"):
    onStub(responding(400, PullRequestApiSuite.ValidationBody)): api =>
      api.attempt.list(Handle, Name, PullRequestQuery.Empty, PageParams.First).map:
        case Left(CodebergError.Api(_, status, _)) => assertEquals(status, 400)
        case other                                 => fail(s"expected an Api failure, got $other")

  test("a 200 whose payload does not fit the model becomes DecodingFailed, never an escaping codec exception"):
    onStub(responding(200, """{"id":1,"title":"t","state":"open"}""")): api =>
      api.attempt.get(Handle, Name, Number).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.number")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a bad element of a list body reports its position, all the way through the pipeline"):
    val body = """[{"id":1,"number":1,"title":"t","state":"open"},{"id":2,"number":2,"title":"t"}]"""

    onStub(responding(200, body)): api =>
      api.attempt.list(Handle, Name, PullRequestQuery.Empty, PageParams.First).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[1].state")
        case other                                             => fail(s"expected a decoding failure, got $other")

  // --- assertions -----------------------------------------------------------

  private def assertRailsAgree[A](raised: Throwable, typed: Either[CodebergError, A]): Unit =
    (raised, typed) match
      case (CodebergException(convenience), Left(materialised)) =>
        assertEquals(summary(materialised), summary(convenience))
      case (convenience, materialised)                          =>
        fail(s"the rails disagreed: $convenience versus $materialised")

  private def summary(error: CodebergError): (String, Int, Option[String]) =
    error match
      case CodebergError.Api(ctx, status, body) => (ctx.operation, status, body.message)
      case other                                => fail(s"expected an Api failure, got ${other.describe}")

  private def operation[A](result: Either[CodebergError, A]): String =
    result match
      case Left(CodebergError.Api(ctx, _, _)) => ctx.operation
      case other                              => fail(s"expected an Api failure, got $other")

  // --- harness --------------------------------------------------------------

  private def responding(status: Int, body: String): BackendStub[Future] =
    responding(status, body, Nil)

  private def responding(status: Int, body: String, headers: List[Header]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status), headers))

  /** Fails once, then succeeds — the shape every retry-eligibility test needs. */
  private def cycling(first: Int, second: Int, body: String): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(
      ResponseStub.adjust("", StatusCode(first)),
      ResponseStub.adjust(body, StatusCode(second)),
    )

  private def dialled(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.toString
      case None               => fail("no request reached the backend")

  /** The dialled URI without its query string. Written with `indexOf` rather than a character comparison because
    * `.scalafix.conf` bans universal equality outright.
    */
  private def pathOf(backend: RecordingBackend): String =
    val uri   = dialled(backend)
    val query = uri.indexOf('?')

    if query < 0 then uri else uri.take(query)

  private def queryOf(backend: RecordingBackend): List[(String, String)] =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.params.toSeq.toList
      case None               => fail("no request reached the backend")

  private def methodOf(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.method.method
      case None               => fail("no request reached the backend")

  private def bodyOf(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.body.show.stripPrefix("string: ")
      case None               => fail("no request reached the backend")

  private def window(page: Int, size: Int): PageParams =
    PageParams(orFail(PageNumber.from(page)), orFail(PageSize.from(size)))

  private def onStub[A](backend: Backend[Future])(use: PullRequestApi => Future[A]): Future[A] =
    onBackend(backend)(use)

  /** Builds the pipeline this group's API sits on, and releases the timer whatever the outcome. */
  private def onBackend[A](backend: Backend[Future])(use: PullRequestApi => Future[A]): Future[A] =
    given Exec[Future] = FutureExec()

    val config = CodebergConfig(Auth.Anonymous).copy(baseUri = Instance, retry = PullRequestApiSuite.PromptRetry)
    val timer  = FutureTimer()

    val pipeline = ApiPipeline[Future](
      SttpHttpPort(backend, config),
      config,
      timer,
      Telemetry.noOp[Future],
      ApiErrorBodyCodec.parse,
    )

    use(PullRequestApi(pipeline)).transform: outcome =>
      timer.close()
      outcome

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour. */
object PullRequestApiSuite:

  /** `golden/pull/single-merged.json`, reduced to the keys these tests assert on. */
  private val MergedBody: String =
    """{
      |  "id": 2785448,
      |  "number": 13726,
      |  "title": "fix: bad quoting in hook scripts",
      |  "state": "closed",
      |  "user": {"id": 91002, "login": "patdyn"},
      |  "assignee": null,
      |  "assignees": null,
      |  "milestone": null,
      |  "due_date": null,
      |  "labels": [],
      |  "merged": true,
      |  "merged_at": "2026-08-01T19:15:31+02:00",
      |  "merge_commit_sha": "38615e78ed86c1eaaadd086f00a807ea4cc96a19",
      |  "merged_by": {"id": 222642, "login": "mfenniak"},
      |  "closed_at": "2026-08-01T19:15:31+02:00"
      |}""".stripMargin

  private val PullListBody: String = s"[$MergedBody]"

  private val ReviewListBody: String =
    """[{
      |  "id": 1654076,
      |  "state": "APPROVED",
      |  "user": {"id": 222642, "login": "mfenniak"},
      |  "team": null,
      |  "commit_id": "48079baa8d387f3ab770cc144c367409ddc2a879",
      |  "body": "",
      |  "html_url": ""
      |}]""".stripMargin

  private val CommitListBody: String =
    """[{
      |  "sha": "48079baa8d387f3ab770cc144c367409ddc2a879",
      |  "author": {"id": 91002, "login": "patdyn"},
      |  "commit": {"message": "fix: bad quoting in hook scripts"}
      |}]""".stripMargin

  private val FileListBody: String =
    """[{
      |  "filename": "modules/git/hook_generate.go",
      |  "status": "changed",
      |  "additions": 17,
      |  "deletions": 17,
      |  "changes": 34
      |}]""".stripMargin

  /** The paging headers `docs/HAZARDS.md` §5 captured verbatim, with the host swapped for the stub's. */
  private val PagedHeaders: List[Header] =
    List(
      Header("X-Total-Count", "167"),
      Header(
        "Link",
        "<https://forge.example/api/v1/repos/forgejo/forgejo/pulls?limit=30&page=2>; rel=\"next\"," +
          "<https://forge.example/api/v1/repos/forgejo/forgejo/pulls?limit=30&page=6>; rel=\"last\"",
      ),
    )

  /** What `golden/MANIFEST.md` records for `/pulls/{n}/reviews`: a total, and no `Link` at all. */
  private val TotalOnly: List[Header] = List(Header("X-Total-Count", "3"))

  /** A 404 shaped like `golden/error/404-repo-not-found.json`: a Go symbol for a message, and the useful text in
    * `errors`.
    */
  private val NotFoundBody: String =
    """{"message":"GetPullRequestByIndex","url":"https://codeberg.org/api/swagger",""" +
      """"errors":["pull request does not exist"]}"""

  private val ValidationBody: String =
    """{"message":"ListPullRequests","url":"https://codeberg.org/api/swagger","errors":["invalid state"]}"""

  /** The ordinary "no" from a merge: Forgejo answers `405` when the merge is refused rather than failing outright. */
  private val RefusedBody: String =
    """{"message":"Merge","url":"https://codeberg.org/api/swagger","errors":["The pull request has merge conflicts"]}"""

  /** Retries promptly and predictably: the default policy would make the retry tests take a quarter of a second. */
  private val PromptRetry: RetryPolicy = RetryPolicy(
    maxAttempts       = 3,
    baseDelay         = 1.milli,
    maxDelay          = 5.millis,
    jitter            = Jitter.None,
    respectRetryAfter = false,
  )
