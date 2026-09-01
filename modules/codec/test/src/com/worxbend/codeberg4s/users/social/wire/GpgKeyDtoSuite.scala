package com.worxbend.codeberg4s.users.social.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{Json, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.users.social.GpgKey

import munit.FunSuite

import java.time.Instant

/** [[GpgKeyDto]] has '''no golden fixture behind it'''.
  *
  * `/user/gpg_keys` answers `401` without a token and `/users/{u}/gpg_keys` was not among the 61 anonymous captures, so
  * the bodies below are hand-authored from `definitions.GPGKey` in the pinned spec and are shape-only evidence — never
  * evidence of optionality, which the spec does not carry. They are written the way `docs/HAZARDS.md` says Forgejo
  * actually behaves: with the Go zero-time sentinel, with explicit `null`s, and with keys the DTO does not name.
  */
final class GpgKeyDtoSuite extends FunSuite:

  test("a full key object decodes field for field"):
    val dto = decode(GpgKeyDtoSuite.FullKey)

    assertEquals(dto.id, Some(12L))
    assertEquals(dto.keyId, Some("3AA5C34371567BD2"))
    assertEquals(dto.primaryKeyId, Some("A1B2C3D4E5F60718"))
    assertEquals(dto.publicKey, Some("-----BEGIN PGP PUBLIC KEY BLOCK-----"))
    assertEquals(dto.canSign, Some(true))
    assertEquals(dto.canEncryptComms, Some(false))
    assertEquals(dto.canEncryptStorage, Some(false))
    assertEquals(dto.canCertify, Some(true))
    assertEquals(dto.verified, Some(true))
    assertEquals(dto.created, Some("2026-07-30T21:14:15+02:00"))
    assertEquals(dto.emails.flatMap(_.email), Vector("earl@example.org"))
    assertEquals(dto.subkeys.flatMap(_.id), Vector(13L))

  test("a full key object converts to the domain"):
    val key = domain(GpgKeyDtoSuite.FullKey)

    assertEquals(key.id.value, 12L)
    assertEquals(key.keyId.map(_.value), Some("3AA5C34371567BD2"))
    assertEquals(key.canSign, true)
    assertEquals(key.canEncryptComms, false)
    assertEquals(key.isVerified, true)
    assertEquals(key.createdAt, Some(Instant.parse("2026-07-30T19:14:15Z")))
    assertEquals(key.emails.map(_.email), Vector("earl@example.org"))
    assertEquals(key.emails.map(_.isVerified), Vector(true))
    assertEquals(key.subkeys.map(_.id.value), Vector(13L))

  test("an explicit null and an absent key decode identically, per HAZARDS §1"):
    val explicitNulls = decode(GpgKeyDtoSuite.NulledKey)
    val absent        = decode("""{"id": 12}""")

    assertEquals(explicitNulls.keyId, absent.keyId)
    assertEquals(explicitNulls.publicKey, absent.publicKey)
    assertEquals(explicitNulls.verified, absent.verified)
    assertEquals(explicitNulls.emails, absent.emails)
    assertEquals(explicitNulls.subkeys, absent.subkeys)
    assertEquals(explicitNulls.created, absent.created)

  test("a key reduced to its id still converts, with every capability reading as the fewest"):
    val key = domain("""{"id": 12}""")

    assertEquals(key.id.value, 12L)
    assertEquals(key.keyId, None)
    assertEquals(key.publicKey, None)
    assertEquals(key.emails, Vector.empty)
    assertEquals(key.subkeys, Vector.empty)
    assertEquals(key.canSign, false)
    assertEquals(key.canCertify, false)
    assertEquals(key.isVerified, false)
    assertEquals(key.createdAt, None)
    assertEquals(key.expiresAt, None)

  test("the Go zero time is carried verbatim by the DTO and folded away by the domain"):
    assertEquals(decode(GpgKeyDtoSuite.FullKey).expires, Some("0001-01-01T00:00:00Z"))
    assertEquals(domain(GpgKeyDtoSuite.FullKey).expiresAt, None)

  test("an id the domain will not carry is reported at its own path, not swallowed"):
    assertEquals(failure("""{"id": 0}""").path.render, "$.id")

  test("an absent id is a failure, because a key that cannot be addressed cannot be deleted"):
    assertEquals(failure("""{"key_id": "3AA5C34371567BD2"}""").path.render, "$.id")

  test("a key id the client will not carry becomes None rather than failing the key"):
    val key = domain("""{"id": 12, "key_id": "  ", "primary_key_id": null}""")

    assertEquals(key.keyId, None)
    assertEquals(key.primaryKeyId, None)

  test("a bad email element names its position under emails"):
    assertEquals(
      failure("""{"id": 12, "emails": [{"email": "a@b"}, {"verified": true}]}""").path.render,
      "$.emails[1].email",
    )

  test("a bad subkey names its position under subkeys, one level down"):
    assertEquals(failure("""{"id": 12, "subkeys": [{"id": 0}]}""").path.render, "$.subkeys[0].id")

  test("a whole array reports the position of whichever element failed"):
    val dtos    = Json.decode[Vector[GpgKeyDto]](s"""[{"id": 12}, {"key_id": "AB"}]""")
    val outcome = dtos.flatMap(WireModel.all(JsonPath.Root, _))

    outcome match
      case Left(problem) => assertEquals(problem.path.render, "$[1].id")
      case Right(keys)   => fail(s"expected the second element to fail, got $keys")

  test("an element position is reported relative to whatever path the array sits at"):
    val dtos    = Json.decode[Vector[GpgKeyDto]]("""[{"id": 0}]""")
    val outcome = dtos.flatMap(WireModel.all(JsonPath.Root.field("keys"), _))

    outcome match
      case Left(problem) => assertEquals(problem.path.render, "$.keys[0].id")
      case Right(keys)   => fail(s"expected the element to fail, got $keys")

  // --- harness --------------------------------------------------------------

  private def decode(body: String): GpgKeyDto =
    Json.decode[GpgKeyDto](body) match
      case Right(dto)    => dto
      case Left(problem) => fail(s"the body did not decode: ${problem.path.render} ${problem.message}")

  private def domain(body: String): GpgKey =
    decode(body).toDomain match
      case Right(key)    => key
      case Left(problem) => fail(s"the key did not convert: ${problem.path.render} ${problem.message}")

  private def failure(body: String): DecodeFailure =
    decode(body).toDomain match
      case Left(problem) => problem
      case Right(key)    => fail(s"expected the conversion to fail, got $key")

/** The bodies this suite decodes, hand-authored from `definitions.GPGKey`; see the class note. */
object GpgKeyDtoSuite:

  private val FullKey: String =
    """{
      |  "id": 12,
      |  "primary_key_id": "A1B2C3D4E5F60718",
      |  "key_id": "3AA5C34371567BD2",
      |  "public_key": "-----BEGIN PGP PUBLIC KEY BLOCK-----",
      |  "emails": [{"email": "earl@example.org", "verified": true}],
      |  "subkeys": [{"id": 13, "key_id": "0011223344556677", "can_sign": false, "subkeys": null}],
      |  "can_sign": true,
      |  "can_encrypt_comms": false,
      |  "can_encrypt_storage": false,
      |  "can_certify": true,
      |  "verified": true,
      |  "created_at": "2026-07-30T21:14:15+02:00",
      |  "expires_at": "0001-01-01T00:00:00Z",
      |  "unmodelled_key": "ignored"
      |}""".stripMargin

  private val NulledKey: String =
    """{
      |  "id": 12,
      |  "primary_key_id": null,
      |  "key_id": null,
      |  "public_key": null,
      |  "emails": null,
      |  "subkeys": null,
      |  "can_sign": null,
      |  "verified": null,
      |  "created_at": null,
      |  "expires_at": null
      |}""".stripMargin
