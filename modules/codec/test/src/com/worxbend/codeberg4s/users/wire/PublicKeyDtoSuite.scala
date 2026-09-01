package com.worxbend.codeberg4s.users.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.users.PublicKey

import munit.FunSuite

import java.time.Instant

/** [[PublicKeyDto]] is the one DTO in this module with '''no''' golden fixture behind it.
  *
  * `/user/keys` answers `401` without a token and `/users/{u}/keys` was not among the 61 anonymous captures, so the
  * bodies below are hand-authored from `definitions.PublicKey` in the pinned spec and are shape-only evidence — never
  * evidence of optionality, which the spec does not carry. They are therefore written the way `docs/HAZARDS.md` says
  * Forgejo actually behaves: with the zero-time sentinel, with a reduced embedded user, and with keys the DTO does not
  * name.
  */
final class PublicKeyDtoSuite extends FunSuite:

  test("a full key object decodes field for field"):
    val dto = decodeKey(PublicKeyDtoSuite.FullKey)

    assertEquals(dto.id, Some(12L))
    assertEquals(dto.key, Some(PublicKeyDtoSuite.KeyMaterial))
    assertEquals(dto.title, Some("laptop"))
    assertEquals(dto.fingerprint, Some("SHA256:0hEZoOHLK6cV1CQXPZmYt0bhIhTNfDPLQ1zqYSTn3Fk"))
    assertEquals(dto.keyType, Some("user"))
    assertEquals(dto.url, Some("https://codeberg.org/api/v1/user/keys/12"))
    assertEquals(dto.readOnly, Some(false))
    assertEquals(dto.verified, Some(true))
    assertEquals(dto.createdAt, Some("2022-11-26T18:56:24+01:00"))
    assertEquals(dto.user.flatMap(_.login), Some("earl-warren"))

  test("a full key object converts to the domain"):
    val key = domainKey(PublicKeyDtoSuite.FullKey)

    assertEquals(key.id, 12L)
    assertEquals(key.key, PublicKeyDtoSuite.KeyMaterial)
    assertEquals(key.title, Some("laptop"))
    assertEquals(key.keyType, Some("user"))
    assertEquals(key.isReadOnly, false)
    assertEquals(key.isVerified, true)
    assertEquals(key.createdAt, Some(Instant.parse("2022-11-26T17:56:24Z")))
    assertEquals(key.owner.map(_.login.value), Some("earl-warren"))

  test("the zero-time sentinel is carried verbatim by the DTO and folded away by the domain"):
    assertEquals(decodeKey(PublicKeyDtoSuite.FullKey).updatedAt, Some("0001-01-01T00:00:00Z"))
    assertEquals(domainKey(PublicKeyDtoSuite.FullKey).updatedAt, None)

  test("a key reduced to id and material still converts, with the flags reading as the fewest privileges"):
    val key = domainKey(s"""{"id": 3, "key": "${PublicKeyDtoSuite.KeyMaterial}"}""")

    assertEquals(key.id, 3L)
    assertEquals(key.title, None)
    assertEquals(key.owner, None)
    assertEquals(key.isReadOnly, false)
    assertEquals(key.isVerified, false)
    assertEquals(key.createdAt, None)

  test("an absent field and an explicit null produce the same DTO"):
    val absent = Json.decode[PublicKeyDto](s"""{"id": 3, "key": "${PublicKeyDtoSuite.KeyMaterial}"}""")

    val explicitNull =
      Json.decode[PublicKeyDto](
        s"""{"id": 3, "key": "${PublicKeyDtoSuite.KeyMaterial}", "title": null, "fingerprint": null,
           |"key_type": null, "url": null, "user": null, "read_only": null, "verified": null,
           |"created_at": null, "updated_at": null}""".stripMargin
      )

    assertEquals(absent, explicitNull)

  test("a key Forgejo decorates with fields this DTO does not name still decodes"):
    assertEquals(decodeKey(PublicKeyDtoSuite.DecoratedKey).id, Some(7L))

  test("a key without an id cannot be converted"):
    assertEquals(failurePath(s"""{"key": "${PublicKeyDtoSuite.KeyMaterial}"}"""), Some("$.id"))

  test("a key without its material cannot be converted, because it then identifies nothing"):
    assertEquals(failurePath("""{"id": 3}"""), Some("$.key"))

  test("a failure inside the embedded user is reported at the user's path, not at the key's"):
    assertEquals(
      failurePath(s"""{"id": 3, "key": "${PublicKeyDtoSuite.KeyMaterial}", "user": {"login": "earl-warren"}}"""),
      Some("$.user.id"),
    )

  test("a nested key reports its failure relative to the path it was given"):
    Json.decode[PublicKeyDto]("""{}""").flatMap(_.toDomainAt(JsonPath.Root.index(2))) match
      case Left(failure) => assertEquals(failure.path.render, "$[2].id")
      case Right(key)    => fail(s"expected a failure, converted $key")

  test("a list of keys decodes as a bare array, which is what the endpoint returns"):
    Json.decode[Vector[PublicKeyDto]](PublicKeyDtoSuite.KeyList) match
      case Right(dtos)   =>
        assertEquals(dtos.size, 2)
        assertEquals(dtos.map(_.id), Vector(Some(12L), Some(13L)))
        assert(dtos.map(_.toDomain).forall(_.isRight), "every listed key converts")
      case Left(failure) => fail(s"a key list did not decode: ${failure.path.render} ${failure.message}")

  test("a truncated key body is a DecodeFailure, not an exception"):
    assert(Json.decode[PublicKeyDto]("""{"id": 1, "key": "ssh-ed""").isLeft)
    assert(Json.decode[PublicKeyDto]("<html>not json</html>").isLeft)

  private def decodeKey(body: String): PublicKeyDto =
    Json.decode[PublicKeyDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the body did not decode: ${failure.path.render} ${failure.message}")

  private def domainKey(body: String): PublicKey =
    decodeKey(body).toDomain match
      case Right(key)    => key
      case Left(failure) => fail(s"the body did not convert: ${failure.path.render} ${failure.message}")

  /** The rendered path of the conversion failure, or `None` when the body converted after all. */
  private def failurePath(body: String): Option[String] =
    Json.decode[PublicKeyDto](body).flatMap(_.toDomain).swap.toOption.map(_.path.render)

/** The hand-authored bodies this suite decodes, kept out of the test bodies so each test reads as one behaviour. */
object PublicKeyDtoSuite:

  /** A public key is public data — this one is a throwaway written for this test and authorises nothing. */
  private val KeyMaterial: String =
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIH2Tc9rWKuJ7YbCzMPqZgUu0Vv0hEwUx1cQ9nD2b4Fk1 earl@example"

  /** Every property `definitions.PublicKey` declares, with the zero-time sentinel on `updated_at`. */
  private val FullKey: String =
    s"""{
       |  "id": 12,
       |  "key": "$KeyMaterial",
       |  "url": "https://codeberg.org/api/v1/user/keys/12",
       |  "title": "laptop",
       |  "fingerprint": "SHA256:0hEZoOHLK6cV1CQXPZmYt0bhIhTNfDPLQ1zqYSTn3Fk",
       |  "key_type": "user",
       |  "created_at": "2022-11-26T18:56:24+01:00",
       |  "updated_at": "0001-01-01T00:00:00Z",
       |  "read_only": false,
       |  "verified": true,
       |  "user": {"id": 73579, "login": "earl-warren", "avatar_url": "https://codeberg.org/avatars/baa02dde"}
       |}""".stripMargin

  /** A key carrying a field no version of the spec declares, which must not cost the rest of the object. */
  private val DecoratedKey: String =
    s"""{"id": 7, "key": "$KeyMaterial", "signing_only": true, "expires_at": "2030-01-01T00:00:00Z"}"""

  private val KeyList: String =
    s"""[
       |  {"id": 12, "key": "$KeyMaterial", "title": "laptop"},
       |  {"id": 13, "key": "$KeyMaterial", "title": "workstation", "read_only": true}
       |]""".stripMargin
