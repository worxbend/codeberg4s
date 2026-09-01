package com.worxbend.codeberg4s.organizations.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{GoldenFixtures, Json, WireModel}
import com.worxbend.codeberg4s.organizations.Organization
import com.worxbend.codeberg4s.users.UserVisibility

import munit.FunSuite

import java.time.Instant

/** Decodes every golden fixture whose payload is an `Organization`, and asserts on named fields rather than on a round
  * trip.
  *
  * A round-trip test would pass even if a field were dropped, because a dropped field is symmetric. Naming the values
  * is what catches the drop.
  */
final class OrganizationDtoSuite extends FunSuite with GoldenFixtures:

  private def decodeOrganization(fixture: String): OrganizationDto =
    Json.decode[OrganizationDto](golden(fixture)) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"$fixture did not decode: ${failure.path.render} ${failure.message}")

  private def domainOrganization(fixture: String): Organization =
    decodeOrganization(fixture).toDomain match
      case Right(organization) => organization
      case Left(failure)       => fail(s"$fixture did not convert: ${failure.path.render} ${failure.message}")

  private def decodeList(fixture: String): Vector[OrganizationDto] =
    Json.decode[Vector[OrganizationDto]](golden(fixture)) match
      case Right(dtos)   => dtos
      case Left(failure) => fail(s"$fixture did not decode: ${failure.path.render} ${failure.message}")

  test("golden /orgs/forgejo decodes field for field"):
    val dto = decodeOrganization("organization/org-single.json")

    assertEquals(dto.id, Some(70422L))
    assertEquals(dto.name, Some("forgejo"))
    assertEquals(dto.fullName, Some("Forgejo"))
    assertEquals(dto.avatarUrl, Some("https://codeberg.org/avatars/dae8ab126a96f6fbd6942cf08ab92382"))
    assertEquals(dto.description, Some("Beyond coding. We forge."))
    assertEquals(dto.website, Some("https://forgejo.org"))
    assertEquals(dto.visibility, Some("public"))
    assertEquals(dto.repoAdminChangeTeamAccess, Some(true))
    assertEquals(dto.created, Some("2022-11-06T07:18:11+01:00"))
    assertEquals(dto.username, Some("forgejo"))

  test("the fields Forgejo sends as an empty string are absent, not empty"):
    val dto = decodeOrganization("organization/org-single.json")

    assertEquals(dto.email, None)
    assertEquals(dto.location, None)

  test("golden /orgs/forgejo converts to the domain"):
    val organization = domainOrganization("organization/org-single.json")

    assertEquals(organization.id, 70422L)
    assertEquals(organization.name.value, "forgejo")
    assertEquals(organization.fullName, Some("Forgejo"))
    assertEquals(organization.description, Some("Beyond coding. We forge."))
    assertEquals(organization.visibility, Some(UserVisibility.Public))
    assertEquals(organization.repoAdminChangeTeamAccess, true)
    assertEquals(organization.createdAt, Some(Instant.parse("2022-11-06T06:18:11Z")))

  test("the deprecated username duplicates name on the captured organisation and is dropped in conversion"):
    val dto = decodeOrganization("organization/org-single.json")

    assertEquals(dto.username, dto.name)

  test("golden /orgs decodes as a bare array of three organisations"):
    val dtos = decodeList("organization/org-list.json")

    assertEquals(dtos.size, 3)
    assertEquals(dtos.map(_.name), Vector(Some("_CYBER_STONES_"), Some("-_"), Some("-_-")))
    assertEquals(dtos.map(_.id), Vector(Some(25273L), Some(90224L), Some(37150L)))

  test("every organisation of golden /orgs converts, punctuation-only names included"):
    WireModel.all(JsonPath.Root, decodeList("organization/org-list.json")) match
      case Right(organizations) =>
        assertEquals(organizations.map(_.name.value), Vector("_CYBER_STONES_", "-_", "-_-"))
        assertEquals(organizations.map(_.fullName), Vector(None, None, None))
        assertEquals(organizations.head.createdAt, Some(Instant.parse("2021-03-25T12:44:54Z")))
      case Left(failure)        => fail(s"org-list did not convert: ${failure.path.render} ${failure.message}")

  test("an absent field and an explicit null produce the same DTO"):
    val absent = Json.decode[OrganizationDto]("""{"id":1,"name":"a"}""")

    val explicitNull =
      Json.decode[OrganizationDto](
        """{"id":1,"name":"a","full_name":null,"email":null,"created":null,"visibility":null,
          |"repo_admin_change_team_access":null,"website":null,"location":null,"description":null}""".stripMargin
      )

    assertEquals(absent, explicitNull)

  test("an organisation reduced to its two required fields still converts"):
    Json.decode[OrganizationDto]("""{"id":9,"name":"tiny"}""").flatMap(_.toDomain) match
      case Right(organization) =>
        assertEquals(organization.id, 9L)
        assertEquals(organization.name.value, "tiny")
        assertEquals(organization.repoAdminChangeTeamAccess, false)
        assertEquals(organization.visibility, None)
        assertEquals(organization.createdAt, None)
      case Left(failure)       => fail(s"a reduced organisation must still convert: ${failure.message}")

  test("an organisation without an id cannot be converted"):
    Json.decode[OrganizationDto]("""{"name":"forgejo"}""").flatMap(_.toDomain) match
      case Left(failure)       => assertEquals(failure.path.render, "$.id")
      case Right(organization) => fail(s"expected a failure, converted $organization")

  test("an organisation without a name cannot be converted"):
    Json.decode[OrganizationDto]("""{"id":9}""").flatMap(_.toDomain) match
      case Left(failure)       => assertEquals(failure.path.render, "$.name")
      case Right(organization) => fail(s"expected a failure, converted $organization")

  test("a name that could forge a path is rejected at the same place as a missing one"):
    Json.decode[OrganizationDto]("""{"id":9,"name":"forgejo/teams"}""").flatMap(_.toDomain) match
      case Left(failure)       =>
        assertEquals(failure.path.render, "$.name")
        assertEquals(failure.message, "must not contain a slash")
      case Right(organization) => fail(s"expected a failure, converted $organization")

  test("an unrecognised visibility does not cost the rest of the organisation"):
    Json.decode[OrganizationDto]("""{"id":1,"name":"a","visibility":"quantum"}""").flatMap(_.toDomain) match
      case Right(organization) =>
        assertEquals(organization.visibility, None)
        assertEquals(organization.name.value, "a")
      case Left(failure)       => fail(s"a new visibility must not break decoding: ${failure.message}")

  test("a bad element of a listing reports its own position, not the document root"):
    Json.decode[Vector[OrganizationDto]]("""[{"id":1,"name":"a"},{"name":"b"}]""") match
      case Right(dtos)   =>
        WireModel.all(JsonPath.Root, dtos) match
          case Left(failure)        => assertEquals(failure.path.render, "$[1].id")
          case Right(organizations) => fail(s"expected a failure, converted $organizations")
      case Left(failure) => fail(s"the array did not decode: ${failure.message}")

  test("a nested organisation reports its failure at the nested path"):
    Json.decode[OrganizationDto]("""{}""").flatMap(_.toDomainAt(JsonPath.Root.field("organization"))) match
      case Left(failure)       => assertEquals(failure.path.render, "$.organization.id")
      case Right(organization) => fail(s"expected a failure, converted $organization")

  test("a truncated organisation body is a DecodeFailure, not an exception"):
    assert(Json.decode[OrganizationDto]("""{"id":1,"name":"forg""").isLeft)
    assert(Json.decode[OrganizationDto]("<html>not json</html>").isLeft)
