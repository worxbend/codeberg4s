package com.worxbend.codeberg4s.users

import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.client.FutureExec
import com.worxbend.codeberg4s.client.FutureTimer
import com.worxbend.codeberg4s.codec.ApiErrorBodyCodec
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.Telemetry
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.repositories.Repository
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

/** [[UserApi]] end to end over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The subject is the group's own wiring — which path each operation dials, which query parameters it sends, how a page
  * reads its own metadata, and that both rails report the same failure. Field-level decoding is asserted in
  * `modules/codec` against the golden captures, so the bodies here are reduced to the keys these tests name.
  */
final class UserApiSuite extends FunSuite:

  private given ExecutionContext = munitExecutionContext

  private val Handle: Username = orFail(Username.from("earl-warren"))

  private val Instance: BaseUri = orFail(BaseUri.from("https://forge.example/api/v1"))

  private val FirstPage: PageParams = PageParams(PageNumber.First, orFail(PageSize.from(3)))

  // --- reading one user -----------------------------------------------------

  test("current maps GET /user to a domain user"):
    onApi(responding(200, UserApiSuite.UserBody)): users =>
      users.current().map: user =>
        assertEquals(user.id, 73579L)
        assertEquals(user.login.value, "earl-warren")
        assertEquals(user.fullName, Some("Earl Warren"))
        assertEquals(user.followersCount, 47L)

  test("current targets /user, the current-credentials path, and not /users"):
    recording(200, UserApiSuite.UserBody): (backend, users) =>
      users.current().map(_ => assertEquals(dialled(backend), "https://forge.example/api/v1/user"))

  test("get targets /users/{username} on the configured instance"):
    recording(200, UserApiSuite.UserBody): (backend, users) =>
      users
        .get(Handle)
        .map(_ => assertEquals(dialled(backend), "https://forge.example/api/v1/users/earl-warren"))

  test("get maps an organisation-shaped payload to a user, because the wire does not distinguish them"):
    onApi(responding(200, UserApiSuite.OrganizationShapedBody)): users =>
      users.get(Handle).map(user => assertEquals(user.login.value, "forgejo"))

  // --- searching ------------------------------------------------------------

  test("search decodes the ok/data envelope rather than a bare array"):
    onApi(responding(200, UserApiSuite.SearchBody)): users =>
      users.search("earl", FirstPage).map: page =>
        assertEquals(page.size, 2)
        assertEquals(page.items.map(_.login.value), Vector("0x20fearless", "earl-warren"))

  test("search sends the keyword as q, alongside page and limit"):
    recording(200, UserApiSuite.SearchBody): (backend, users) =>
      users
        .search("earl", FirstPage)
        .map(_ => assertEquals(dialled(backend), "https://forge.example/api/v1/users/search?q=earl&page=1&limit=3"))

  test("a search body that really is a bare array does not decode, which is why the envelope exists"):
    onApi(responding(200, UserApiSuite.UserListBody)): users =>
      users.attempt.search("earl", FirstPage).map:
        case Left(CodebergError.DecodingFailed(ctx, _, _, _)) => assertEquals(ctx.operation, UserApi.SearchOperation)
        case other                                            => fail(s"expected a decoding failure, got $other")

  // --- paging ---------------------------------------------------------------

  test("a page reads its neighbours from the Link header and its total from x-total-count"):
    onApi(respondingWith(200, UserApiSuite.RepositoryListBody, UserApiSuite.PagedHeaders)): users =>
      users.repositories(Handle, FirstPage).map: page =>
        assertEquals(page.totalCount, Some(25))
        assertEquals(page.nextPage.map(_.value), Some(2))
        assertEquals(page.prevPage, None)
        assertEquals(page.isLast, false)

  test("a page shorter than the requested limit is not the last page"):
    onApi(respondingWith(200, UserApiSuite.RepositoryListBody, UserApiSuite.PagedHeaders)): users =>
      users.repositories(Handle, FirstPage).map: page =>
        assert(page.size < FirstPage.size.value, "the fixture must be shorter than the window it was asked for")
        assertEquals(page.isLast, false, "Forgejo clamps limit while echoing it, so a short page proves nothing")

  test("a page past the end is an empty page, not a failure"):
    onApi(respondingWith(200, "[]", UserApiSuite.LastPageHeaders)): users =>
      users.repositories(Handle, FirstPage).map: page =>
        assertEquals(page.items, Vector.empty[Repository])
        assertEquals(page.isLast, true)
        assertEquals(page.totalCount, Some(25))

  test("repositories returns the repository model wave 2 owns, not a copy of it"):
    onApi(respondingWith(200, UserApiSuite.RepositoryListBody, UserApiSuite.PagedHeaders)): users =>
      users.repositories(Handle, FirstPage).map: page =>
        assertEquals(page.items.map(_.slug.value), Vector("earl-warren/website", "earl-warren/woodpecker-forgejo"))
        assertEquals(page.items.head.starsCount, 4L)

  test("an element that cannot be converted fails the page at that element's path"):
    onApi(responding(200, UserApiSuite.UserListWithBadElementBody)): users =>
      users.attempt.followers(Handle, FirstPage).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[1].login")
        case other                                             => fail(s"expected a decoding failure, got $other")

  // --- the remaining paths --------------------------------------------------

  test("followers targets /users/{username}/followers with page and limit"):
    recording(200, UserApiSuite.UserListBody): (backend, users) =>
      users
        .followers(Handle, FirstPage)
        .map: _ =>
          assertEquals(dialled(backend), "https://forge.example/api/v1/users/earl-warren/followers?page=1&limit=3")

  test("following targets /users/{username}/following with page and limit"):
    recording(200, UserApiSuite.UserListBody): (backend, users) =>
      users
        .following(Handle, FirstPage)
        .map: _ =>
          assertEquals(dialled(backend), "https://forge.example/api/v1/users/earl-warren/following?page=1&limit=3")

  test("every listing sends page and limit together, because limit alone is ignored by some endpoints"):
    recording(200, UserApiSuite.UserListBody): (backend, users) =>
      users.followers(Handle, PageParams(PageNumber.First.next, orFail(PageSize.from(50)))).map: _ =>
        assert(dialled(backend).endsWith("?page=2&limit=50"), dialled(backend))

  test("currentKeys targets /user/keys and maps the payload to public keys"):
    recording(200, UserApiSuite.KeyListBody): (backend, users) =>
      users.currentKeys(FirstPage).map: page =>
        assertEquals(dialled(backend), "https://forge.example/api/v1/user/keys?page=1&limit=3")
        assertEquals(page.items.map(_.id), Vector(12L, 13L))
        assertEquals(page.items.head.title, Some("laptop"))
        assertEquals(page.items(1).isReadOnly, true)

  test("keys targets /users/{username}/keys"):
    recording(200, UserApiSuite.KeyListBody): (backend, users) =>
      users
        .keys(Handle, FirstPage)
        .map: _ =>
          assertEquals(dialled(backend), "https://forge.example/api/v1/users/earl-warren/keys?page=1&limit=3")

  test("a username that would forge a path never reaches a request, because it never becomes a Username"):
    assert(Username.from("earl-warren/../../admin/users").isLeft)

  // --- failures, on both rails ----------------------------------------------

  test("a 404 fails the convenience rail with a CodebergException carrying the Api failure"):
    onApi(responding(404, UserApiSuite.NotFoundBody)): users =>
      users.get(Handle).failed.map:
        case CodebergException(error) => assertEquals(summary(error), UserApiSuite.ExpectedNotFound)
        case other                    => fail(s"expected a CodebergException, got $other")

  test("a 404 reaches the typed rail as a Left reporting the very same failure"):
    onApi(responding(404, UserApiSuite.NotFoundBody)): users =>
      for
        raised <- users.get(Handle).failed
        typed  <- users.attempt.get(Handle)
      yield assertRailsAgree(raised, typed, UserApiSuite.ExpectedNotFound)

  test("a 401 on the current-credentials keys listing reaches both rails identically"):
    onApi(responding(401, UserApiSuite.UnauthorizedBody)): users =>
      for
        raised <- users.currentKeys(FirstPage).failed
        typed  <- users.attempt.currentKeys(FirstPage)
      yield assertRailsAgree(raised, typed, UserApiSuite.ExpectedUnauthorized)

  test("a 422 on search reaches both rails identically"):
    onApi(responding(422, UserApiSuite.ValidationBody)): users =>
      for
        raised <- users.search("earl", FirstPage).failed
        typed  <- users.attempt.search("earl", FirstPage)
      yield assertRailsAgree(raised, typed, UserApiSuite.ExpectedValidation)

  test("every operation id is the one the group publishes, so an alert can be written against it"):
    assertEquals(
      List(
        UserApi.CurrentOperation,
        UserApi.GetOperation,
        UserApi.SearchOperation,
        UserApi.RepositoriesOperation,
        UserApi.FollowersOperation,
        UserApi.FollowingOperation,
        UserApi.CurrentKeysOperation,
        UserApi.KeysOperation,
      ),
      List(
        "users.current",
        "users.get",
        "users.search",
        "users.repos",
        "users.followers",
        "users.following",
        "users.currentKeys",
        "users.keys",
      ),
    )

  // --- assertions -----------------------------------------------------------

  /** Both rails must report the same failure, so the choice between them is a choice of style and nothing else. */
  private def assertRailsAgree[A](
      raised: Throwable,
      typed: Either[CodebergError, A],
      expected: (String, Int, Option[String]),
  ): Unit =
    (raised, typed) match
      case (CodebergException(convenience), Left(materialised)) =>
        assertEquals(summary(materialised), summary(convenience))
        assertEquals(summary(materialised), expected)
      case (convenience, materialised)                          =>
        fail(s"the rails disagreed: $convenience versus $materialised")

  /** An `Api` failure projected onto the parts that do not depend on wall-clock time, so two calls are comparable. */
  private def summary(error: CodebergError): (String, Int, Option[String]) =
    error match
      case CodebergError.Api(ctx, status, body, _) => (ctx.operation, status, body.message)
      case other                                   => fail(s"expected an Api failure, got ${other.describe}")

  // --- fixtures -------------------------------------------------------------

  private def responding(status: Int, body: String): Backend[Future] =
    respondingWith(status, body, Seq.empty)

  private def respondingWith(status: Int, body: String, headers: Seq[Header]): Backend[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status), headers))

  private def dialled(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.toString
      case None               => fail("no request reached the backend")

  /** Runs `use` against a [[UserApi]] recording what it dialled. */
  private def recording[A](status: Int, body: String)(
      use: (RecordingBackend, UserApi) => Future[A]
  ): Future[A] =
    val backend = RecordingBackend(responding(status, body))

    onApi(backend)(users => use(backend, users))

  /** Builds a [[UserApi]] on `backend`, releasing the timer thread whatever the outcome.
    *
    * The group is constructed directly rather than through `CodebergClient`, because wiring it into the façade is the
    * orchestrator's step and this suite must not wait for it.
    */
  private def onApi[A](backend: Backend[Future])(use: UserApi => Future[A]): Future[A] =
    given Exec[Future] = FutureExec()

    val timer  = FutureTimer()
    val config = CodebergConfig(Auth.Anonymous).copy(baseUri = Instance, retry = UserApiSuite.PromptRetry)

    val pipeline = ApiPipeline[Future](
      SttpHttpPort(backend, config),
      config,
      timer,
      Telemetry.noOp[Future],
      ApiErrorBodyCodec.parse,
    )

    use(UserApi(pipeline)).transform: outcome =>
      timer.close()
      outcome

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

/** The response bodies and headers this suite stubs, kept out of the tests so each reads as one behaviour. */
object UserApiSuite:

  /** `golden/user/user-single.json`, reduced to the keys these tests assert on. */
  private val UserBody: String =
    """{
      |  "id": 73579,
      |  "login": "earl-warren",
      |  "full_name": "Earl Warren",
      |  "email": "earl-warren@noreply.codeberg.org",
      |  "visibility": "public",
      |  "followers_count": 47,
      |  "following_count": 2,
      |  "last_login": "0001-01-01T00:00:00Z",
      |  "created": "2022-11-26T18:56:24+01:00"
      |}""".stripMargin

  /** `golden/user/user-single-org-shaped.json` reduced: an organisation is a user on the wire. */
  private val OrganizationShapedBody: String =
    """{"id": 70422, "login": "forgejo", "full_name": "Forgejo", "website": "https://forgejo.org"}"""

  /** The shape `golden/user/user-search.json` has: an envelope, never a bare array. */
  private val SearchBody: String =
    """{
      |  "data": [
      |    {"id": 165118, "login": "0x20fearless"},
      |    {"id": 73579, "login": "earl-warren", "full_name": "Earl Warren"}
      |  ],
      |  "ok": true
      |}""".stripMargin

  private val UserListBody: String =
    """[{"id": 165118, "login": "0x20fearless"}, {"id": 73579, "login": "earl-warren"}]"""

  /** A list whose second element carries no `login`, so the failure must be reported at `$[1].login`. */
  private val UserListWithBadElementBody: String =
    """[{"id": 165118, "login": "0x20fearless"}, {"id": 73579}]"""

  /** `golden/user/user-repos-list.json` reduced to the keys these tests assert on. */
  private val RepositoryListBody: String =
    """[
      |  {
      |    "id": 1,
      |    "name": "website",
      |    "full_name": "earl-warren/website",
      |    "owner": {"id": 73579, "login": "earl-warren"},
      |    "stars_count": 4
      |  },
      |  {
      |    "id": 2,
      |    "name": "woodpecker-forgejo",
      |    "full_name": "earl-warren/woodpecker-forgejo",
      |    "owner": {"id": 73579, "login": "earl-warren"}
      |  }
      |]""".stripMargin

  private val KeyListBody: String =
    """[
      |  {"id": 12, "key": "ssh-ed25519 AAAAC3Nza laptop", "title": "laptop", "verified": true},
      |  {"id": 13, "key": "ssh-ed25519 AAAAC3Nzb desk", "title": "workstation", "read_only": true}
      |]""".stripMargin

  /** What a first page of three carries: a `next` link, a `last` link, and a total. */
  private val PagedHeaders: Seq[Header] = Seq(
    Header(
      "Link",
      "<https://forge.example/api/v1/users/earl-warren/repos?limit=3&page=2>; rel=\"next\"," +
        "<https://forge.example/api/v1/users/earl-warren/repos?limit=3&page=9>; rel=\"last\"",
    ),
    Header("X-Total-Count", "25"),
  )

  /** What a page past the end carries: `first` and `prev`, never `next`. See `docs/HAZARDS.md` §5. */
  private val LastPageHeaders: Seq[Header] = Seq(
    Header(
      "Link",
      "<https://forge.example/api/v1/users/earl-warren/repos?limit=3&page=1>; rel=\"first\"," +
        "<https://forge.example/api/v1/users/earl-warren/repos?limit=3&page=8>; rel=\"prev\"",
    ),
    Header("X-Total-Count", "25"),
  )

  /** `golden/error/404-user-not-found.json`, with the requested handle swapped in. No `errors` key — Forgejo omits it
    * on this endpoint, which is why [[com.worxbend.codeberg4s.ApiErrorBody]] defaults it to empty.
    */
  private val NotFoundBody: String =
    """{"message": "user redirect does not exist [name: earl-warren]",
      |"url": "https://codeberg.org/api/swagger"}""".stripMargin

  /** The `message` of [[NotFoundBody]], which is what a failure carries. */
  private val NotFoundMessage: String = "user redirect does not exist [name: earl-warren]"

  /** `golden/error/401-token-required.json`, in one line. */
  private val UnauthorizedBody: String =
    """{"message": "token is required", "url": "https://codeberg.org/api/swagger"}"""

  /** `golden/error/422-invalid-sort.json`, in one line. */
  private val ValidationBody: String =
    """{"message": "Invalid sort mode: \"bogus\"", "url": "https://codeberg.org/api/swagger"}"""

  /** The `message` of [[ValidationBody]]. */
  private val ValidationMessage: String = "Invalid sort mode: \"bogus\""

  /** The `Api` failure each stubbed error body must produce, projected onto the parts that do not depend on a clock. */
  private val ExpectedNotFound: (String, Int, Option[String]) =
    (UserApi.GetOperation, 404, Some(NotFoundMessage))

  private val ExpectedUnauthorized: (String, Int, Option[String]) =
    (UserApi.CurrentKeysOperation, 401, Some("token is required"))

  private val ExpectedValidation: (String, Int, Option[String]) =
    (UserApi.SearchOperation, 422, Some(ValidationMessage))

  /** Retries promptly and predictably: the default policy would make a retried failure take a quarter of a second. */
  private val PromptRetry: RetryPolicy = RetryPolicy(
    maxAttempts       = 3,
    baseDelay         = 1.milli,
    maxDelay          = 5.millis,
    jitter            = Jitter.None,
    respectRetryAfter = false,
  )
