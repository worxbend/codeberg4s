package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.repositories.RepositoryPermissions
import com.worxbend.codeberg4s.wire.SearchEnvelopeDto

import munit.FunSuite

import java.time.Instant

/** Decodes every golden fixture whose payload is a `Repository` — the two single-repository captures, the three list
  * captures, the fork list and the search envelope. Thirteen repository objects in total, which is what makes the
  * absent-versus-null assertions below evidence rather than opinion.
  */
final class RepositoryDtoSuite extends FunSuite with GoldenFixtures:

  private def decodeRepo(fixture: String): RepositoryDto =
    Json.decode[RepositoryDto](golden(fixture)) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"$fixture did not decode: ${failure.path.render} ${failure.message}")

  private def decodeList(fixture: String): Vector[RepositoryDto] =
    Json.decode[Vector[RepositoryDto]](golden(fixture)) match
      case Right(dtos)   => dtos
      case Left(failure) => fail(s"$fixture did not decode: ${failure.path.render} ${failure.message}")

  private def domainRepo(fixture: String): Repository =
    decodeRepo(fixture).toDomain match
      case Right(repository) => repository
      case Left(failure)     => fail(s"$fixture did not convert: ${failure.path.render} ${failure.message}")

  test("golden /repos/forgejo/forgejo decodes field for field"):
    val dto = decodeRepo("repository/repo-single.json")

    assertEquals(dto.id, Some(73144L))
    assertEquals(dto.name, Some("forgejo"))
    assertEquals(dto.fullName, Some("forgejo/forgejo"))
    assertEquals(dto.description, Some("Beyond coding. We forge."))
    assertEquals(dto.owner.flatMap(_.login), Some("forgejo"))
    assertEquals(dto.isPrivate, Some(false))
    assertEquals(dto.fork, Some(false))
    assertEquals(dto.empty, Some(false))
    assertEquals(dto.size, Some(364707L))
    assertEquals(dto.language, Some("Go"))
    assertEquals(dto.defaultBranch, Some("forgejo"))
    assertEquals(dto.starsCount, Some(5233L))
    assertEquals(dto.forksCount, Some(892L))
    assertEquals(dto.watchersCount, Some(187L))
    assertEquals(dto.openIssuesCount, Some(1423L))
    assertEquals(dto.openPrCounter, Some(167L))
    assertEquals(dto.releaseCounter, Some(113L))
    assertEquals(dto.objectFormatName, Some("sha1"))
    assertEquals(dto.defaultMergeStyle, Some("squash"))
    assertEquals(dto.topics, Vector("forge", "forgejo", "git", "self-hosted"))

  test("the embedded permissions and internal tracker objects decode"):
    val dto = decodeRepo("repository/repo-single.json")

    assertEquals(dto.permissions, Some(PermissionDto(Some(false), Some(false), Some(true))))
    assertEquals(dto.internalTracker.flatMap(_.enableTimeTracker), Some(false))
    assertEquals(dto.internalTracker.flatMap(_.allowOnlyContributorsToTrackTime), Some(true))
    assertEquals(dto.internalTracker.flatMap(_.enableIssueDependencies), Some(true))

  test("an explicit null parent decodes as absence"):
    assertEquals(decodeRepo("repository/repo-single.json").parent, None)

  test("the field Forgejo sends as an empty string is absent"):
    assertEquals(decodeRepo("repository/repo-single.json").link, None)
    assertEquals(decodeRepo("repository/repo-single.json").mirrorInterval, None)

  test("golden /repos/forgejo/forgejo converts to the domain"):
    val repository = domainRepo("repository/repo-single.json")

    assertEquals(repository.id, 73144L)
    assertEquals(repository.slug.value, "forgejo/forgejo")
    assertEquals(repository.fullName, "forgejo/forgejo")
    assertEquals(repository.owner.login.value, "forgejo")
    assertEquals(repository.owner.id, 70422L)
    assertEquals(repository.starsCount, 5233L)
    assertEquals(repository.sizeKb, 364707L)
    assertEquals(repository.openPullRequestCount, 167L)
    assertEquals(repository.releaseCount, 113L)
    assertEquals(repository.topics, Vector("forge", "forgejo", "git", "self-hosted"))
    assertEquals(repository.permissions, Some(RepositoryPermissions(admin = false, push = false, pull = true)))
    assertEquals(repository.parent, None)
    assertEquals(repository.isPrivate, false)
    assertEquals(repository.isArchived, false)
    assertEquals(repository.hasIssues, true)
    assertEquals(repository.hasWiki, false)
    assertEquals(repository.createdAt, Some(Instant.parse("2022-11-06T06:24:57Z")))

  test("the epoch sentinel Forgejo sends for a repository that was never archived is absence"):
    val dto = decodeRepo("repository/repo-single.json")

    assertEquals(dto.archivedAt, Some("1970-01-01T01:00:00+01:00"))
    assertEquals(dto.mirrorUpdated, Some("0001-01-01T00:00:00Z"))
    assertEquals(domainRepo("repository/repo-single.json").archivedAt, None)

  test("golden /repos/codeberg/Community decodes and converts"):
    val repository = domainRepo("repository/repo-single-community.json")

    assertEquals(repository.id, 1L)
    assertEquals(repository.slug.value, "Codeberg/Community")
    assertEquals(repository.fullName, "Codeberg/Community")
    assertEquals(repository.owner.login.value, "Codeberg")
    assertEquals(repository.defaultBranch, Some("main"))
    assertEquals(repository.starsCount, 395L)
    assertEquals(repository.topics, Vector("codeberg", "community"))
    assertEquals(repository.description, Some("Discussion of community- and platform-related issues"))
    assertEquals(repository.createdAt, Some(Instant.parse("2018-06-29T10:48:58Z")))

  test("golden /users/earl-warren/repos decodes as a bare list and every element converts"):
    val dtos = decodeList("user/user-repos-list.json")

    assertEquals(dtos.size, 3)
    assertEquals(dtos.head.fullName, Some("earl-warren/website"))
    assert(dtos.map(_.toDomain).forall(_.isRight), "every listed repository converts")

  test("a repository that omits internal_tracker entirely still decodes"):
    val dtos = decodeList("user/user-repos-list.json")

    assertEquals(dtos(1).fullName, Some("earl-warren/woodpecker-forgejo"))
    assertEquals(dtos(1).internalTracker, None)
    assert(dtos(1).toDomain.isRight, "an omitted settings object must not cost the repository")

  test("golden /orgs/forgejo/repos decodes as a bare list"):
    val dtos = decodeList("organization/org-repos-list.json")

    assertEquals(dtos.size, 3)
    assertEquals(dtos.head.fullName, Some("forgejo/gitea-open-letter"))
    assert(dtos.map(_.toDomain).forall(_.isRight), "every listed repository converts")

  test("golden /repos/search decodes through the envelope, not as a bare array"):
    Json.decode[SearchEnvelopeDto[RepositoryDto]](golden("repository/search.json")) match
      case Right(envelope) =>
        assertEquals(envelope.ok, Some(true))
        assertEquals(envelope.data.size, 3)
        assertEquals(envelope.data.head.id, Some(211684L))
        assertEquals(envelope.data.head.fullName, Some("rindeal/__openpgp-proof-forgejo"))
        assert(envelope.data.map(_.toDomain).forall(_.isRight), "every search hit converts")
      case Left(failure)   => fail(s"repository search did not decode: ${failure.path.render} ${failure.message}")

  test("a search body decoded as a bare array fails, which is why the envelope exists"):
    assert(Json.decode[Vector[RepositoryDto]](golden("repository/search.json")).isLeft)

  test("golden /repos/forgejo/forgejo/forks carries a populated parent, and the recursion decodes"):
    val dtos = decodeList("repository/forks-list.json")

    assertEquals(dtos.size, 2)
    assertEquals(dtos.head.fullName, Some("caesar/forgejo"))
    assertEquals(dtos.head.fork, Some(true))
    assertEquals(dtos.head.parent.flatMap(_.fullName), Some("forgejo/forgejo"))
    assertEquals(dtos.head.parent.flatMap(_.id), Some(73144L))
    assertEquals(dtos.head.parent.flatMap(_.owner).flatMap(_.login), Some("forgejo"))
    assertEquals(dtos.head.parent.flatMap(_.parent), None)

  test("a fork's parent converts into the domain repository's parent"):
    val fork = decodeList("repository/forks-list.json").head.toDomain

    fork match
      case Right(repository) =>
        assertEquals(repository.slug.value, "caesar/forgejo")
        assertEquals(repository.isFork, true)
        assertEquals(repository.parent.map(_.slug.value), Some("forgejo/forgejo"))
        assertEquals(repository.parent.flatMap(_.parent), None)
      case Left(failure)     => fail(s"a fork must convert: ${failure.path.render} ${failure.message}")

  test("an absent field and an explicit null produce the same DTO"):
    val absent = Json.decode[RepositoryDto]("""{"id":1,"name":"r","owner":{"id":2,"login":"o"}}""")

    val explicitNull =
      Json.decode[RepositoryDto](
        """{"id":1,"name":"r","owner":{"id":2,"login":"o"},"parent":null,"repo_transfer":null,
          |"topics":null,"permissions":null,"internal_tracker":null,"description":null,
          |"archived":null,"created_at":null,"stars_count":null,"language":null}""".stripMargin
      )

    assertEquals(absent, explicitNull)

  test("a null topics array is an empty vector, never a crash"):
    Json.decode[RepositoryDto]("""{"id":1,"name":"r","owner":{"id":2,"login":"o"},"topics":null}""")
      .flatMap(_.toDomain) match
      case Right(repository) => assertEquals(repository.topics, Vector.empty[String])
      case Left(failure)     => fail(s"a null array must not fail: ${failure.path.render} ${failure.message}")

  test("a repository reduced to the fields an embedded object carries still converts"):
    Json.decode[RepositoryDto]("""{"id":5,"name":"tiny","owner":{"id":6,"login":"someone"}}""")
      .flatMap(_.toDomain) match
      case Right(repository) =>
        assertEquals(repository.slug.value, "someone/tiny")
        assertEquals(repository.fullName, "someone/tiny")
        assertEquals(repository.starsCount, 0L)
        assertEquals(repository.topics, Vector.empty[String])
        assertEquals(repository.permissions, None)
        assertEquals(repository.hasIssues, false)
      case Left(failure)     => fail(s"a reduced repository must convert: ${failure.path.render} ${failure.message}")

  test("a repository without an id cannot be converted"):
    Json.decode[RepositoryDto]("""{"name":"r","owner":{"id":2,"login":"o"}}""").flatMap(_.toDomain) match
      case Left(failure)     => assertEquals(failure.path.render, "$.id")
      case Right(repository) => fail(s"expected a failure, converted $repository")

  test("a repository without an owner cannot be converted"):
    Json.decode[RepositoryDto]("""{"id":1,"name":"r"}""").flatMap(_.toDomain) match
      case Left(failure)     => assertEquals(failure.path.render, "$.owner")
      case Right(repository) => fail(s"expected a failure, converted $repository")

  test("a repository whose owner has no login cannot address itself"):
    Json.decode[RepositoryDto]("""{"id":1,"name":"r","owner":{"id":2}}""").flatMap(_.toDomain) match
      case Left(failure)     => assertEquals(failure.path.render, "$.owner.login")
      case Right(repository) => fail(s"expected a failure, converted $repository")

  test("a name that would forge a request path is rejected by the smart constructor"):
    Json.decode[RepositoryDto]("""{"id":1,"name":"a/../b","owner":{"id":2,"login":"o"}}""").flatMap(_.toDomain) match
      case Left(failure)     =>
        assertEquals(failure.path.render, "$.name")
        assert(failure.message.contains("slash"), failure.message)
      case Right(repository) => fail(s"expected a failure, converted $repository")

  test("a failure inside a list element reports the element's own path"):
    val dtos = decodeList("repository/forks-list.json")

    dtos.head.toDomainAt(JsonPath.Root.index(0)).map(_.slug.value) match
      case Right(slug)   => assertEquals(slug, "caesar/forgejo")
      case Left(failure) => fail(s"unexpected failure: ${failure.path.render} ${failure.message}")

    Json.decode[RepositoryDto]("""{"name":"r"}""").flatMap(_.toDomainAt(JsonPath.Root.index(4))) match
      case Left(failure)     => assertEquals(failure.path.render, "$[4].id")
      case Right(repository) => fail(s"expected a failure, converted $repository")

  test("a truncated repository body is a DecodeFailure, not an exception"):
    assert(Json.decode[RepositoryDto]("""{"id":1,"name":"for""").isLeft)
    assert(Json.decode[RepositoryDto]("<html>502 Bad Gateway</html>").isLeft)
    assert(Json.decode[RepositoryDto]("").isLeft)
