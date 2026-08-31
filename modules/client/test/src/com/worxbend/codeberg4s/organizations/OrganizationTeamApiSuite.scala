package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.paging.PageParams

import sttp.client4.testing.RecordingBackend

import munit.FunSuite

import java.time.LocalDate

/** [[OrganizationTeamApi]] over a `BackendStub`.
  *
  * Two things are the subject. The first is which root a call uses: creating and searching hang off the organisation,
  * everything else off `/teams/{id}`. The second is the three-way retry split, which is where this class makes its
  * sharpest judgements — a team id may be repeated on, a renameable `{org}/{repo}` pair may not.
  */
final class OrganizationTeamApiSuite extends FunSuite with OrganizationStubs:

  private val Reviewers: TeamName = orFail(TeamName.from("reviewers"))

  private val Repo: RepoName = orFail(RepoName.from("forgejo"))

  // --- roots ----------------------------------------------------------------

  test("orgs.teams.create posts below the organisation, because a team is created inside one"):
    val backend = RecordingBackend(responding(201, OrganizationTeamApiSuite.TeamBody))

    onApi(backend): api =>
      api.teamAdmin
        .create(Org, CreateTeam.named(Reviewers).permitted(TeamPermission.Read).reaching("repo.code"))
        .map: team =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/teams")
          assertEquals(
            bodyOf(backend),
            """{"name":"reviewers","can_create_org_repo":false,"includes_all_repositories":false,""" +
              """"permission":"read","units":["repo.code"]}""",
          )
          assertEquals(team.id.value, 42L)

  test("orgs.teams.edit and orgs.teams.delete are rooted at the instance, not below the organisation"):
    val edited  = RecordingBackend(responding(200, OrganizationTeamApiSuite.TeamBody))
    val deleted = RecordingBackend(responding(204, ""))

    for
      _ <- onApi(edited)(api => api.teamAdmin.edit(Maintainers, EditTeam.named(Reviewers)))
      _ <- onApi(deleted)(api => api.teamAdmin.delete(Maintainers))
    yield
      assertEquals((methodOf(edited), pathOf(edited)), ("PATCH", "https://forge.example/api/v1/teams/42"))
      assertEquals((methodOf(deleted), pathOf(deleted)), ("DELETE", "https://forge.example/api/v1/teams/42"))

  test("the smallest team edit still carries a name, because EditTeamOption requires one"):
    val backend = RecordingBackend(responding(200, OrganizationTeamApiSuite.TeamBody))

    onApi(backend): api =>
      api.teamAdmin
        .edit(Maintainers, EditTeam.named(Reviewers))
        .map(_ => assertEquals(bodyOf(backend), """{"name":"reviewers"}"""))

  // --- search ---------------------------------------------------------------

  test("orgs.teams.search sends its optional filters ahead of the window"):
    val backend = RecordingBackend(responding(200, OrganizationTeamApiSuite.SearchBody))

    onApi(backend): api =>
      api.teamAdmin
        .search(Org, Some("review"), Some(true), window(3, 10))
        .map: _ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/orgs/forgejo/teams/search")
          assertEquals(
            queryOf(backend),
            List("q" -> "review", "include_desc" -> "true", "page" -> "3", "limit" -> "10"),
          )

  test("orgs.teams.search unwraps the ok/data envelope, which is the one envelope this group meets"):
    onApi(responding(200, OrganizationTeamApiSuite.SearchBody)): api =>
      api.teamAdmin
        .search(Org, None, None, PageParams.First)
        .map: page =>
          assertEquals(page.items.map(_.id.value), Vector(42L))
          assertEquals(page.items.map(_.name), Vector("maintainers"))

  test("a bad element of the search envelope reports its position inside data, not at the root"):
    onApi(responding(200, """{"ok":true,"data":[{"id":1,"name":"a"},{"id":2}]}""")): api =>
      api.teamAdmin.attempt.search(Org, None, None, PageParams.First).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.data[1].name")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a search that matched nothing is an empty page, not a failure"):
    onApi(responding(200, """{"ok":true,"data":[]}""")): api =>
      api.teamAdmin
        .search(Org, Some("nothing"), None, PageParams.First)
        .map: page =>
          assertEquals(page.items.size, 0)
          assertEquals(page.isLast, true)

  // --- activity feed --------------------------------------------------------

  test("orgs.teams.activities.list hangs off the team and renders its day as ISO-8601"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api.teamAdmin
        .activities(Maintainers, Some(LocalDate.of(2026, 2, 9)), PageParams.First)
        .map: _ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/teams/42/activities/feeds")
          assertEquals(queryOf(backend), List("date" -> "2026-02-09", "page" -> "1", "limit" -> "30"))

  // --- members --------------------------------------------------------------

  test("the three team-membership routes share one path and differ only in method"):
    val read    = RecordingBackend(responding(200, OrganizationTeamApiSuite.UserBody))
    val added   = RecordingBackend(responding(204, ""))
    val removed = RecordingBackend(responding(204, ""))

    for
      _ <- onApi(read)(api => api.teamAdmin.member(Maintainers, Account))
      _ <- onApi(added)(api => api.teamAdmin.addMember(Maintainers, Account))
      _ <- onApi(removed)(api => api.teamAdmin.removeMember(Maintainers, Account))
    yield
      assertEquals(pathOf(read), "https://forge.example/api/v1/teams/42/members/earl-warren")
      assertEquals(pathOf(added), pathOf(read))
      assertEquals(pathOf(removed), pathOf(read))
      assertEquals((methodOf(read), methodOf(added), methodOf(removed)), ("GET", "PUT", "DELETE"))

  test("the team-member read answers a user body, unlike the organisation's status-only membership probe"):
    onApi(responding(200, OrganizationTeamApiSuite.UserBody)): api =>
      api.teamAdmin
        .member(Maintainers, Account)
        .map: user =>
          assertEquals(user.login, "earl-warren")
          assertEquals(user.id, 73579L)

  test("a team-membership write carries no body, because the path is the whole statement"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.teamAdmin.addMember(Maintainers, Account).map(_ => assertEquals(bodyOf(backend), NoBody))

  // --- repositories ---------------------------------------------------------

  test("the three team-repository routes share one path and differ only in method"):
    val read    = RecordingBackend(responding(200, OrganizationTeamApiSuite.RepoBody))
    val added   = RecordingBackend(responding(204, ""))
    val removed = RecordingBackend(responding(204, ""))

    for
      _ <- onApi(read)(api => api.teamAdmin.repository(Maintainers, Org, Repo))
      _ <- onApi(added)(api => api.teamAdmin.addRepository(Maintainers, Org, Repo))
      _ <- onApi(removed)(api => api.teamAdmin.removeRepository(Maintainers, Org, Repo))
    yield
      assertEquals(pathOf(read), "https://forge.example/api/v1/teams/42/repos/forgejo/forgejo")
      assertEquals(pathOf(added), pathOf(read))
      assertEquals(pathOf(removed), pathOf(read))
      assertEquals((methodOf(read), methodOf(added), methodOf(removed)), ("GET", "PUT", "DELETE"))

  // --- retries --------------------------------------------------------------

  test("orgs.teams.create is never retried, because a team name is not unique within an organisation"):
    val backend = RecordingBackend(flakyThen(201, OrganizationTeamApiSuite.TeamBody))

    onApi(backend): api =>
      api.teamAdmin.attempt
        .create(Org, CreateTeam.named(Reviewers))
        .map(_ => assertEquals(attemptsOn(backend), 1, "the team create was repeated"))

  test("orgs.teams.edit is never retried, because EditTeamOption's required name makes every edit a rename"):
    val backend = RecordingBackend(flakyThen(200, OrganizationTeamApiSuite.TeamBody))

    onApi(backend): api =>
      api.teamAdmin.attempt
        .edit(Maintainers, EditTeam.named(Reviewers))
        .map(_ => assertEquals(attemptsOn(backend), 1, "the team edit was repeated"))

  test("orgs.teams.delete is retried, because a team id is a row id the instance never reuses"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.teamAdmin.delete(Maintainers).map(_ => assertEquals(attemptsOn(backend), 2, "the 503 was not retried"))

  test("both team-membership writes are retried, because each states an end condition about one named team"):
    val added   = RecordingBackend(flakyThen(204, ""))
    val removed = RecordingBackend(flakyThen(204, ""))

    for
      _ <- onApi(added)(api => api.teamAdmin.addMember(Maintainers, Account))
      _ <- onApi(removed)(api => api.teamAdmin.removeMember(Maintainers, Account))
    yield
      assertEquals(attemptsOn(added), 2, "the membership add was not retried")
      assertEquals(attemptsOn(removed), 2, "the membership removal was not retried")

  test("neither team-repository write is retried, because the subject is a renameable org/repo pair"):
    val added   = RecordingBackend(flakyThen(204, ""))
    val removed = RecordingBackend(flakyThen(204, ""))

    for
      _ <- onApi(added)(api => api.teamAdmin.attempt.addRepository(Maintainers, Org, Repo))
      _ <- onApi(removed)(api => api.teamAdmin.attempt.removeRepository(Maintainers, Org, Repo))
    yield
      assertEquals(attemptsOn(added), 1, "the repository grant was repeated")
      assertEquals(attemptsOn(removed), 1, "the repository revocation was repeated")

  // --- failures -------------------------------------------------------------

  test("a 401 on the team search reaches both rails identically"):
    onApi(responding(401, OrganizationStubs.UnauthorizedBody)): api =>
      for
        raised <- api.teamAdmin.search(Org, None, None, PageParams.First).failed
        typed  <- api.teamAdmin.attempt.search(Org, None, None, PageParams.First)
      yield
        assertEquals(operationOf(typed), OrganizationTeamApi.SearchOperation)
        assertRailsAgree(raised, typed)

  test("a 404 on the single-member read reaches both rails identically"):
    onApi(responding(404, OrganizationStubs.NotFoundBody)): api =>
      for
        raised <- api.teamAdmin.member(Maintainers, Account).failed
        typed  <- api.teamAdmin.attempt.member(Maintainers, Account)
      yield
        assertEquals(operationOf(typed), OrganizationTeamApi.MemberOperation)
        assertRailsAgree(raised, typed)

  test("a 403 on granting a repository reaches both rails identically"):
    onApi(responding(403, OrganizationStubs.UnauthorizedBody)): api =>
      for
        raised <- api.teamAdmin.addRepository(Maintainers, Org, Repo).failed
        typed  <- api.teamAdmin.attempt.addRepository(Maintainers, Org, Repo)
      yield
        assertEquals(operationOf(typed), OrganizationTeamApi.AddRepositoryOperation)
        assertRailsAgree(raised, typed)

  test("a 2xx team payload with no name becomes DecodingFailed, never an escaping codec exception"):
    onApi(responding(200, """{"id":42}""")): api =>
      api.teamAdmin.attempt.edit(Maintainers, EditTeam.named(Reviewers)).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.name")
        case other                                             => fail(s"expected a decoding failure, got $other")

/** The response bodies this suite stubs. All derived from the pinned spec; no team payload was ever captured. */
object OrganizationTeamApiSuite:

  /** A team as the spec defines one. */
  val TeamBody: String =
    """{"id": 42, "name": "maintainers", "permission": "write", "units": ["repo.code"]}"""

  /** The team search's `{"ok", "data"}` envelope, which is the one envelope this group meets. */
  val SearchBody: String = s"""{"ok": true, "data": [$TeamBody]}"""

  /** A user as the membership routes return one, reduced to the keys these tests assert on. */
  val UserBody: String = """{"id": 73579, "login": "earl-warren", "full_name": "Earl Warren"}"""

  /** A repository as the team-repository read returns one. */
  val RepoBody: String =
    """{"id": 70845, "name": "forgejo", "full_name": "forgejo/forgejo", "owner": {"id": 70422, "login": "forgejo"}}"""
