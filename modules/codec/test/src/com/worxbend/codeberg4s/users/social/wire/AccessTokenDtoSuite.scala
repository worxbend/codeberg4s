package com.worxbend.codeberg4s.users.social.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.auth.ApiToken
import com.worxbend.codeberg4s.codec.{Json, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.users.social.AccessToken
import com.worxbend.codeberg4s.users.social.CreatedAccessToken
import com.worxbend.codeberg4s.users.social.TokenCategory
import com.worxbend.codeberg4s.users.social.TokenScope

import munit.FunSuite

import java.time.Instant

/** [[AccessTokenDto]] is the one DTO in the library that can hold a working user credential.
  *
  * '''No golden fixture backs it''': both token endpoints authenticate and the harvest was anonymous, so every body
  * below is hand-authored from `definitions.AccessToken` in the pinned spec. Half of this suite is about the field
  * sets; the other half is about the credential never leaving through a door it was not meant to.
  */
final class AccessTokenDtoSuite extends FunSuite:

  test("a listing element decodes field for field"):
    val dto = decode(AccessTokenDtoSuite.ListedToken)

    assertEquals(dto.id, Some(42L))
    assertEquals(dto.name, Some("ci"))
    assertEquals(dto.scopes, Vector("read:repository", "write:issue"))
    assertEquals(dto.tokenLastEight, Some("edential"))
    assertEquals(dto.created, Some("2026-07-30T21:14:15+02:00"))
    assertEquals(dto.repositories.flatMap(_.name), Vector("forgejo"))

  test("a listing element converts without a credential, because the wire sends none"):
    val token = domain(AccessTokenDtoSuite.ListedToken)

    assertEquals(token.id.value, 42L)
    assertEquals(token.name.map(_.value), Some("ci"))
    assertEquals(
      token.scopes,
      Vector(TokenScope.Read(TokenCategory.Repository), TokenScope.Write(TokenCategory.Issue)),
    )
    assertEquals(token.lastEight, Some("edential"))
    assertEquals(token.repositories.map(_.value), Vector("forgejo/forgejo"))
    assertEquals(token.createdAt, Some(Instant.parse("2026-07-30T19:14:15Z")))

  test("an explicit null and an absent key decode identically, per HAZARDS §1"):
    val explicitNulls = decode(AccessTokenDtoSuite.NulledToken)
    val absent        = decode("""{"id": 42}""")

    assertEquals(explicitNulls.name, absent.name)
    assertEquals(explicitNulls.scopes, absent.scopes)
    assertEquals(explicitNulls.sha1, absent.sha1)
    assertEquals(explicitNulls.tokenLastEight, absent.tokenLastEight)
    assertEquals(explicitNulls.repositories, absent.repositories)
    assertEquals(explicitNulls.created, absent.created)

  test("an empty sha1 is the same as no sha1, which is what every listing sends"):
    assertEquals(decode("""{"id": 42, "sha1": ""}""").sha1, None)

  test("a token reduced to its id still converts, with an unconfined repository set"):
    val token = domain("""{"id": 42}""")

    assertEquals(token.name, None)
    assertEquals(token.scopes, Vector.empty[TokenScope])
    assertEquals(token.repositories, Vector.empty)
    assertEquals(token.lastEight, None)

  test("a token without an id fails, because the id is the unambiguous way to revoke it"):
    assertEquals(failure("""{"name": "ci"}""").path.render, "$.id")
    assertEquals(failure("""{"id": 0}""").path.render, "$.id")

  test("a scope this release does not model survives rather than failing the page"):
    assertEquals(domain("""{"id": 42, "scopes": ["read:admin"]}""").scopes, Vector(TokenScope.Other("read:admin")))

  test("a name the client will not carry costs the name, not the token"):
    val token = domain("""{"id": 42, "name": "ci/deploy"}""")

    assertEquals(token.id.value, 42L)
    assertEquals(token.name, None)

  test("a repository entry that yields no usable slug is dropped, not failed on"):
    val token = domain("""{"id": 42, "repositories": [{"owner": "forgejo", "name": "a/b"}]}""")

    assertEquals(token.repositories, Vector.empty)

  test("a bad element names its position in the array"):
    val outcome = Json
      .decode[Vector[AccessTokenDto]]("""[{"id": 1}, {"name": "ci"}]""")
      .flatMap(WireModel.all(JsonPath.Root, _))

    outcome match
      case Left(problem) => assertEquals(problem.path.render, "$[1].id")
      case Right(tokens) => fail(s"expected the second element to fail, got $tokens")

  // --- the credential -------------------------------------------------------

  test("a creation response yields the credential, and only through the conversion meant for it"):
    val created = createdFrom(AccessTokenDtoSuite.CreatedToken)

    assertEquals(created.token.reveal, "gto_realcredential")
    assertEquals(created.details.id.value, 42L)
    assertEquals(created.details.name.map(_.value), Some("ci"))

  test("the listing conversion drops the credential even when the wire carries one"):
    val listed = domain(AccessTokenDtoSuite.CreatedToken)

    assert(!listed.toString.contains("gto_realcredential"), s"the token reached the listing model: $listed")
    assertEquals(listed.lastEight, Some("edential"))

  test("a created token never renders its material through the DTO that carried it"):
    val dto = decode(AccessTokenDtoSuite.CreatedToken)

    assert(!dto.toString.contains("gto_realcredential"), s"the credential reached the DTO's toString: $dto")
    assert(dto.toString.contains(ApiToken.Redacted), s"the mask is missing from the DTO's toString: $dto")
    assert(!s"$dto".contains("gto_realcredential"), "the credential reached string interpolation")

  test("a DTO that carried no credential says so, rather than printing the mask"):
    val dto = decode(AccessTokenDtoSuite.ListedToken)

    assert(!dto.toString.contains(ApiToken.Redacted), s"a token-free DTO printed the mask: $dto")

  test("a creation response without usable material fails at sha1, and never echoes it"):
    val problem = createdFailure("""{"id": 42, "sha1": "   "}""")

    assertEquals(problem.path.render, "$.sha1")
    assert(!problem.message.contains("   "), s"the rejected value reached the failure: ${problem.message}")

  test("a creation response missing sha1 entirely fails at sha1, not silently"):
    assertEquals(createdFailure("""{"id": 42}""").path.render, "$.sha1")

  test("a creation response missing its id fails at id, exactly as a listing element would"):
    assertEquals(createdFailure(AccessTokenDtoSuite.CreatedTokenWithoutId).path.render, "$.id")

  // --- harness --------------------------------------------------------------

  private def decode(body: String): AccessTokenDto =
    Json.decode[AccessTokenDto](body) match
      case Right(dto)    => dto
      case Left(problem) => fail(s"the body did not decode: ${problem.path.render} ${problem.message}")

  private def domain(body: String): AccessToken =
    decode(body).toDomain match
      case Right(token)  => token
      case Left(problem) => fail(s"the token did not convert: ${problem.path.render} ${problem.message}")

  private def createdFrom(body: String): CreatedAccessToken =
    decode(body).toCreated match
      case Right(created) => created
      case Left(problem)  => fail(s"the creation did not convert: ${problem.path.render} ${problem.message}")

  private def failure(body: String): DecodeFailure =
    decode(body).toDomain match
      case Left(problem) => problem
      case Right(token)  => fail(s"expected the conversion to fail, got $token")

  private def createdFailure(body: String): DecodeFailure =
    decode(body).toCreated match
      case Left(problem)  => problem
      case Right(created) => fail(s"expected the conversion to fail, got $created")

/** The bodies this suite decodes, hand-authored from `definitions.AccessToken`; see the class note. */
object AccessTokenDtoSuite:

  private val ListedToken: String =
    """{
      |  "id": 42,
      |  "name": "ci",
      |  "scopes": ["read:repository", "write:issue"],
      |  "sha1": "",
      |  "token_last_eight": "edential",
      |  "repositories": [{"id": 7, "name": "forgejo", "owner": "forgejo", "full_name": "forgejo/forgejo"}],
      |  "created_at": "2026-07-30T21:14:15+02:00",
      |  "unmodelled_key": "ignored"
      |}""".stripMargin

  private val NulledToken: String =
    """{
      |  "id": 42,
      |  "name": null,
      |  "scopes": null,
      |  "sha1": null,
      |  "token_last_eight": null,
      |  "repositories": null,
      |  "created_at": null
      |}""".stripMargin

  private val CreatedToken: String =
    """{
      |  "id": 42,
      |  "name": "ci",
      |  "scopes": ["read:repository"],
      |  "sha1": "gto_realcredential",
      |  "token_last_eight": "edential",
      |  "created_at": "2026-07-30T21:14:15+02:00"
      |}""".stripMargin

  private val CreatedTokenWithoutId: String =
    """{"name": "ci", "sha1": "gto_realcredential", "token_last_eight": "edential"}"""
