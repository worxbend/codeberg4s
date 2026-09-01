package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize

import sttp.client4.Backend
import sttp.client4.testing.BackendStub
import sttp.client4.testing.RecordingBackend
import sttp.client4.testing.ResponseStub
import sttp.model.Header
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** The repository endpoints end to end over a `BackendStub`: nothing here opens a socket.
  *
  * The subject is the wiring — the request each operation dials, how a page is assembled, and that both rails report
  * the same failure. Decoding itself is `modules/codec`'s business and is asserted there against the golden captures,
  * so the bodies below are small and hand-written.
  */
final class RepositoryApiSuite extends FunSuite:

  private given ExecutionContext = munitExecutionContext

  private val Handle: Owner = orFail(Owner.from("forgejo"))

  private val Name: RepoName = orFail(RepoName.from("forgejo"))

  private val Instance: BaseUri = orFail(BaseUri.from("https://forge.example/api/v1"))

  private val FirstPage: PageParams = PageParams.First

  // --- request shapes -------------------------------------------------------

  test("search dials /repos/search carrying q, page and limit"):
    dialling(RepositoryApiSuite.SearchBody)(_.repos.search("forgejo", FirstPage)): uri =>
      assertEquals(uri, "https://forge.example/api/v1/repos/search?q=forgejo&page=1&limit=30")

  test("branches dials the branches collection with the requested window"):
    val second = PageParams(FirstPage.page.next, orFail(PageSize.from(50)))

    dialling("[]")(_.repos.branches(Handle, Name, second)): uri =>
      assertEquals(uri, "https://forge.example/api/v1/repos/forgejo/forgejo/branches?page=2&limit=50")

  test("getBranch sends a slashed branch name as real path segments, because Forgejo routes it that way"):
    val branch = orFail(BranchName.from("v16.0/forgejo"))

    dialling(RepositoryApiSuite.BranchBody)(_.repos.getBranch(Handle, Name, branch)): uri =>
      assertEquals(uri, "https://forge.example/api/v1/repos/forgejo/forgejo/branches/v16.0/forgejo")

  test("getContents sends a nested file path as real path segments"):
    val path = orFail(ContentPath.from("models/user.go"))

    dialling(RepositoryApiSuite.FileBody)(_.repos.getContents(Handle, Name, path)): uri =>
      assertEquals(uri, "https://forge.example/api/v1/repos/forgejo/forgejo/contents/models/user.go")

  test("getRelease dials the release by its identifier"):
    val id = orFail(ReleaseId.from(11189746L))

    dialling(RepositoryApiSuite.ReleaseBody)(_.repos.getRelease(Handle, Name, id)): uri =>
      assertEquals(uri, "https://forge.example/api/v1/repos/forgejo/forgejo/releases/11189746")

  test("tags dials the tags collection"):
    dialling("[]")(_.repos.tags(Handle, Name, FirstPage)): uri =>
      assertEquals(uri, "https://forge.example/api/v1/repos/forgejo/forgejo/tags?page=1&limit=30")

  test("commits dials the commits collection"):
    dialling("[]")(_.repos.commits(Handle, Name, FirstPage)): uri =>
      assertEquals(uri, "https://forge.example/api/v1/repos/forgejo/forgejo/commits?page=1&limit=30")

  test("releases dials the releases collection"):
    dialling("[]")(_.repos.releases(Handle, Name, FirstPage)): uri =>
      assertEquals(uri, "https://forge.example/api/v1/repos/forgejo/forgejo/releases?page=1&limit=30")

  test("forks dials the forks collection"):
    dialling("[]")(_.repos.forks(Handle, Name, FirstPage)): uri =>
      assertEquals(uri, "https://forge.example/api/v1/repos/forgejo/forgejo/forks?page=1&limit=30")

  test("topics dials the topics collection"):
    dialling(RepositoryApiSuite.TopicsBody)(_.repos.topics(Handle, Name, FirstPage)): uri =>
      assertEquals(uri, "https://forge.example/api/v1/repos/forgejo/forgejo/topics?page=1&limit=30")

  // --- paging ---------------------------------------------------------------

  test("a page takes its next page from the Link header and never from how many items arrived"):
    val headers = List(
      Header("Link", """<https://forge.example/api/v1/repos/forgejo/forgejo/tags?page=2&limit=50>; rel="next""""),
      Header("X-Total-Count", "113"),
    )

    onStub(responding(200, RepositoryApiSuite.OneTag, headers)): client =>
      client.repos.tags(Handle, Name, PageParams(FirstPage.page, orFail(PageSize.from(50)))).map: page =>
        assertEquals(page.size, 1, "one item against a window of fifty")
        assertEquals(page.nextPage.map(_.value), Some(2), "a short page is not the last page")
        assertEquals(page.isLast, false)
        assertEquals(page.totalCount, Some(113))

  test("a response with no Link header is the last page"):
    onStub(responding(200, RepositoryApiSuite.OneTag, Nil)): client =>
      client.repos.tags(Handle, Name, FirstPage).map: page =>
        assertEquals(page.isLast, true)
        assertEquals(page.totalCount, None, "an absent x-total-count is unknown, not zero")

  test("a page past the end is an empty page rather than a failure"):
    onStub(responding(200, "[]", Nil)): client =>
      client.repos.branches(Handle, Name, FirstPage).map: page =>
        assertEquals(page.items, Vector.empty[Branch])
        assertEquals(page.isLast, true)

  test("the requested window is kept on the page, so the caller can resume"):
    onStub(responding(200, "[]", Nil)): client =>
      client.repos.branches(Handle, Name, FirstPage).map(page => assertEquals(page.params, FirstPage))

  // --- payloads -------------------------------------------------------------

  test("search unwraps the ok/data envelope into repositories"):
    onStub(responding(200, RepositoryApiSuite.SearchBody, Nil)): client =>
      client.repos.search("forgejo", FirstPage).map: page =>
        assertEquals(page.items.map(_.slug.value), Vector("forgejo/forgejo"))

  test("topics unwraps the topics envelope into a page of names"):
    onStub(responding(200, RepositoryApiSuite.TopicsBody, Nil)): client =>
      client.repos.topics(Handle, Name, FirstPage).map: page =>
        assertEquals(page.items.map(_.value), Vector("forge", "git"))

  test("a topic name that could not go back into a request path is a decoding failure"):
    onStub(responding(200, """{"topics": ["forge", "bad/name"]}""", Nil)): client =>
      client.repos.attempt.topics(Handle, Name, FirstPage).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.topics[1]")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("getContents answers the object arm as a file"):
    onStub(responding(200, RepositoryApiSuite.FileBody, Nil)): client =>
      client.repos.getContents(Handle, Name, orFail(ContentPath.from("README.md"))).map:
        case RepositoryContent.File(entry) => assertEquals(entry.meta.name, "README.md")
        case other                         => fail(s"expected a file, got $other")

  test("getContents answers the array arm as a directory, on the same call"):
    onStub(responding(200, RepositoryApiSuite.DirectoryBody, Nil)): client =>
      client.repos.getContents(Handle, Name, orFail(ContentPath.from("models"))).map:
        case RepositoryContent.Directory(entries) => assertEquals(entries.map(_.kind.wireName), Vector("file", "dir"))
        case other                                => fail(s"expected a directory, got $other")

  test("a body that is neither arm becomes DecodingFailed rather than an escaping codec exception"):
    onStub(responding(200, "\"nonsense\"", Nil)): client =>
      client.repos.attempt.getContents(Handle, Name, orFail(ContentPath.from("README.md"))).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("getBranch maps its payload to a domain branch"):
    onStub(responding(200, RepositoryApiSuite.BranchBody, Nil)): client =>
      client.repos.getBranch(Handle, Name, orFail(BranchName.from("forgejo"))).map: branch =>
        assertEquals(branch.name.value, "forgejo")
        assertEquals(branch.commit.sha.value, "647de8b3279b0ce6e9721cc651e431981725edf1")

  // --- failures -------------------------------------------------------------

  test("a 404 on getRelease reaches both rails as the very same Api failure"):
    val id = orFail(ReleaseId.from(42L))

    onStub(responding(404, RepositoryApiSuite.NotFoundBody, Nil)): client =>
      for
        raised <- client.repos.getRelease(Handle, Name, id).failed
        typed  <- client.repos.attempt.getRelease(Handle, Name, id)
      yield assertRailsAgree(raised, typed, RepositoryApi.GetReleaseOperation)

  test("a 422 on search reaches both rails as the very same Api failure"):
    onStub(responding(422, RepositoryApiSuite.InvalidQueryBody, Nil)): client =>
      for
        raised <- client.repos.search("bad", FirstPage).failed
        typed  <- client.repos.attempt.search("bad", FirstPage)
      yield assertRailsAgree(raised, typed, RepositoryApi.SearchOperation)

  test("a failing listing carries its own operation id, so alerts can tell the endpoints apart"):
    onStub(responding(404, RepositoryApiSuite.NotFoundBody, Nil)): client =>
      client.repos.attempt.commits(Handle, Name, FirstPage).map: result =>
        assertEquals(operationOf(result), Some(RepositoryApi.ListCommitsOperation))

  // --- assertions -----------------------------------------------------------

  private def assertRailsAgree[A](raised: Throwable, typed: Either[CodebergError, A], operation: String): Unit =
    (raised, typed) match
      case (CodebergException(convenience), Left(materialised)) =>
        assertEquals(summary(materialised), summary(convenience))
        assertEquals(summary(materialised)._1, operation)
      case (convenience, materialised)                          =>
        fail(s"the rails disagreed: $convenience versus $materialised")

  private def summary(error: CodebergError): (String, Int, Option[String]) =
    error match
      case CodebergError.Api(ctx, status, body, _) => (ctx.operation, status, body.message)
      case other                                   => fail(s"expected an Api failure, got ${other.describe}")

  private def operationOf[A](result: Either[CodebergError, A]): Option[String] =
    result.swap.toOption.map(error => summary(error)._1)

  // --- fixtures -------------------------------------------------------------

  private def responding(status: Int, body: String, headers: List[Header]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status), headers))

  /** Runs `call` against a recording backend and hands the dialled URI to `check`. */
  private def dialling[A](body: String)(call: CodebergClient => Future[A])(
      check: String => Unit
  ): Future[Unit] =
    val backend = RecordingBackend(responding(200, body, Nil))

    onStub(backend)(client => call(client).map(_ => check(dialled(backend))))

  private def dialled(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.toString
      case None               => fail("no request reached the backend")

  private def onStub[A](backend: Backend[Future])(use: CodebergClient => Future[A]): Future[A] =
    val client = CodebergClient.usingBackend(
      CodebergConfig(Auth.Anonymous).copy(baseUri = Instance),
      backend,
    )

    use(client).transform: outcome =>
      client.close()
      outcome

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour. */
object RepositoryApiSuite:

  /** The `{"ok", "data"}` shape of `golden/repository/search.json`, reduced to the keys asserted on. */
  private val SearchBody: String =
    """{"ok": true, "data": [
      |  {"id": 73144, "name": "forgejo", "full_name": "forgejo/forgejo",
      |   "owner": {"id": 1, "login": "forgejo"}, "topics": null}
      |]}""".stripMargin

  /** The shape of `golden/repository/branch-single.json`, reduced. */
  private val BranchBody: String =
    """{
      |  "name": "forgejo",
      |  "commit": {
      |    "id": "647de8b3279b0ce6e9721cc651e431981725edf1",
      |    "message": "Update module\n",
      |    "author": {"name": "Renovate Bot", "email": "bot@kriese.eu", "username": "viceice-bot"},
      |    "added": null, "removed": null, "modified": null
      |  },
      |  "protected": true,
      |  "required_approvals": 1,
      |  "status_check_contexts": [],
      |  "effective_branch_protection_name": ""
      |}""".stripMargin

  /** One element of `golden/repository/tags-list.json`. */
  private val OneTag: String =
    """[{"name": "v16.0.2", "id": "d7471ea487788c5d6f711a61436a45bf47601948",
      |  "commit": {"sha": "d7471ea487788c5d6f711a61436a45bf47601948"},
      |  "archive_download_count": {"zip": 5, "tar_gz": 119}}]""".stripMargin

  /** The shape of `golden/repository/release-latest.json`, reduced. */
  private val ReleaseBody: String =
    """{"id": 11189746, "tag_name": "v16.0.2", "name": "v16.0.2", "draft": false, "prerelease": false,
      |  "assets": [], "target_commitish": ""}""".stripMargin

  /** The object arm of the contents union. */
  private val FileBody: String =
    """{"name": "README.md", "path": "README.md", "sha": "e6f1b6c3d1bf880d606379609238c57fe60016db",
      |  "type": "file", "size": 5, "encoding": "base64", "content": "aGVsbG8=", "target": null,
      |  "submodule_git_url": null}""".stripMargin

  /** The array arm of the same union, on the same operation. */
  private val DirectoryBody: String =
    """[
      |  {"name": "user.go", "path": "models/user.go", "sha": "08ec82aed143bc7f33e9ef617abf211b4ace6174",
      |   "type": "file", "size": 520, "content": null, "encoding": null},
      |  {"name": "issues", "path": "models/issues", "sha": "99c5da8e4c82a1febe3b2f64def3609f4f7a740d",
      |   "type": "dir", "size": 0}
      |]""".stripMargin

  /** The `{"topics"}` envelope of `golden/repository/topics.json`. */
  private val TopicsBody: String = """{"topics": ["forge", "git"]}"""

  /** `golden/error/404-repo-not-found.json`, in one line. */
  private val NotFoundBody: String =
    """{"message": "The target couldn't be found.", "url": "https://codeberg.org/api/swagger", "errors": []}"""

  /** A 422 of the shape `docs/HAZARDS.md` §4 captured: a raw Go error and no `errors` array. */
  private val InvalidQueryBody: String =
    """{"message": "invalid sort type", "url": "https://codeberg.org/api/swagger"}"""
