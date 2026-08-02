package com.worxbend.codeberg4s.organizations.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.organizations.BlockedUser
import com.worxbend.codeberg4s.organizations.OrganizationPermissions

import munit.FunSuite

import java.time.Instant

/** `OrganizationPermissions` and `BlockedUser` on the wire.
  *
  * '''Both payloads are derived from `spec/swagger.v1.json`, not captured.''' `GET /users/{u}/orgs/{org}/permissions`
  * and `GET /orgs/{org}/list_blocked` each need a token and the golden harvest was anonymous, so the bodies below were
  * written by hand to match the spec's definitions and are not evidence that Forgejo sends exactly this.
  *
  * Each field is exercised three ways — present, JSON `null`, and absent — because
  * [[com.worxbend.codeberg4s.codec.WireConventions]] rule 2 says the last two must be indistinguishable, and because
  * `docs/HAZARDS.md` §1 measured that the spec asserts nothing about which of the three an instance will send.
  */
final class OrganizationAdminDtoSuite extends FunSuite:

  // --- OrganizationPermissions ----------------------------------------------

  test("every permission flag decodes when present"):
    val permissions = domainPermissions(
      """{"is_owner":true,"is_admin":true,"can_write":true,"can_read":true,"can_create_repository":true}"""
    )

    assertEquals(permissions, OrganizationPermissions(true, true, true, true, true))

  test("a permission flag sent as JSON null reads as 'may not'"):
    val permissions = domainPermissions("""{"is_owner":null,"can_read":true}""")

    assertEquals(permissions.isOwner, false)
    assertEquals(permissions.canRead, true)

  test("an absent permission flag decodes identically to one sent as null"):
    assertEquals(domainPermissions("""{"can_read":true}"""), domainPermissions("""{"is_owner":null,"can_read":true}"""))

  test("a permissions payload with no keys at all is five falses, not a failure"):
    assertEquals(domainPermissions("{}"), OrganizationPermissions(false, false, false, false, false))

  test("a permission flag of the wrong JSON kind is absence, not a decoding failure"):
    assertEquals(domainPermissions("""{"is_admin":"yes"}""").isAdmin, false)

  test("a permissions body that is not JSON is a decoding failure at the root, never an escaping exception"):
    Json.decode[OrganizationPermissionsDto]("not json at all") match
      case Left(failure) => assertEquals(failure.path.render, "$")
      case Right(value)  => fail(s"expected a decoding failure, got $value")

  test("a permissions body that is the JSON literal null is rejected rather than becoming a null reference"):
    Json.decode[OrganizationPermissionsDto]("null") match
      case Left(failure) => assertEquals(failure.path.render, "$")
      case Right(value)  => fail(s"expected a decoding failure, got $value")

  // --- BlockedUser ----------------------------------------------------------

  test("a block entry decodes its two keys"):
    val entry = domainBlock("""{"block_id":41,"created_at":"2026-02-09T10:11:12Z"}""")

    assertEquals(entry.blockId.value, 41L)
    assertEquals(entry.createdAt, Some(Instant.parse("2026-02-09T10:11:12Z")))

  test("a block entry whose created_at is JSON null keeps its identifier and loses the timestamp"):
    val entry = domainBlock("""{"block_id":41,"created_at":null}""")

    assertEquals(entry.blockId.value, 41L)
    assertEquals(entry.createdAt, None)

  test("an absent created_at decodes identically to one sent as null"):
    assertEquals(domainBlock("""{"block_id":41}"""), domainBlock("""{"block_id":41,"created_at":null}"""))

  test("the Go zero-time sentinel is absence, not a timestamp in the year one"):
    assertEquals(domainBlock("""{"block_id":41,"created_at":"0001-01-01T00:00:00Z"}""").createdAt, None)

  test("a block entry without block_id fails, because nothing else tells two entries apart"):
    failureOf("""{"created_at":"2026-02-09T10:11:12Z"}""") match
      case (path, _) => assertEquals(path, "$.block_id")

  test("a block_id sent as null fails exactly as a missing one does"):
    assertEquals(failureOf("""{"block_id":null}""")._1, failureOf("{}")._1)

  test("a non-positive block_id is refused during conversion, at the field's own path"):
    val (path, message) = failureOf("""{"block_id":0}""")

    assertEquals(path, "$.block_id")
    assert(message.contains("at least 1"), s"the failure did not explain the bound: $message")

  test("a bad element of a block listing reports its own index"):
    val dtos = Json.decode[Vector[BlockedUserDto]]("""[{"block_id":1},{"block_id":0}]""") match
      case Right(decoded) => decoded
      case Left(failure)  => fail(s"the array did not decode: ${failure.path.render} ${failure.message}")

    BlockedUserDto.toDomainAll(JsonPath.Root, dtos) match
      case Left(failure) => assertEquals(failure.path.render, "$[1].block_id")
      case Right(value)  => fail(s"expected a conversion failure, got $value")

  test("an empty block listing is an empty vector, not a failure"):
    Json.decode[Vector[BlockedUserDto]]("[]").flatMap(dtos => BlockedUserDto.toDomainAll(JsonPath.Root, dtos)) match
      case Right(entries) => assertEquals(entries, Vector.empty[BlockedUser])
      case Left(failure)  => fail(s"an empty array failed: ${failure.path.render} ${failure.message}")

  // --- helpers --------------------------------------------------------------

  private def domainPermissions(body: String): OrganizationPermissions =
    Json.decode[OrganizationPermissionsDto](body).flatMap(_.toDomain) match
      case Right(permissions) => permissions
      case Left(failure)      => fail(s"$body did not decode: ${failure.path.render} ${failure.message}")

  private def domainBlock(body: String): BlockedUser =
    Json.decode[BlockedUserDto](body).flatMap(_.toDomain) match
      case Right(entry)  => entry
      case Left(failure) => fail(s"$body did not decode: ${failure.path.render} ${failure.message}")

  private def failureOf(body: String): (String, String) =
    Json.decode[BlockedUserDto](body).flatMap(_.toDomain) match
      case Left(failure) => (failure.path.render, failure.message)
      case Right(value)  => fail(s"expected a failure, got $value")
