package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.ClientSuiteHarness
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.users.UserVisibility
import com.worxbend.codeberg4s.users.Username

import sttp.client4.Backend
import sttp.client4.testing.BackendStub
import sttp.client4.testing.RecordingBackend
import sttp.client4.testing.ResponseStub
import sttp.model.Header
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.Future

/** [[OrganizationApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The subject is the wiring — which URI is dialled, which query parameters are sent, what each rail does with a
  * failure, and what the paging headers are allowed to decide. Decoding itself is asserted against the golden captures
  * in `modules/codec`, so the payloads here are small hand-written bodies chosen to exercise a seam.
  */
final class OrganizationApiSuite extends FunSuite with ClientSuiteHarness:

  private val Org: OrgName = orFail(OrgName.from("forgejo"))

  private val Maintainers: TeamId = orFail(TeamId.from(42L))

  private val Account: Username = orFail(Username.from("earl-warren"))

  // --- single reads ---------------------------------------------------------

  test("a single-organisation read maps the instance's payload to a domain organisation"):
    onApi(responding(200, OrganizationApiSuite.OrgBody)): api =>
      api.get(Org).map: organization =>
        assertEquals(organization.id, 70422L)
        assertEquals(organization.name.value, "forgejo")
        assertEquals(organization.fullName, Some("Forgejo"))
        assertEquals(organization.visibility, Some(UserVisibility.Public))

  test("a single-organisation read targets /orgs/{org} on the configured instance"):
    val backend = RecordingBackend(responding(200, OrganizationApiSuite.OrgBody))

    onApi(backend): api =>
      api.get(Org).map(_ => assertEquals(dialled(backend), "https://forge.example/api/v1/orgs/forgejo"))

  test("a single-team read is rooted at /teams/{id}, not below the organisation"):
    val backend = RecordingBackend(responding(200, OrganizationApiSuite.TeamBody))

    onApi(backend): api =>
      api.getTeam(Maintainers).map: team =>
        assertEquals(dialled(backend), "https://forge.example/api/v1/teams/42")
        assertEquals(team.id.value, 42L)
        assertEquals(team.name, "maintainers")

  // --- request shape --------------------------------------------------------

  test("orgs.list sends page and limit together, because limit alone is silently ignored"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .list(window(2, 25))
        .map: _ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs")
          assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "25"))

  test("orgs.repos.list targets the organisation's repositories and pages them"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .repositories(Org, PageParams.First)
        .map: _ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/repos")
          assertEquals(queryOf(backend), List("page" -> "1", "limit" -> "30"))

  test("orgs.members.list and orgs.publicMembers.list are different paths, not one with a flag"):
    val members       = RecordingBackend(responding(200, "[]"))
    val publicMembers = RecordingBackend(responding(200, "[]"))

    for
      _ <- onApi(members)(api => api.members(Org, PageParams.First))
      _ <- onApi(publicMembers)(api => api.publicMembers(Org, PageParams.First))
    yield
      assertEquals(pathOf(members), "https://forge.example/api/v1/orgs/forgejo/members")
      assertEquals(pathOf(publicMembers), "https://forge.example/api/v1/orgs/forgejo/public_members")

  test("orgs.teams.list targets the organisation's teams"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .teams(Org, PageParams.First)
        .map: _ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/teams")
          assertEquals(queryOf(backend), List("page" -> "1", "limit" -> "30"))

  test("orgs.teams.members.list and orgs.teams.repos.list hang off the instance-rooted team path"):
    val members      = RecordingBackend(responding(200, "[]"))
    val repositories = RecordingBackend(responding(200, "[]"))

    for
      _ <- onApi(members)(api => api.teamMembers(Maintainers, PageParams.First))
      _ <- onApi(repositories)(api => api.teamRepositories(Maintainers, PageParams.First))
    yield
      assertEquals(pathOf(members), "https://forge.example/api/v1/teams/42/members")
      assertEquals(pathOf(repositories), "https://forge.example/api/v1/teams/42/repos")

  test("orgs.userOrgs.list names a person, so it sits under /users/{username}/orgs"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .userOrganizations(Account, window(3, 10))
        .map: _ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/users/earl-warren/orgs")
          assertEquals(queryOf(backend), List("page" -> "3", "limit" -> "10"))

  // --- payloads and paging --------------------------------------------------

  test("orgs.list decodes a bare array, because this group meets no search envelope"):
    onApi(responding(200, OrganizationApiSuite.OrgListBody)): api =>
      api.list(PageParams.First).map: page =>
        assertEquals(page.items.map(_.name.value), Vector("forgejo"))

  test("orgs.members.list yields the User model wave 1 owns, not a second membership type"):
    onApi(responding(200, OrganizationApiSuite.MemberListBody)): api =>
      api.members(Org, PageParams.First).map: page =>
        assertEquals(page.items.map(_.login.value), Vector("earl-warren"))
        assertEquals(page.items.map(_.id), Vector(73579L))

  test("orgs.repos.list yields the Repository model wave 2 owns"):
    onApi(responding(200, OrganizationApiSuite.RepoListBody)): api =>
      api.repositories(Org, PageParams.First).map: page =>
        assertEquals(page.items.map(_.slug.value), Vector("forgejo/forgejo"))

  test("orgs.list ends where rel=next says it ends, not where a short page suggests"):
    onApi(responding(200, OrganizationApiSuite.OrgListBody, OrganizationApiSuite.PagedHeaders)): api =>
      api.list(window(1, 30)).map: page =>
        assertEquals(page.size, 1)
        assertEquals(page.totalCount, Some(24159))
        assertEquals(page.nextPage.map(_.value), Some(2))
        assertEquals(page.isLast, false)

  test("a page whose response carries no Link header reports itself as the last one"):
    onApi(responding(200, OrganizationApiSuite.OrgListBody)): api =>
      api.list(PageParams.First).map: page =>
        assertEquals(page.isLast, true)
        assertEquals(page.nextPage, None)

  test("a page past the end is an empty page, not a failure — Forgejo answers 200 with []"):
    onApi(responding(200, "[]")): api =>
      api.teams(Org, PageParams.First).map: page =>
        assertEquals(page.items, Vector.empty[Team])
        assertEquals(page.isLast, true)

  // --- retries --------------------------------------------------------------

  test("every operation here is a GET, so a 503 is retried rather than surfaced"):
    val backend = RecordingBackend(
      BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(
        ResponseStub.adjust("", StatusCode(503)),
        ResponseStub.adjust(OrganizationApiSuite.OrgBody, StatusCode(200)),
      )
    )

    onApi(backend): api =>
      api.get(Org).map: organization =>
        assertEquals(organization.name.value, "forgejo")
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  // --- failures -------------------------------------------------------------

  test("a 401 fails the convenience rail with a CodebergException carrying the Api failure"):
    onApi(responding(401, OrganizationApiSuite.UnauthorizedBody)): api =>
      api.teams(Org, PageParams.First).failed.map:
        case CodebergException(error) =>
          assertEquals(summary(error), (OrganizationApi.TeamsOperation, 401, Some("token is required")))
        case other                    => fail(s"expected a CodebergException, got $other")

  test("the 401 golden/MANIFEST.md records for an anonymous team listing reaches both rails identically"):
    onApi(responding(401, OrganizationApiSuite.UnauthorizedBody)): api =>
      for
        raised <- api.teams(Org, PageParams.First).failed
        typed  <- api.attempt.teams(Org, PageParams.First)
      yield assertRailsAgree(raised, typed)

  test("the 401 an anonymous /users/{username}/orgs answers reaches both rails identically"):
    onApi(responding(401, OrganizationApiSuite.UnauthorizedBody)): api =>
      for
        raised <- api.userOrganizations(Account, PageParams.First).failed
        typed  <- api.attempt.userOrganizations(Account, PageParams.First)
      yield
        assertEquals(operationOf(typed), OrganizationApi.UserOrganizationsOperation)
        assertRailsAgree(raised, typed)

  test("a 404 on a single-organisation read reaches both rails identically"):
    onApi(responding(404, OrganizationApiSuite.NotFoundBody)): api =>
      for
        raised <- api.get(Org).failed
        typed  <- api.attempt.get(Org)
      yield
        assertEquals(detailsOf(typed), List("organization does not exist [name: forgejo]"))
        assertRailsAgree(raised, typed)

  test("a 404 on a single-team read reaches both rails identically"):
    onApi(responding(404, OrganizationApiSuite.NotFoundBody)): api =>
      for
        raised <- api.getTeam(Maintainers).failed
        typed  <- api.attempt.getTeam(Maintainers)
      yield
        assertEquals(operationOf(typed), OrganizationApi.GetTeamOperation)
        assertRailsAgree(raised, typed)

  test("a 403 on a member listing reaches both rails identically"):
    onApi(responding(403, OrganizationApiSuite.UnauthorizedBody)): api =>
      for
        raised <- api.members(Org, PageParams.First).failed
        typed  <- api.attempt.members(Org, PageParams.First)
      yield assertRailsAgree(raised, typed)

  test("a 400 is an Api failure too — Forgejo uses it for validation alongside 422"):
    onApi(responding(400, OrganizationApiSuite.NotFoundBody)): api =>
      api.attempt.list(PageParams.First).map:
        case Left(CodebergError.Api(_, status, _, _)) => assertEquals(status, 400)
        case other                                    => fail(s"expected an Api failure, got $other")

  test("a 200 whose payload does not fit the model becomes DecodingFailed, never an escaping codec exception"):
    onApi(responding(200, """{"name":"forgejo"}""")): api =>
      api.attempt.get(Org).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.id")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a bad element of a list body reports its position, all the way through the pipeline"):
    onApi(responding(200, """[{"id":1,"name":"a"},{"id":2}]""")): api =>
      api.attempt.list(PageParams.First).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[1].name")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("an organisation name that could forge a path fails decoding rather than reaching the domain"):
    onApi(responding(200, """{"id":1,"name":"forgejo/teams"}""")): api =>
      api.attempt.get(Org).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.name")
        case other                                             => fail(s"expected a decoding failure, got $other")

  // --- harness --------------------------------------------------------------

  /** Builds the API under test on a pipeline over `backend`, releasing the timer whatever happens. */
  private def onApi[A](backend: Backend[Future])(use: OrganizationApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(OrganizationApi(pipeline)))

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour. */
object OrganizationApiSuite:

  /** `golden/organization/org-single.json`, reduced to the keys these tests assert on. */
  private val OrgBody: String =
    """{
      |  "id": 70422,
      |  "name": "forgejo",
      |  "full_name": "Forgejo",
      |  "email": "",
      |  "description": "Beyond coding. We forge.",
      |  "website": "https://forgejo.org",
      |  "location": "",
      |  "visibility": "public",
      |  "repo_admin_change_team_access": true,
      |  "created": "2022-11-06T07:18:11+01:00",
      |  "username": "forgejo"
      |}""".stripMargin

  private val OrgListBody: String = s"[$OrgBody]"

  /** A team as the pinned spec defines one; no capture exists, see `TeamDtoSuite`. */
  private val TeamBody: String =
    """{"id": 42, "name": "maintainers", "permission": "write", "units": ["repo.code"]}"""

  private val MemberListBody: String =
    """[{"id": 73579, "login": "earl-warren", "full_name": "Earl Warren"}]"""

  private val RepoListBody: String =
    """[{
      |  "id": 70845,
      |  "name": "forgejo",
      |  "full_name": "forgejo/forgejo",
      |  "owner": {"id": 70422, "login": "forgejo"}
      |}]""".stripMargin

  /** The paging headers `docs/HAZARDS.md` §5 captured verbatim, with the host swapped for the stub's and the total the
    * organisation listing reported on the day `golden/organization/org-list.json` was taken.
    */
  private val PagedHeaders: List[Header] =
    List(
      Header("X-Total-Count", "24159"),
      Header(
        "Link",
        "<https://forge.example/api/v1/orgs?limit=30&page=2>; rel=\"next\"," +
          "<https://forge.example/api/v1/orgs?limit=30&page=806>; rel=\"last\"",
      ),
    )

  /** `golden/error/401-org-teams.json` and `golden/error/401-user-orgs.json` are byte-identical to this. */
  private val UnauthorizedBody: String =
    """{"message":"token is required","url":"https://codeberg.org/api/swagger"}"""

  /** A 404 shaped like `golden/error/404-user-not-found.json`: a Go symbol for a message, and the useful text in
    * `errors`.
    */
  private val NotFoundBody: String =
    """{"message":"GetOrgByName","url":"https://codeberg.org/api/swagger",
      |"errors":["organization does not exist [name: forgejo]"]}""".stripMargin
