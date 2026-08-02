package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.users.UserVisibility

import munit.FunSuite

/** The organisation group's command types and the value types they carry.
  *
  * The subject is what a caller can and cannot express: which builder produces which value, which smart constructor
  * refuses which input, and — for the two `Option[Boolean]` fields — that "leave it alone" and "turn it off" really are
  * different values rather than the same one spelled twice.
  */
final class OrganizationCommandsSuite extends FunSuite:

  private val Handle: OrgName = orFail(OrgName.from("worxbend"))

  // --- CreateOrganization ---------------------------------------------------

  test("a create names the organisation and asserts nothing else"):
    val command = CreateOrganization.named(Handle)

    assertEquals(command.name.value, "worxbend")
    assertEquals(command.fullName, None)
    assertEquals(command.description, None)
    assertEquals(command.email, None)
    assertEquals(command.website, None)
    assertEquals(command.location, None)
    assertEquals(command.visibility, None)
    assertEquals(command.repoAdminChangeTeamAccess, None)

  test("a create's builders each set exactly one field"):
    val command = CreateOrganization
      .named(Handle)
      .displayedAs("Worxbend")
      .describedAs("tools we keep having to write twice")
      .contactableAt("hello@worxbend.example")
      .linkingTo("https://worxbend.example")
      .locatedAt("Kraków")
      .visibleAs(UserVisibility.Limited)
      .repositoryAdminsChangeTeamAccess(false)

    assertEquals(command.fullName, Some("Worxbend"))
    assertEquals(command.description, Some("tools we keep having to write twice"))
    assertEquals(command.email, Some("hello@worxbend.example"))
    assertEquals(command.website, Some("https://worxbend.example"))
    assertEquals(command.location, Some("Kraków"))
    assertEquals(command.visibility, Some(UserVisibility.Limited))
    assertEquals(command.repoAdminChangeTeamAccess, Some(false))

  test("a create's repo-admin flag distinguishes 'leave the default' from 'false'"):
    assertEquals(CreateOrganization.named(Handle).repoAdminChangeTeamAccess, None)
    assertEquals(
      CreateOrganization.named(Handle).repositoryAdminsChangeTeamAccess(false).repoAdminChangeTeamAccess,
      Some(false),
    )

  // --- EditOrganization -----------------------------------------------------

  test("the empty edit changes nothing"):
    assertEquals(EditOrganization.Empty.fullName, None)
    assertEquals(EditOrganization.Empty.description, None)
    assertEquals(EditOrganization.Empty.email, None)
    assertEquals(EditOrganization.Empty.website, None)
    assertEquals(EditOrganization.Empty.location, None)
    assertEquals(EditOrganization.Empty.visibility, None)
    assertEquals(EditOrganization.Empty.repoAdminChangeTeamAccess, None)

  test("an edit's builders each set exactly one field, leaving the rest absent"):
    val command = EditOrganization.Empty.describedAs("we forge").visibleAs(UserVisibility.Private)

    assertEquals(command.description, Some("we forge"))
    assertEquals(command.visibility, Some(UserVisibility.Private))
    assertEquals(command.fullName, None)
    assertEquals(command.email, None)
    assertEquals(command.website, None)
    assertEquals(command.location, None)

  test("an edit can turn the repo-admin flag off, which the create's absence cannot express"):
    assertEquals(EditOrganization.Empty.repositoryAdminsChangeTeamAccess(false).repoAdminChangeTeamAccess, Some(false))
    assertEquals(EditOrganization.Empty.repositoryAdminsChangeTeamAccess(true).repoAdminChangeTeamAccess, Some(true))

  // --- TeamName -------------------------------------------------------------

  test("a team name is trimmed"):
    assertEquals(orFail(TeamName.from("  reviewers  ")).value, "reviewers")

  test("a blank team name is refused on the teamName field"):
    assertEquals(fieldOf(TeamName.from("   ")), "teamName")

  test("a team name carrying a control character is refused"):
    assertEquals(fieldOf(TeamName.from("review\ters")), "teamName")

  test("a team name may contain a slash, because it never becomes a path segment"):
    assertEquals(orFail(TeamName.from("frontend/reviewers")).value, "frontend/reviewers")

  // --- CreateTeam / EditTeam ------------------------------------------------

  test("a team create names the team, defaults both flags off and says nothing about units"):
    val command = CreateTeam.named(orFail(TeamName.from("reviewers")))

    assertEquals(command.name.value, "reviewers")
    assertEquals(command.description, None)
    assertEquals(command.permission, None)
    assertEquals(command.units, Vector.empty[String])
    assertEquals(command.unitPermissions, Map.empty[String, TeamPermission])
    assertEquals(command.canCreateOrgRepo, false)
    assertEquals(command.includesAllRepositories, false)

  test("a team create's unit builders are two different statements, not one"):
    val command = CreateTeam
      .named(orFail(TeamName.from("reviewers")))
      .permitted(TeamPermission.Read)
      .reaching("repo.code", "repo.pulls")
      .reachingAt("repo.issues", TeamPermission.Write)

    assertEquals(command.permission, Some(TeamPermission.Read))
    assertEquals(command.units, Vector("repo.code", "repo.pulls"))
    assertEquals(command.unitPermissions, Map("repo.issues" -> TeamPermission.Write))

  test("a team create's flags are set by their builders"):
    val command = CreateTeam
      .named(orFail(TeamName.from("owners-ish")))
      .creatingRepositories
      .includingAllRepositories

    assertEquals(command.canCreateOrgRepo, true)
    assertEquals(command.includesAllRepositories, true)

  test("a team edit always carries a name, and its flags start absent"):
    val command = EditTeam.named(orFail(TeamName.from("reviewers")))

    assertEquals(command.name.value, "reviewers")
    assertEquals(command.canCreateOrgRepo, None)
    assertEquals(command.includesAllRepositories, None)

  test("a team edit can turn either flag off"):
    val command = EditTeam
      .named(orFail(TeamName.from("reviewers")))
      .creatingRepositories(false)
      .includingAllRepositories(false)

    assertEquals(command.canCreateOrgRepo, Some(false))
    assertEquals(command.includesAllRepositories, Some(false))

  test("a team edit's later reachingAt replaces the level of the unit it names and leaves the others"):
    val command = EditTeam
      .named(orFail(TeamName.from("reviewers")))
      .reachingAt("repo.code", TeamPermission.Read)
      .reachingAt("repo.issues", TeamPermission.Write)
      .reachingAt("repo.code", TeamPermission.Admin)

    assertEquals(command.unitPermissions, Map("repo.code" -> TeamPermission.Admin, "repo.issues" -> TeamPermission.Write))

  // --- QuotaSubject ---------------------------------------------------------

  test("a quota subject is trimmed and otherwise untouched"):
    assertEquals(orFail(QuotaSubject.from("  size:repos:public ")).value, "size:repos:public")

  test("a quota subject this library has never heard of is still accepted, because the spec enumerates none"):
    assertEquals(
      orFail(QuotaSubject.from("size:something:forgejo:adds:later")).value,
      "size:something:forgejo:adds:later",
    )

  test("a blank quota subject is refused on the quotaSubject field"):
    assertEquals(fieldOf(QuotaSubject.from("  ")), "quotaSubject")

  test("a quota subject carrying a control character is refused, because it becomes a query parameter"):
    assertEquals(fieldOf(QuotaSubject.from("size:\nall")), "quotaSubject")

  // --- OrganizationLabelSort ------------------------------------------------

  test("every label ordering round-trips through its wire spelling"):
    OrganizationLabelSort.values.foreach: sort =>
      assertEquals(OrganizationLabelSort.parse(sort.wireValue), Some(sort))

  test("label orderings are the three the spec enumerates, spelled as the spec spells them"):
    assertEquals(OrganizationLabelSort.MostIssues.wireValue, "mostissues")
    assertEquals(OrganizationLabelSort.LeastIssues.wireValue, "leastissues")
    assertEquals(OrganizationLabelSort.ReverseAlphabetically.wireValue, "reversealphabetically")

  test("a label ordering outside the enumerated set is None rather than a failure"):
    assertEquals(OrganizationLabelSort.parse("alphabetically"), None)

  test("label ordering parsing trims and ignores case"):
    assertEquals(OrganizationLabelSort.parse("  MostIssues "), Some(OrganizationLabelSort.MostIssues))

  // --- BlockId --------------------------------------------------------------

  test("a block id is a positive row id"):
    assertEquals(orFail(BlockId.from(7L)).value, 7L)

  test("a non-positive block id is refused on the blockId field"):
    assertEquals(fieldOf(BlockId.from(0L)), "blockId")
    assertEquals(fieldOf(BlockId.from(-1L)), "blockId")

  // --- helpers --------------------------------------------------------------

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

  private def fieldOf[A](result: Either[ValidationError, A]): String =
    result match
      case Left(error)  => error.field
      case Right(value) => fail(s"expected a rejection, got $value")
