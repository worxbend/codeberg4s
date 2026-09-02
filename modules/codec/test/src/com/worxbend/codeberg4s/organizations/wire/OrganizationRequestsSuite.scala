package com.worxbend.codeberg4s.organizations.wire

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.organizations.CreateOrganization
import com.worxbend.codeberg4s.organizations.CreateTeam
import com.worxbend.codeberg4s.organizations.EditOrganization
import com.worxbend.codeberg4s.organizations.EditTeam
import com.worxbend.codeberg4s.organizations.OrgName
import com.worxbend.codeberg4s.organizations.TeamName
import com.worxbend.codeberg4s.organizations.TeamPermission
import com.worxbend.codeberg4s.organizations.{OrganizationLabelQuery, OrganizationLabelSort}
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.quota.QuotaSubject
import com.worxbend.codeberg4s.users.UserVisibility
import com.worxbend.codeberg4s.users.account.AvatarImage

import munit.FunSuite

import java.time.LocalDate

/** The request bodies and query strings this group renders.
  *
  * Bodies are asserted as exact strings rather than as re-parsed objects, for the reason `AdminRequestsSuite` gives:
  * the point of a renderer is the bytes. A key that moved, an optional field that started being emitted, or an input
  * order that stopped being stable is a change in what the instance receives, and a round trip through a parser would
  * hide all three.
  *
  * '''Every expected body was derived from `spec/swagger.v1.json`.''' No golden capture of any request in this group
  * exists — each one needs a token and `modules/codec/test/resources/golden` was harvested anonymously.
  */
final class OrganizationRequestsSuite extends FunSuite:

  private val Handle: OrgName = orFail(OrgName.from("worxbend"))

  private val Reviewers: TeamName = orFail(TeamName.from("reviewers"))

  // --- CreateOrgOption ------------------------------------------------------

  test("a minimal organisation create sends the one required property and nothing else"):
    assertEquals(
      OrganizationOptionDto.renderCreate(CreateOrganization.named(Handle)),
      """{"username":"worxbend"}""",
    )

  test("an organisation create emits every property the caller set, in the order the renderer fixes"):
    val command = CreateOrganization
      .named(Handle)
      .displayedAs("Worxbend")
      .describedAs("we forge")
      .contactableAt("hello@worxbend.example")
      .linkingTo("https://worxbend.example")
      .locatedAt("Krakow")
      .visibleAs(UserVisibility.Limited)
      .repositoryAdminsChangeTeamAccess(true)

    assertEquals(
      OrganizationOptionDto.renderCreate(command),
      """{"username":"worxbend","full_name":"Worxbend","description":"we forge",""" +
        """"email":"hello@worxbend.example","website":"https://worxbend.example","location":"Krakow",""" +
        """"visibility":"limited","repo_admin_change_team_access":true}""",
    )

  test("an organisation create does not assert repo_admin_change_team_access when the caller said nothing"):
    assert(
      !OrganizationOptionDto.renderCreate(CreateOrganization.named(Handle)).contains("repo_admin_change_team_access"),
      "the create forced a flag the caller left to the instance",
    )

  test("an organisation create emits a false flag the caller set, which is not the same as saying nothing"):
    assertEquals(
      OrganizationOptionDto.renderCreate(CreateOrganization.named(Handle).repositoryAdminsChangeTeamAccess(false)),
      """{"username":"worxbend","repo_admin_change_team_access":false}""",
    )

  // --- EditOrgOption --------------------------------------------------------

  test("the empty organisation edit renders as an object that changes nothing"):
    assertEquals(OrganizationOptionDto.renderEdit(EditOrganization.Empty), "{}")

  test("an organisation edit emits only what the caller set, and never a name"):
    val rendered = OrganizationOptionDto.renderEdit(EditOrganization.Empty.describedAs("we forge"))

    assertEquals(rendered, """{"description":"we forge"}""")
    assert(!rendered.contains("username"), "the edit tried to rename, which is a different endpoint")

  test("an organisation edit renders visibility as Forgejo's own lowercase spelling"):
    assertEquals(
      OrganizationOptionDto.renderEdit(EditOrganization.Empty.visibleAs(UserVisibility.Private)),
      """{"visibility":"private"}""",
    )

  // --- RenameOrgOption and UpdateUserAvatarOption ---------------------------

  test("a rename sends the one key the model declares"):
    assertEquals(
      OrganizationOptionDto.renderRename(orFail(OrgName.from("worxbend-forge"))),
      """{"new_name":"worxbend-forge"}""",
    )

  test("an avatar upload sends the base64 text under image, never the raw bytes"):
    assertEquals(
      OrganizationOptionDto.renderAvatar(AvatarImage.ofBytes("forge".getBytes("UTF-8"))),
      """{"image":"Zm9yZ2U="}""",
    )

  // --- CreateTeamOption -----------------------------------------------------

  test("a minimal team create emits the name and both flags, because a create has no defaults to leave"):
    assertEquals(
      TeamOptionDto.renderCreate(CreateTeam.named(Reviewers)),
      """{"name":"reviewers","can_create_org_repo":false,"includes_all_repositories":false}""",
    )

  test("a team create emits units and units_map only when there is something to say"):
    val rendered = TeamOptionDto.renderCreate(CreateTeam.named(Reviewers))

    assert(!rendered.contains("units"), s"an empty unit list was sent as a key: $rendered")

  test("a team create renders units in the caller's order and units_map in key order"):
    val command = CreateTeam
      .named(Reviewers)
      .describedAs("may approve, may not push")
      .permitted(TeamPermission.Read)
      .reaching("repo.pulls", "repo.code")
      .reachingAt("repo.issues", TeamPermission.Write)
      .reachingAt("repo.code", TeamPermission.Read)

    assertEquals(
      TeamOptionDto.renderCreate(command),
      """{"name":"reviewers","can_create_org_repo":false,"includes_all_repositories":false,""" +
        """"description":"may approve, may not push","permission":"read",""" +
        """"units":["repo.pulls","repo.code"],"units_map":{"repo.code":"read","repo.issues":"write"}}""",
    )

  test("a team create's flags travel as the caller set them"):
    assertEquals(
      TeamOptionDto.renderCreate(CreateTeam.named(Reviewers).creatingRepositories.includingAllRepositories),
      """{"name":"reviewers","can_create_org_repo":true,"includes_all_repositories":true}""",
    )

  // --- EditTeamOption -------------------------------------------------------

  test("the smallest team edit still carries the name, because the model requires it"):
    assertEquals(TeamOptionDto.renderEdit(EditTeam.named(Reviewers)), """{"name":"reviewers"}""")

  test("a team edit emits a flag only when the caller set it, so false can mean 'turn this off'"):
    assertEquals(
      TeamOptionDto.renderEdit(EditTeam.named(Reviewers).includingAllRepositories(false)),
      """{"name":"reviewers","includes_all_repositories":false}""",
    )

  test("a team edit renders its whole vocabulary in the order the renderer fixes"):
    val command = EditTeam
      .named(Reviewers)
      .describedAs("now they may push")
      .permitted(TeamPermission.Write)
      .reaching("repo.code")
      .reachingAt("repo.wiki", TeamPermission.Admin)
      .creatingRepositories(true)
      .includingAllRepositories(true)

    assertEquals(
      TeamOptionDto.renderEdit(command),
      """{"name":"reviewers","description":"now they may push","permission":"write",""" +
        """"units":["repo.code"],"units_map":{"repo.wiki":"admin"},""" +
        """"can_create_org_repo":true,"includes_all_repositories":true}""",
    )

  // --- query strings --------------------------------------------------------

  test("a label listing without an ordering sends no sort parameter at all"):
    assertEquals(
      OrganizationQueries.labels(OrganizationLabelQuery.Empty, window(1, 30)),
      List("page" -> "1", "limit" -> "30")
    )

  test("a label listing sends the ordering ahead of the window, in the spec's own spelling"):
    assertEquals(
      OrganizationQueries.labels(OrganizationLabelQuery.of(OrganizationLabelSort.LeastIssues), window(1, 30)),
      List("sort" -> "leastissues", "page" -> "1", "limit" -> "30"),
    )

  test("an activity feed without a day sends only the window"):
    assertEquals(OrganizationQueries.activities(None, window(1, 30)), List("page" -> "1", "limit" -> "30"))

  test("an activity feed renders its day as ISO-8601, which is what format: date means"):
    assertEquals(
      OrganizationQueries.activities(Some(LocalDate.of(2026, 2, 9)), window(1, 30)),
      List("date" -> "2026-02-09", "page" -> "1", "limit" -> "30"),
    )

  test("a team search with nothing to say sends only the window"):
    assertEquals(OrganizationQueries.teamSearch(None, None, window(1, 30)), List("page" -> "1", "limit" -> "30"))

  test("a team search sends q and include_desc only when the caller set them"):
    assertEquals(
      OrganizationQueries.teamSearch(Some("review"), Some(true), window(3, 10)),
      List("q" -> "review", "include_desc" -> "true", "page" -> "3", "limit" -> "10"),
    )

  test("a team search can ask for include_desc=false, which is not the same as omitting it"):
    assertEquals(
      OrganizationQueries.teamSearch(None, Some(false), window(1, 30)),
      List("include_desc" -> "false", "page" -> "1", "limit" -> "30"),
    )

  test("a quota check always sends its subject, because the spec marks it required"):
    assertEquals(
      OrganizationQueries.quotaCheck(orFail(QuotaSubject.from("size:repos:public"))),
      List("subject" -> "size:repos:public"),
    )

  // --- helpers --------------------------------------------------------------

  private def window(page: Int, size: Int): PageParams =
    PageParams(orFail(PageNumber.from(page)), orFail(PageSize.from(size)))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
