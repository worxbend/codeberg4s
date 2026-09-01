package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.CodebergError

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend

import scala.concurrent.Future

/** [[RepositoryWikiApi]] over a `BackendStub`.
  *
  * Two things here are unusual enough to be worth asserting rather than describing: a page name with a slash reaches
  * the wire as several segments, and '''no''' wiki write is retried — the group's Scaladoc argues that from the fact
  * that every wiki write makes a Git commit, and these tests are what keeps the argument honest.
  */
final class RepositoryWikiApiSuite extends HookApiSuite:

  private val Home: WikiPageName = orFail(WikiPageName.from("Home"))

  private val SubPage: WikiPageName = orFail(WikiPageName.from("Deployment/Kubernetes"))

  // --- reads ----------------------------------------------------------------

  test("the page listing targets wiki/pages and pages it"):
    val backend = RecordingBackend(responding(200, RepositoryWikiApiSuite.PageListBody))

    onApi(backend): api =>
      api.listPages(Handle, Name, window(2, 25)).map: page =>
        assertEquals(pathOf(backend), s"$Repository/wiki/pages")
        assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "25"))
        assertEquals(page.items.map(_.title), Vector("Home"))

  test("the page listing ends where rel=next says it ends"):
    val headers = pagedHeaders(12, s"$Repository/wiki/pages?limit=30&page=2")

    onApi(responding(200, RepositoryWikiApiSuite.PageListBody, headers)): api =>
      api.listPages(Handle, Name, window(1, 30)).map: page =>
        assertEquals(page.totalCount, Some(12))
        assertEquals(page.nextPage.map(_.value), Some(2))

  test("a single-page read addresses the page under wiki/page and decodes its base64 content"):
    val backend = RecordingBackend(responding(200, RepositoryWikiApiSuite.PageBody))

    onApi(backend): api =>
      api.page(Handle, Name, Home).map: page =>
        assertEquals(pathOf(backend), s"$Repository/wiki/page/Home")
        assertEquals(page.content.flatMap(_.text), Some("# Home\n"))

  test("a page name with a slash reaches the wire as several segments, not one encoded one"):
    val backend = RecordingBackend(responding(200, RepositoryWikiApiSuite.PageBody))

    onApi(backend): api =>
      api
        .page(Handle, Name, SubPage)
        .map(_ => assertEquals(pathOf(backend), s"$Repository/wiki/page/Deployment/Kubernetes"))

  test("the revision listing unwraps the commits envelope and sends page without limit"):
    val backend = RecordingBackend(responding(200, RepositoryWikiApiSuite.RevisionsBody))

    onApi(backend): api =>
      api.revisions(Handle, Name, Home, window(3, 25)).map: page =>
        assertEquals(pathOf(backend), s"$Repository/wiki/revisions/Home")
        assertEquals(queryOf(backend), List("page" -> "3"))
        assertEquals(page.items.map(_.sha.value), Vector("d0c4f1e2a3b4c5d6e7f8091a2b3c4d5e6f708192"))

  test("the body's own count is not the page's total count, which comes from the header"):
    val headers = pagedHeaders(41, s"$Repository/wiki/revisions/Home?page=2")

    onApi(responding(200, RepositoryWikiApiSuite.RevisionsBody, headers)): api =>
      api.revisions(Handle, Name, Home, window(1, 30)).map(page => assertEquals(page.totalCount, Some(41)))

  // --- writes ---------------------------------------------------------------

  test("wiki.createPage POSTs the rendered options to wiki/new"):
    val backend = RecordingBackend(responding(201, RepositoryWikiApiSuite.PageBody))

    onApi(backend): api =>
      api
        .createPage(Handle, Name, CreateWikiPage.ofText(Home, "# Home\n").withMessage("start"))
        .map: _ =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), s"$Repository/wiki/new")
          assertEquals(bodyOf(backend), """{"title":"Home","content_base64":"IyBIb21lCg==","message":"start"}""")

  test("wiki.createPage is never retried, because a repeat would write a second commit"):
    val backend = RecordingBackend(flakyThenOk(201, RepositoryWikiApiSuite.PageBody))

    onApi(backend): api =>
      api.attempt
        .createPage(Handle, Name, CreateWikiPage.ofText(Home, "# Home\n"))
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a wiki create must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("wiki.editPage PATCHes the named page and omits the title unless it is a rename"):
    val backend = RecordingBackend(responding(200, RepositoryWikiApiSuite.PageBody))

    onApi(backend): api =>
      api
        .editPage(Handle, Name, Home, EditWikiPage.ofText("# Home\n"))
        .map: _ =>
          assertEquals(methodOf(backend), "PATCH")
          assertEquals(pathOf(backend), s"$Repository/wiki/page/Home")
          assertEquals(bodyOf(backend), """{"content_base64":"IyBIb21lCg=="}""")

  test("wiki.editPage sends the new title when the command moves the page"):
    val backend = RecordingBackend(responding(200, RepositoryWikiApiSuite.PageBody))

    onApi(backend): api =>
      api
        .editPage(Handle, Name, Home, EditWikiPage.ofText("# Home\n").movedTo(SubPage))
        .map(_ => assert(bodyOf(backend).contains(""""title":"Deployment/Kubernetes""""), bodyOf(backend)))

  test("wiki.editPage is never retried, because it commits and may rename"):
    val backend = RecordingBackend(flakyThenOk(200, RepositoryWikiApiSuite.PageBody))

    onApi(backend): api =>
      api.attempt
        .editPage(Handle, Name, Home, EditWikiPage.ofText("# Home\n"))
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a wiki edit must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the PATCH was retried")

  test("wiki.deletePage addresses the page and is never retried, because removing it commits too"):
    val backend = RecordingBackend(flakyThenOk(204, ""))

    onApi(backend): api =>
      api.attempt
        .deletePage(Handle, Name, Home)
        .map: outcome =>
          assertEquals(methodOf(backend), "DELETE")
          assertEquals(pathOf(backend), s"$Repository/wiki/page/Home")
          assert(outcome.isLeft, s"a 503 on a wiki delete must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the DELETE was retried")

  // --- failures -------------------------------------------------------------

  test("a 404 reaches both rails as the very same failure"):
    onApi(responding(404, HookApiSuite.NotFoundBody)): api =>
      for
        raised <- api.page(Handle, Name, Home).failed
        typed  <- api.attempt.page(Handle, Name, Home)
      yield assertRailsAgree(raised, typed)

  test("a 423 on a write is an ordinary Api failure, which is how an archived repository refuses"):
    onApi(responding(423, HookApiSuite.NotFoundBody)): api =>
      api.attempt.deletePage(Handle, Name, Home).map:
        case Left(CodebergError.Api(_, status, _, _)) => assertEquals(status, 423)
        case other                                    => fail(s"expected an Api failure, got $other")

  test("a 413 on a create is an ordinary Api failure, which is how a quota refuses"):
    onApi(responding(413, HookApiSuite.NotFoundBody)): api =>
      api.attempt.createPage(Handle, Name, CreateWikiPage.ofText(Home, "x")).map:
        case Left(CodebergError.Api(_, status, _, _)) => assertEquals(status, 413)
        case other                                    => fail(s"expected an Api failure, got $other")

  test("a page payload with no title becomes DecodingFailed at the field that was missing"):
    onApi(responding(200, """{"content_base64":"aGk="}""")): api =>
      api.attempt.page(Handle, Name, Home).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.title")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a bad revision inside the envelope reports its position under the commits key"):
    onApi(responding(200, """{"commits":[{"sha":"abcd1234"},{"message":"x"}]}""")): api =>
      api.attempt.revisions(Handle, Name, Home, window(1, 30)).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.commits[1].sha")
        case other                                             => fail(s"expected a decoding failure, got $other")

  private def onApi[A](backend: Backend[Future])(use: RepositoryWikiApi => Future[A]): Future[A] =
    onPipeline(backend, (pipeline, exec) => RepositoryWikiApi(pipeline)(using exec))(use)

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour. */
object RepositoryWikiApiSuite:

  private val CommitBody: String =
    """{"sha": "d0c4f1e2a3b4c5d6e7f8091a2b3c4d5e6f708192", "message": "start"}"""

  private val PageBody: String =
    s"""{
       |  "title": "Home",
       |  "content_base64": "IyBIb21lCg==",
       |  "sub_url": "Home",
       |  "last_commit": $CommitBody,
       |  "commit_count": 1
       |}""".stripMargin

  private val PageListBody: String =
    s"""[{"title": "Home", "sub_url": "Home", "last_commit": $CommitBody}]"""

  private val RevisionsBody: String =
    s"""{"commits": [$CommitBody], "count": 1}"""
