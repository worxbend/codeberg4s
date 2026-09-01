package com.worxbend.codeberg4s.users.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.UserVisibility
import com.worxbend.codeberg4s.wire.SearchEnvelopeDto

import munit.FunSuite

import java.time.Instant

/** Decodes every golden fixture whose payload is a `User`, and asserts on named fields rather than on a round trip.
  *
  * A round-trip test would pass even if a field were dropped, because a dropped field is symmetric. Naming the values
  * is what catches the drop.
  */
final class UserDtoSuite extends FunSuite with GoldenFixtures:

  private def decodeUser(fixture: String): UserDto =
    Json.decode[UserDto](golden(fixture)) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"$fixture did not decode: ${failure.path.render} ${failure.message}")

  private def domainUser(fixture: String): User =
    decodeUser(fixture).toDomain match
      case Right(user)   => user
      case Left(failure) => fail(s"$fixture did not convert: ${failure.path.render} ${failure.message}")

  test("golden /users/earl-warren decodes field for field"):
    val dto = decodeUser("user/user-single.json")

    assertEquals(dto.id, Some(73579L))
    assertEquals(dto.login, Some("earl-warren"))
    assertEquals(dto.username, Some("earl-warren"))
    assertEquals(dto.fullName, Some("Earl Warren"))
    assertEquals(dto.email, Some("earl-warren@noreply.codeberg.org"))
    assertEquals(dto.avatarUrl, Some("https://codeberg.org/avatars/baa02ddebf727ef06a0ded0aee5a25cc"))
    assertEquals(dto.htmlUrl, Some("https://codeberg.org/earl-warren"))
    assertEquals(dto.visibility, Some("public"))
    assertEquals(dto.isAdmin, Some(false))
    assertEquals(dto.restricted, Some(false))
    assertEquals(dto.active, Some(false))
    assertEquals(dto.prohibitLogin, Some(false))
    assertEquals(dto.followersCount, Some(47L))
    assertEquals(dto.followingCount, Some(2L))
    assertEquals(dto.starredReposCount, Some(28L))
    assertEquals(dto.created, Some("2022-11-26T18:56:24+01:00"))

  test("the fields Forgejo sends as an empty string are absent, not empty"):
    val dto = decodeUser("user/user-single.json")

    assertEquals(dto.loginName, None)
    assertEquals(dto.language, None)
    assertEquals(dto.location, None)
    assertEquals(dto.pronouns, None)
    assertEquals(dto.website, None)
    assertEquals(dto.description, None)

  test("the zero-time sentinel is carried verbatim by the DTO and folded away by the domain"):
    val dto = decodeUser("user/user-single.json")

    assertEquals(dto.lastLogin, Some("0001-01-01T00:00:00Z"))
    assertEquals(domainUser("user/user-single.json").lastLoginAt, None)

  test("golden /users/earl-warren converts to the domain"):
    val user = domainUser("user/user-single.json")

    assertEquals(user.id, 73579L)
    assertEquals(user.login.value, "earl-warren")
    assertEquals(user.fullName, Some("Earl Warren"))
    assertEquals(user.visibility, Some(UserVisibility.Public))
    assertEquals(user.followersCount, 47L)
    assertEquals(user.followingCount, 2L)
    assertEquals(user.starredRepositoriesCount, 28L)
    assertEquals(user.isAdmin, false)
    assertEquals(user.createdAt, Some(Instant.parse("2022-11-26T17:56:24Z")))

  test("golden /users/forgejo decodes as a User even though it names an organisation"):
    val dto = decodeUser("user/user-single-org-shaped.json")

    assertEquals(dto.id, Some(70422L))
    assertEquals(dto.login, Some("forgejo"))
    assertEquals(dto.fullName, Some("Forgejo"))
    assertEquals(dto.website, Some("https://forgejo.org"))
    assertEquals(dto.description, Some("Beyond coding. We forge."))
    assertEquals(dto.followersCount, Some(578L))
    assertEquals(dto.starredReposCount, Some(0L))

  test("golden /users/forgejo converts to the domain"):
    val user = domainUser("user/user-single-org-shaped.json")

    assertEquals(user.login.value, "forgejo")
    assertEquals(user.website, Some("https://forgejo.org"))
    assertEquals(user.followingCount, 0L)

  test("golden /users/search decodes through the envelope, not as a bare array"):
    Json.decode[SearchEnvelopeDto[UserDto]](golden("user/user-search.json")) match
      case Right(envelope) =>
        assertEquals(envelope.ok, Some(true))
        assertEquals(envelope.data.size, 3)
        assertEquals(envelope.data.head.login, Some("0x20fearless"))
        assertEquals(envelope.data.head.id, Some(165118L))
        assert(envelope.data.forall(_.id.isDefined), "every search hit has an id")
      case Left(failure)   => fail(s"user-search did not decode: ${failure.path.render} ${failure.message}")

  test("every user in the search envelope converts to the domain"):
    Json.decode[SearchEnvelopeDto[UserDto]](golden("user/user-search.json")) match
      case Right(envelope) =>
        val converted = envelope.data.map(_.toDomain)

        assert(converted.forall(_.isRight), s"some search hits did not convert: $converted")
      case Left(failure)   => fail(s"user-search did not decode: ${failure.path.render} ${failure.message}")

  test("a search body decoded as a bare array fails, which is why the envelope exists"):
    assert(Json.decode[Vector[UserDto]](golden("user/user-search.json")).isLeft)

  test("an absent field and an explicit null produce the same DTO"):
    val absent = Json.decode[UserDto]("""{"id":1,"login":"a"}""")

    val explicitNull =
      Json.decode[UserDto](
        """{"id":1,"login":"a","full_name":null,"email":null,"last_login":null,"created":null,
          |"is_admin":null,"followers_count":null,"visibility":null,"website":null}""".stripMargin
      )

    assertEquals(absent, explicitNull)

  test("a user reduced to the fields an embedded object carries still converts"):
    Json.decode[UserDto]("""{"id":9,"login":"someone","avatar_url":"https://example.test/a.png"}""")
      .flatMap(_.toDomain) match
      case Right(user)   =>
        assertEquals(user.id, 9L)
        assertEquals(user.login.value, "someone")
        assertEquals(user.followersCount, 0L)
        assertEquals(user.visibility, None)
        assertEquals(user.lastLoginAt, None)
      case Left(failure) => fail(s"a reduced user must still convert: ${failure.path.render} ${failure.message}")

  test("a user without an id cannot be converted"):
    Json.decode[UserDto]("""{"login":"someone"}""").flatMap(_.toDomain) match
      case Left(failure) => assertEquals(failure.path.render, "$.id")
      case Right(user)   => fail(s"expected a failure, converted $user")

  test("a user without a login cannot be converted"):
    Json.decode[UserDto]("""{"id":9}""").flatMap(_.toDomain) match
      case Left(failure) => assertEquals(failure.path.render, "$.login")
      case Right(user)   => fail(s"expected a failure, converted $user")

  test("a login that could forge a request path is rejected where the user is read"):
    Json.decode[UserDto]("""{"id":9,"login":"someone/../admin"}""").flatMap(_.toDomain) match
      case Left(failure) => assertEquals(failure.path.render, "$.login")
      case Right(user)   => fail(s"expected a failure, converted $user")

  test("a nested user reports its failure at the nested path, not at the document root"):
    Json.decode[UserDto]("""{}""").flatMap(_.toDomainAt(JsonPath.Root.index(2).field("owner"))) match
      case Left(failure) => assertEquals(failure.path.render, "$[2].owner.id")
      case Right(user)   => fail(s"expected a failure, converted $user")

  test("an unrecognised visibility does not cost the rest of the user"):
    Json.decode[UserDto]("""{"id":1,"login":"a","visibility":"quantum"}""").flatMap(_.toDomain) match
      case Right(user)   =>
        assertEquals(user.visibility, None)
        assertEquals(user.login.value, "a")
      case Left(failure) => fail(s"a new visibility must not break decoding: ${failure.message}")

  test("a truncated user body is a DecodeFailure, not an exception"):
    assert(Json.decode[UserDto]("""{"id":1,"login":"ear""").isLeft)
    assert(Json.decode[UserDto]("<html>not json</html>").isLeft)
