package com.worxbend.codeberg4s.organizations.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.organizations.Team
import com.worxbend.codeberg4s.organizations.TeamPermission

import munit.FunSuite

/** [[TeamDto]] against hand-written bodies, because no golden capture of a team exists.
  *
  * `golden/MANIFEST.md` records `GET /orgs/{org}/teams` answering `401 token is required` anonymously, so the bodies
  * below are assembled from the pinned spec's `Team` definition and its own `units` / `units_map` examples. They are
  * shape-only evidence and never evidence of optionality — which is exactly why the DTO treats every field as
  * absent-able and the tests below check that it does.
  */
final class TeamDtoSuite extends FunSuite:

  private def decodeTeam(body: String): TeamDto =
    Json.decode[TeamDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the body did not decode: ${failure.path.render} ${failure.message}")

  private def domainTeam(body: String): Team =
    decodeTeam(body).toDomain match
      case Right(team)   => team
      case Left(failure) => fail(s"the body did not convert: ${failure.path.render} ${failure.message}")

  test("a full team body decodes field for field"):
    val dto = decodeTeam(TeamDtoSuite.FullTeam)

    assertEquals(dto.id, Some(42L))
    assertEquals(dto.name, Some("maintainers"))
    assertEquals(dto.description, Some("people who may merge"))
    assertEquals(dto.permission, Some("write"))
    assertEquals(dto.units, Vector("repo.code", "repo.issues", "repo.pulls"))
    assertEquals(dto.canCreateOrgRepo, Some(true))
    assertEquals(dto.includesAllRepositories, Some(false))
    assertEquals(dto.organization.flatMap(_.name), Some("forgejo"))

  test("a full team body converts to the domain"):
    val team = domainTeam(TeamDtoSuite.FullTeam)

    assertEquals(team.id.value, 42L)
    assertEquals(team.name, "maintainers")
    assertEquals(team.permission, Some(TeamPermission.Write))
    assertEquals(team.canCreateOrgRepo, true)
    assertEquals(team.includesAllRepositories, false)
    assertEquals(team.organization.map(_.name.value), Some("forgejo"))

  test("units_map becomes per-unit levels, which is the field a caller must read before deciding on a push"):
    val team = domainTeam(TeamDtoSuite.FullTeam)

    assertEquals(
      team.unitPermissions,
      Map(
        "repo.code"   -> TeamPermission.Read,
        "repo.issues" -> TeamPermission.Write,
        "repo.pulls"  -> TeamPermission.Owner,
      ),
    )

  test("the overall permission and the per-unit levels are different answers to different questions"):
    val team = domainTeam(TeamDtoSuite.FullTeam)

    assertEquals(team.permission, Some(TeamPermission.Write))
    assertEquals(team.unitPermissions.get("repo.code"), Some(TeamPermission.Read))

  test("a units_map entry whose level is unrecognised is dropped, not a failure"):
    val team = domainTeam("""{"id":1,"name":"t","units_map":{"repo.code":"read","repo.moon":"teleport"}}""")

    assertEquals(team.unitPermissions, Map("repo.code" -> TeamPermission.Read))

  test("a units_map entry whose value is not a string is dropped, matching the leniency elsewhere"):
    val dto = decodeTeam("""{"id":1,"name":"t","units_map":{"repo.code":"read","repo.wiki":7}}""")

    assertEquals(dto.unitsMap, Map("repo.code" -> "read"))

  test("a null units_map and a null units array are empty, never a crash"):
    val team = domainTeam("""{"id":1,"name":"t","units":null,"units_map":null}""")

    assertEquals(team.units, Vector.empty[String])
    assertEquals(team.unitPermissions, Map.empty[String, TeamPermission])

  test("an absent field and an explicit null produce the same DTO"):
    val absent = Json.decode[TeamDto]("""{"id":1,"name":"t"}""")

    val explicitNull =
      Json.decode[TeamDto](
        """{"id":1,"name":"t","description":null,"organization":null,"permission":null,"units":null,
          |"units_map":null,"can_create_org_repo":null,"includes_all_repositories":null}""".stripMargin
      )

    assertEquals(absent, explicitNull)

  test("a team reduced to its two required fields still converts"):
    val team = domainTeam("""{"id":7,"name":"owners"}""")

    assertEquals(team.id.value, 7L)
    assertEquals(team.permission, None)
    assertEquals(team.organization, None)
    assertEquals(team.canCreateOrgRepo, false)
    assertEquals(team.includesAllRepositories, false)

  test("a team without an id cannot be converted"):
    Json.decode[TeamDto]("""{"name":"owners"}""").flatMap(_.toDomain) match
      case Left(failure) => assertEquals(failure.path.render, "$.id")
      case Right(team)   => fail(s"expected a failure, converted $team")

  test("a team whose id is zero cannot be converted, because zero is not a row id"):
    Json.decode[TeamDto]("""{"id":0,"name":"owners"}""").flatMap(_.toDomain) match
      case Left(failure) =>
        assertEquals(failure.path.render, "$.id")
        assertEquals(failure.message, "must be at least 1")
      case Right(team)   => fail(s"expected a failure, converted $team")

  test("a team without a name cannot be converted"):
    Json.decode[TeamDto]("""{"id":7}""").flatMap(_.toDomain) match
      case Left(failure) => assertEquals(failure.path.render, "$.name")
      case Right(team)   => fail(s"expected a failure, converted $team")

  test("an unusable embedded organisation reports its failure at the nested path"):
    Json.decode[TeamDto]("""{"id":7,"name":"owners","organization":{"name":"forgejo"}}""").flatMap(_.toDomain) match
      case Left(failure) => assertEquals(failure.path.render, "$.organization.id")
      case Right(team)   => fail(s"expected a failure, converted $team")

  test("an unrecognised permission does not cost the rest of the team"):
    val team = domainTeam("""{"id":7,"name":"owners","permission":"superuser"}""")

    assertEquals(team.permission, None)
    assertEquals(team.name, "owners")

  test("a bad element of a team listing reports its own position"):
    Json.decode[Vector[TeamDto]]("""[{"id":1,"name":"a"},{"id":2}]""") match
      case Right(dtos)   =>
        TeamDto.toDomainAll(JsonPath.Root, dtos) match
          case Left(failure) => assertEquals(failure.path.render, "$[1].name")
          case Right(teams)  => fail(s"expected a failure, converted $teams")
      case Left(failure) => fail(s"the array did not decode: ${failure.message}")

  test("a truncated team body is a DecodeFailure, not an exception"):
    assert(Json.decode[TeamDto]("""{"id":1,"name":"own""").isLeft)
    assert(Json.decode[TeamDto]("<html>not json</html>").isLeft)

/** The bodies this suite decodes, assembled from the pinned spec's `Team` definition and its examples. */
object TeamDtoSuite:

  /** Every one of the spec's nine `Team` properties, with `units` and `units_map` deliberately disagreeing so that the
    * two are not accidentally read from one another.
    */
  private val FullTeam: String =
    """{
      |  "id": 42,
      |  "name": "maintainers",
      |  "description": "people who may merge",
      |  "permission": "write",
      |  "can_create_org_repo": true,
      |  "includes_all_repositories": false,
      |  "units": ["repo.code", "repo.issues", "repo.pulls"],
      |  "units_map": {"repo.code": "read", "repo.issues": "write", "repo.pulls": "owner"},
      |  "organization": {
      |    "id": 70422,
      |    "name": "forgejo",
      |    "full_name": "Forgejo",
      |    "visibility": "public"
      |  }
      |}""".stripMargin
