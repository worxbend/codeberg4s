package com.worxbend.codeberg4s.organizations.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.organizations.OrganizationPermissions

import munit.FunSuite

/** `OrganizationPermissions` on the wire.
  *
  * '''The payload is derived from `spec/swagger.v1.json`, not captured.''' `GET /users/{u}/orgs/{org}/permissions`
  * needs a token and the golden harvest was anonymous, so the bodies below were written by hand to match the spec's
  * definition and are not evidence that Forgejo sends exactly this.
  *
  * The block-list entries `GET /orgs/{org}/list_blocked` returns are the same `BlockedUser` model `/user/list_blocked`
  * sends, and are tested once, in [[com.worxbend.codeberg4s.users.social.wire.SocialDtoSuite]].
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

  // --- helpers --------------------------------------------------------------

  private def domainPermissions(body: String): OrganizationPermissions =
    Json.decode[OrganizationPermissionsDto](body).flatMap(_.toDomain) match
      case Right(permissions) => permissions
      case Left(failure)      => fail(s"$body did not decode: ${failure.path.render} ${failure.message}")
