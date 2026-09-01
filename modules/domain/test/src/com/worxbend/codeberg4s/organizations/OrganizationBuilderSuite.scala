package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.users.UserVisibility

import munit.FunSuite

/** The organisation group's builders applied to values that already carry every field.
  *
  * `OrganizationCommandsSuite` establishes what each builder produces from a fresh command. This suite asks the harder
  * question: applied to a command that already says everything, does a builder change only the one property it names? A
  * `copy` that dropped a sibling passes the first test and fails this one.
  */
final class OrganizationBuilderSuite extends FunSuite:

  private val Reviewers: TeamName = orFail(TeamName.from("reviewers"))

  private val Owners: TeamName = orFail(TeamName.from("owners"))

  // --- EditOrganization -----------------------------------------------------

  test("every organisation-edit builder sets its own field and leaves every sibling alone"):
    val edit = populatedOrganizationEdit

    assertEquals(edit.displayedAs("replaced"), edit.copy(fullName = Some("replaced")))
    assertEquals(edit.describedAs("replaced"), edit.copy(description = Some("replaced")))
    assertEquals(edit.contactableAt("replaced@worxbend.example"), edit.copy(email = Some("replaced@worxbend.example")))
    assertEquals(edit.linkingTo("https://replaced.example"), edit.copy(website = Some("https://replaced.example")))
    assertEquals(edit.locatedAt("Gdańsk"), edit.copy(location = Some("Gdańsk")))
    assertEquals(edit.visibleAs(UserVisibility.Private), edit.copy(visibility = Some(UserVisibility.Private)))
    assertEquals(
      edit.repositoryAdminsChangeTeamAccess(false),
      edit.copy(repoAdminChangeTeamAccess = Some(false)),
    )

  test("an organisation edit that changes only the website says nothing about the name, which is a separate endpoint"):
    val edit = EditOrganization.Empty.linkingTo("https://worxbend.example")

    assertEquals(edit.website, Some("https://worxbend.example"))
    assertEquals(edit.fullName, None)
    assertEquals(edit.description, None)

  // --- CreateTeam -----------------------------------------------------------

  test("every team-create builder sets its own field and leaves every sibling alone"):
    val command = populatedTeamCreate

    assertEquals(command.describedAs("replaced"), command.copy(description = Some("replaced")))
    assertEquals(command.permitted(TeamPermission.Admin), command.copy(permission = Some(TeamPermission.Admin)))
    assertEquals(command.reaching("repo.releases"), command.copy(units = Vector("repo.releases")))
    assertEquals(
      command.reachingAt("repo.code", TeamPermission.Admin),
      command.copy(unitPermissions = command.unitPermissions.updated("repo.code", TeamPermission.Admin)),
    )
    assertEquals(command.creatingRepositories, command.copy(canCreateOrgRepo = true))
    assertEquals(command.includingAllRepositories, command.copy(includesAllRepositories = true))

  test("naming units and levelling one unit are two statements, and neither erases the other"):
    val command = populatedTeamCreate.reaching("repo.releases")

    assertEquals(command.units, Vector("repo.releases"))
    assertEquals(command.unitPermissions, populatedTeamCreate.unitPermissions)

  // --- EditTeam -------------------------------------------------------------

  test("every team-edit builder sets its own field and leaves every sibling alone, the name included"):
    val edit = populatedTeamEdit

    assertEquals(edit.describedAs("replaced"), edit.copy(description = Some("replaced")))
    assertEquals(edit.permitted(TeamPermission.Admin), edit.copy(permission = Some(TeamPermission.Admin)))
    assertEquals(edit.reaching("repo.releases"), edit.copy(units = Vector("repo.releases")))
    assertEquals(
      edit.reachingAt("repo.code", TeamPermission.Admin),
      edit.copy(unitPermissions = edit.unitPermissions.updated("repo.code", TeamPermission.Admin)),
    )
    assertEquals(edit.creatingRepositories(false), edit.copy(canCreateOrgRepo = Some(false)))
    assertEquals(edit.includingAllRepositories(false), edit.copy(includesAllRepositories = Some(false)))

  test("no team-edit builder can change the name, because every edit is a rename and the caller states it once"):
    assertEquals(populatedTeamEdit.describedAs("replaced").name, Reviewers)
    assertEquals(EditTeam.named(Owners).permitted(TeamPermission.Read).name, Owners)

  test("reaching with no names empties the list, which sends no units key and leaves the team's units alone"):
    assertEquals(populatedTeamEdit.reaching().units, Vector.empty[String])

  // --- helpers --------------------------------------------------------------

  private def populatedOrganizationEdit: EditOrganization =
    EditOrganization(
      fullName                  = Some("Worxbend"),
      description               = Some("tools we keep having to write twice"),
      email                     = Some("hello@worxbend.example"),
      website                   = Some("https://worxbend.example"),
      location                  = Some("Kraków"),
      visibility                = Some(UserVisibility.Limited),
      repoAdminChangeTeamAccess = Some(true),
    )

  private def populatedTeamCreate: CreateTeam =
    CreateTeam(
      name                    = Reviewers,
      description             = Some("may approve, may not push"),
      permission              = Some(TeamPermission.Read),
      units                   = Vector("repo.code", "repo.pulls"),
      unitPermissions         = Map("repo.issues" -> TeamPermission.Write),
      canCreateOrgRepo        = false,
      includesAllRepositories = false,
    )

  private def populatedTeamEdit: EditTeam =
    EditTeam(
      name                    = Reviewers,
      description             = Some("may approve, may not push"),
      permission              = Some(TeamPermission.Read),
      units                   = Vector("repo.code", "repo.pulls"),
      unitPermissions         = Map("repo.issues" -> TeamPermission.Write),
      canCreateOrgRepo        = Some(true),
      includesAllRepositories = Some(true),
    )

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
