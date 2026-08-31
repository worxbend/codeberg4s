package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.ArrayElements
import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.repositories.ArchiveDownloadCount
import com.worxbend.codeberg4s.repositories.Tag

import munit.FunSuite

import java.time.Instant

/** Decodes `golden/repository/tags-list.json`, the three most recent `forgejo/forgejo` tags. */
final class TagDtoSuite extends FunSuite with GoldenFixtures:

  test("the golden tag listing decodes all three tags"):
    assertEquals(decodeList("repository/tags-list.json").size, 3)

  test("the first tag decodes field for field"):
    val dto = decodeList("repository/tags-list.json").head

    assertEquals(dto.name, Some("v15.0.6"))
    assertEquals(dto.id, Some("5f7e2e5c003c066a865ea483e42809fa87d85eae"))
    assertEquals(dto.zipballUrl, Some("https://codeberg.org/forgejo/forgejo/archive/v15.0.6.zip"))
    assertEquals(dto.tarballUrl, Some("https://codeberg.org/forgejo/forgejo/archive/v15.0.6.tar.gz"))

  test("the embedded commit reference decodes"):
    val commit = decodeList("repository/tags-list.json").head.commit

    assertEquals(commit.flatMap(_.sha), Some("5f7e2e5c003c066a865ea483e42809fa87d85eae"))
    assertEquals(commit.flatMap(_.created), Some("2026-07-30T21:14:15+02:00"))

  test("the first tag converts to a domain tag"):
    val tag = domain("repository/tags-list.json").head

    assertEquals(tag.name.value, "v15.0.6")
    assertEquals(tag.commitSha.value, "5f7e2e5c003c066a865ea483e42809fa87d85eae")
    assertEquals(tag.commit.map(_.sha.value), Some("5f7e2e5c003c066a865ea483e42809fa87d85eae"))
    assertEquals(tag.commit.flatMap(_.created), Some(Instant.parse("2026-07-30T19:14:15Z")))

  test("an annotated tag keeps its message"):
    assert(domain("repository/tags-list.json").head.message.exists(_.contains("disable hooks during diff patch")))

  test("the archive download counters convert, tar_gz included"):
    assertEquals(domain("repository/tags-list.json").head.archiveDownloads, Some(ArchiveDownloadCount(1L, 16L)))

  test("a tag without a name cannot be converted"):
    val failure = TagDto(None, None, Some("abcdef12"), None, None, None, None).toDomain

    assertEquals(failure.swap.toOption.map(_.path.render), Some("$.name"))

  test("a tag whose id is not an object id cannot be converted"):
    val failure = TagDto(Some("v1"), None, Some("not-a-sha"), None, None, None, None).toDomain

    assertEquals(failure.swap.toOption.map(_.path.render), Some("$.id"))

  test("a failure inside the commit reference is reported at its own path"):
    val broken = TagDto(Some("v1"), None, Some("abcdef12"), Some(CommitMetaDto(None, None, None)), None, None, None)

    assertEquals(broken.toDomain.swap.toOption.map(_.path.render), Some("$.commit.sha"))

  private def decodeList(fixture: String): Vector[TagDto] =
    Json.decode[Vector[TagDto]](golden(fixture)) match
      case Right(dtos)   => dtos
      case Left(failure) => fail(s"$fixture did not decode: ${failure.path.render} ${failure.message}")

  private def domain(fixture: String): Vector[Tag] =
    ArrayElements.convert(JsonPath.Root, decodeList(fixture))((dto, at) => dto.toDomainAt(at)) match
      case Right(tags)   => tags
      case Left(failure) => fail(s"$fixture did not convert: ${failure.path.render} ${failure.message}")
