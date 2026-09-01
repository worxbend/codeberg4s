package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.ArrayElements
import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.repositories.Commit
import com.worxbend.codeberg4s.repositories.CommitFileStatus
import com.worxbend.codeberg4s.repositories.CommitStats

import munit.FunSuite

import java.time.Instant

/** Decodes `golden/repository/commits-list.json`, two commits from `forgejo/forgejo`'s default branch.
  *
  * The interesting property of this capture is that the same commit carries two different views of who wrote it: an
  * instance account at the top level and a Git identity underneath. Both are asserted, because collapsing them is the
  * mistake this model exists to prevent.
  */
final class CommitDtoSuite extends FunSuite with GoldenFixtures:

  private val HeadSha: String = "647de8b3279b0ce6e9721cc651e431981725edf1"

  test("the golden commit listing decodes both commits"):
    assertEquals(decodeList("repository/commits-list.json").size, 2)

  test("the first commit decodes field for field"):
    val dto = decodeList("repository/commits-list.json").head

    assertEquals(dto.sha, Some(HeadSha))
    assertEquals(dto.created, Some("2026-08-01T19:58:48+02:00"))
    assert(dto.htmlUrl.exists(_.contains("/forgejo/forgejo/commit/")))

  test("the account and the Git identity are kept apart"):
    val commit = domain("repository/commits-list.json").head

    assertEquals(commit.author.map(_.login.value), Some("viceice-bot"))
    assertEquals(commit.details.flatMap(_.author).flatMap(_.name), Some("Renovate Bot"))
    assertEquals(commit.details.flatMap(_.author).flatMap(_.username), None, "a CommitUser carries no username")

  test("the Git identity's date parses"):
    val identity = domain("repository/commits-list.json").head.details.flatMap(_.author)

    assertEquals(identity.flatMap(_.date), Some(Instant.parse("2026-08-01T17:58:48Z")))

  test("the tree reference converts"):
    assertEquals(domain("repository/commits-list.json").head.details.flatMap(_.tree).map(_.sha.value), Some(HeadSha))

  test("parents convert, one for an ordinary commit"):
    val parents = domain("repository/commits-list.json").head.parents

    assertEquals(parents.map(_.sha.value), Vector("fb91f8712c316aea35705fb929bc08f2ba1aed68"))

  test("the affected files convert, status included"):
    val files = domain("repository/commits-list.json").head.files

    assertEquals(files.map(_.filename), Vector("go.mod", "go.sum"))
    assertEquals(files.map(_.status), Vector(Some(CommitFileStatus.Modified), Some(CommitFileStatus.Modified)))

  test("the line statistics convert"):
    assertEquals(domain("repository/commits-list.json").head.stats, Some(CommitStats(6L, 3L, 3L)))

  test("an unsigned commit's null signer decodes as absence"):
    val verification = domain("repository/commits-list.json").head.details.flatMap(_.verification)

    assertEquals(verification.map(_.isVerified), Some(false))
    assertEquals(verification.flatMap(_.signer), None)

  test("a commit without a sha cannot be converted"):
    assertEquals(empty.toDomain.swap.toOption.map(_.path.render), Some("$.sha"))

  test("a parent without a sha is reported at its own index"):
    val broken = empty.copy(sha = Some(HeadSha), parents = Vector(CommitMetaDto(None, None, None)))

    assertEquals(broken.toDomain.swap.toOption.map(_.path.render), Some("$.parents[0].sha"))

  test("a listing failure is reported at the element's index"):
    val dtos = Vector(empty.copy(sha = Some(HeadSha)), empty)

    val failure = ArrayElements.convert(JsonPath.Root, dtos)((dto, at) => dto.toDomainAt(at)).swap.toOption

    assertEquals(failure.map(_.path.render), Some("$[1].sha"))

  private val empty: CommitDto =
    CommitDto(None, None, None, None, None, None, None, Vector.empty, Vector.empty, None)

  private def decodeList(fixture: String): Vector[CommitDto] =
    Json.decode[Vector[CommitDto]](golden(fixture)) match
      case Right(dtos)   => dtos
      case Left(failure) => fail(s"$fixture did not decode: ${failure.path.render} ${failure.message}")

  private def domain(fixture: String): Vector[Commit] =
    ArrayElements.convert(JsonPath.Root, decodeList(fixture))((dto, at) => dto.toDomainAt(at)) match
      case Right(commits) => commits
      case Left(failure)  => fail(s"$fixture did not convert: ${failure.path.render} ${failure.message}")
