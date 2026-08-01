package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.repositories.ArchiveDownloadCount
import com.worxbend.codeberg4s.repositories.Release

import munit.FunSuite

import java.time.Instant

/** Decodes both golden release captures: the single latest release and the two-element listing. */
final class ReleaseDtoSuite extends FunSuite with GoldenFixtures:

  test("the golden latest-release capture decodes field for field"):
    val dto = decode("repository/release-latest.json")

    assertEquals(dto.id, Some(11189746L))
    assertEquals(dto.tagName, Some("v16.0.2"))
    assertEquals(dto.name, Some("v16.0.2"))
    assertEquals(dto.draft, Some(false))
    assertEquals(dto.prerelease, Some(false))
    assertEquals(dto.hideArchiveLinks, Some(false))

  test("the empty target_commitish Forgejo sends is absence"):
    assertEquals(decode("repository/release-latest.json").targetCommitish, None)

  test("the latest release converts to the domain"):
    val release = domain("repository/release-latest.json")

    assertEquals(release.id.value, 11189746L)
    assertEquals(release.tagName.value, "v16.0.2")
    assertEquals(release.isDraft, false)
    assertEquals(release.isPrerelease, false)
    assertEquals(release.createdAt, Some(Instant.parse("2026-07-30T20:59:53Z")))
    assertEquals(release.publishedAt, Some(Instant.parse("2026-07-30T20:59:53Z")))
    assertEquals(release.author.map(_.login), Some("release-team"))

  test("the release notes survive as Markdown"):
    assert(domain("repository/release-latest.json").body.exists(_.nonEmpty))

  test("all twenty-one assets convert"):
    val assets = domain("repository/release-latest.json").assets

    assertEquals(assets.size, 21)
    assertEquals(assets.head.id, 1730449L)
    assertEquals(assets.head.name, "forgejo-16.0.2-linux-amd64")
    assertEquals(assets.head.size, 119142664L)
    assertEquals(assets.head.downloadCount, 705L)
    assertEquals(assets.head.uuid, Some("49e93aa2-3684-4039-9404-c7215f353642"))

  test("the archive download counters convert"):
    assertEquals(domain("repository/release-latest.json").archiveDownloads, Some(ArchiveDownloadCount(5L, 119L)))

  test("the golden release listing decodes both releases"):
    val releases = list("repository/releases-list.json")

    assertEquals(releases.map(_.id.value), Vector(11189746L, 11189725L))
    assertEquals(releases.map(_.tagName.value), Vector("v16.0.2", "v15.0.6"))

  test("a release without an id cannot be converted"):
    assertEquals(empty.toDomain.swap.toOption.map(_.path.render), Some("$.id"))

  test("a release without a tag cannot be converted"):
    assertEquals(empty.copy(id = Some(1L)).toDomain.swap.toOption.map(_.path.render), Some("$.tag_name"))

  test("an asset without a name is reported at its own index"):
    val broken = empty.copy(
      id      = Some(1L),
      tagName = Some("v1"),
      assets  = Vector(ReleaseAssetDto(Some(1L), None, None, None, None, None, None, None)),
    )

    assertEquals(broken.toDomain.swap.toOption.map(_.path.render), Some("$.assets[0].name"))

  private val empty: ReleaseDto = ReleaseDto(
    id                   = None,
    tagName              = None,
    targetCommitish      = None,
    name                 = None,
    body                 = None,
    url                  = None,
    htmlUrl              = None,
    tarballUrl           = None,
    zipballUrl           = None,
    hideArchiveLinks     = None,
    uploadUrl            = None,
    draft                = None,
    prerelease           = None,
    createdAt            = None,
    publishedAt          = None,
    author               = None,
    assets               = Vector.empty,
    archiveDownloadCount = None,
  )

  private def decode(fixture: String): ReleaseDto =
    Json.decode[ReleaseDto](golden(fixture)) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"$fixture did not decode: ${failure.path.render} ${failure.message}")

  private def domain(fixture: String): Release =
    decode(fixture).toDomain match
      case Right(release) => release
      case Left(failure)  => fail(s"$fixture did not convert: ${failure.path.render} ${failure.message}")

  private def list(fixture: String): Vector[Release] =
    Json.decode[Vector[ReleaseDto]](golden(fixture)) match
      case Left(failure) => fail(s"$fixture did not decode: ${failure.path.render} ${failure.message}")
      case Right(dtos)   =>
        Elements.convert(JsonPath.Root, dtos)((dto, at) => dto.toDomainAt(at)) match
          case Right(releases) => releases
          case Left(failure)   => fail(s"$fixture did not convert: ${failure.path.render} ${failure.message}")
