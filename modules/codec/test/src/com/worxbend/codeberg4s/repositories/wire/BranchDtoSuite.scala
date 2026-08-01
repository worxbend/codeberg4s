package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.repositories.Branch

import munit.FunSuite

import java.time.Instant

/** Decodes the two golden branch captures: the single-branch response and the three-element listing.
  *
  * One of those three branches is named `renovate/…` and another `v16.0/…`, which is the measurement behind
  * [[com.worxbend.codeberg4s.repositories.BranchName]] accepting slashes.
  */
final class BranchDtoSuite extends FunSuite with GoldenFixtures:

  test("golden branch-single decodes field for field"):
    val dto = decode("repository/branch-single.json")

    assertEquals(dto.name, Some("forgejo"))
    assertEquals(dto.isProtected, Some(true))
    assertEquals(dto.requiredApprovals, Some(1L))
    assertEquals(dto.enableStatusCheck, Some(true))
    assertEquals(dto.statusCheckContexts.size, 6)
    assert(dto.statusCheckContexts.contains("testing / backend-checks (pull_request)"))
    assertEquals(dto.userCanPush, Some(false))
    assertEquals(dto.userCanMerge, Some(false))

  test("the empty protection name Forgejo sends instead of null is absence"):
    assertEquals(decode("repository/branch-single.json").effectiveBranchProtectionName, None)

  test("the embedded commit decodes, including its Git identities"):
    val commit = decode("repository/branch-single.json").commit

    assertEquals(commit.flatMap(_.id), Some("647de8b3279b0ce6e9721cc651e431981725edf1"))
    assertEquals(commit.flatMap(_.author).flatMap(_.name), Some("Renovate Bot"))
    assertEquals(commit.flatMap(_.author).flatMap(_.username), Some("viceice-bot"))
    assertEquals(commit.flatMap(_.committer).flatMap(_.name), Some("Mathieu Fenniak"))
    assertEquals(commit.flatMap(_.committer).flatMap(_.username), Some("mfenniak"))

  test("the null added/removed/modified arrays decode as empty, where a derived codec would abort"):
    val commit = decode("repository/branch-single.json").commit

    assertEquals(commit.map(_.added), Some(Vector.empty))
    assertEquals(commit.map(_.removed), Some(Vector.empty))
    assertEquals(commit.map(_.modified), Some(Vector.empty))

  test("an unsigned commit reports Forgejo's own reason token"):
    val verification = decode("repository/branch-single.json").commit.flatMap(_.verification)

    assertEquals(verification.flatMap(_.verified), Some(false))
    assertEquals(verification.flatMap(_.reason), Some("gpg.error.not_signed_commit"))
    assertEquals(verification.flatMap(_.signer), None)

  test("the single capture converts to a domain branch"):
    val branch = domain("repository/branch-single.json")

    assertEquals(branch.name.value, "forgejo")
    assertEquals(branch.isProtected, true)
    assertEquals(branch.statusCheckEnabled, true)
    assertEquals(branch.requiredApprovals, 1L)
    assertEquals(branch.commit.sha.value, "647de8b3279b0ce6e9721cc651e431981725edf1")
    assertEquals(branch.commit.timestamp, Some(Instant.parse("2026-08-01T17:58:48Z")))

  test("golden branches-list decodes all three branches"):
    assertEquals(decodeList("repository/branches-list.json").size, 3)

  test("a slashed branch name survives conversion and splits into route segments"):
    val branches = list("repository/branches-list.json")
    val renovate = branches.head

    assertEquals(renovate.name.value, "renovate/forgejo-github.com-go-swagger-go-swagger-cmd-swagger-0.x")
    assertEquals(renovate.name.segments.size, 2)

  test("a signed commit carries its signer identity"):
    val signer = list("repository/branches-list.json").head.commit.verification.flatMap(_.signer)

    assertEquals(signer.flatMap(_.name), Some("viceice-bot"))
    assertEquals(signer.flatMap(_.username), None, "the empty username Forgejo sends is absence")

  test("a branch without a name cannot be converted, and the failure names the field"):
    val failure = BranchDto(None, None, None, None, None, Vector.empty, None, None, None).toDomain.swap.toOption

    assertEquals(failure.map(_.path.render), Some("$.name"))

  test("a branch without a commit cannot be converted"):
    val nameless = BranchDto(Some("main"), None, None, None, None, Vector.empty, None, None, None)

    assertEquals(nameless.toDomain.swap.toOption.map(_.path.render), Some("$.commit"))

  test("a failure inside the commit is reported at the commit's own path"):
    val broken = BranchDto(
      name = Some("main"),
      commit = Some(PayloadCommitDto(None, None, None, None, None, None, None, Vector.empty, Vector.empty, Vector.empty)),
      isProtected                   = None,
      requiredApprovals             = None,
      enableStatusCheck             = None,
      statusCheckContexts           = Vector.empty,
      userCanPush                   = None,
      userCanMerge                  = None,
      effectiveBranchProtectionName = None,
    )

    assertEquals(broken.toDomain.swap.toOption.map(_.path.render), Some("$.commit.id"))

  private def decode(fixture: String): BranchDto =
    Json.decode[BranchDto](golden(fixture)) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"$fixture did not decode: ${failure.path.render} ${failure.message}")

  private def decodeList(fixture: String): Vector[BranchDto] =
    Json.decode[Vector[BranchDto]](golden(fixture)) match
      case Right(dtos)   => dtos
      case Left(failure) => fail(s"$fixture did not decode: ${failure.path.render} ${failure.message}")

  private def domain(fixture: String): Branch =
    decode(fixture).toDomain match
      case Right(branch) => branch
      case Left(failure) => fail(s"$fixture did not convert: ${failure.path.render} ${failure.message}")

  private def list(fixture: String): Vector[Branch] =
    Elements.convert(JsonPath.Root, decodeList(fixture))((dto, at) => dto.toDomainAt(at)) match
      case Right(branches) => branches
      case Left(failure)   => fail(s"$fixture did not convert: ${failure.path.render} ${failure.message}")
