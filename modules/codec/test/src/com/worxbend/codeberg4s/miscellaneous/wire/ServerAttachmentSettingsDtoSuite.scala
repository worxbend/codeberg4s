package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.miscellaneous.ServerAttachmentSettings

import munit.FunSuite

/** `golden/misc/settings-attachment.json` — where the comma-separated `allowed_types` string is turned into a list. */
final class ServerAttachmentSettingsDtoSuite extends FunSuite with GoldenFixtures:

  test("the golden /settings/attachment body decodes field for field"):
    assertEquals(
      Json.decode[ServerAttachmentSettingsDto](golden("misc/settings-attachment.json")),
      Right(ServerAttachmentSettingsDto(Some(true), Some(ServerAttachmentSettings.AnyType), Some(100L), Some(20L))),
    )

  test("Codeberg accepts any content type, up to 100 MiB and 20 files"):
    val settings = domain(golden("misc/settings-attachment.json"))

    assertEquals(settings.enabled, true)
    assertEquals(settings.allowedTypes, Vector(ServerAttachmentSettings.AnyType))
    assertEquals(settings.acceptsAnyType, true)
    assertEquals(settings.maxSizeMib, Some(100L))
    assertEquals(settings.maxFiles, Some(20L))

  test("a comma-separated allowed_types becomes one entry per type, trimmed"):
    val settings = domain("""{"enabled":true,"allowed_types":"image/png, image/gif ,.pdf,"}""")

    assertEquals(settings.allowedTypes, Vector("image/png", "image/gif", ".pdf"))
    assertEquals(settings.acceptsAnyType, false)

  test("an instance that reports no attachment settings is treated as accepting no upload"):
    val settings = domain("""{}""")

    assertEquals(settings.enabled, false)
    assertEquals(settings.allowedTypes, Vector.empty[String])
    assertEquals(settings.maxSizeMib, None)
    assertEquals(settings.maxFiles, None)

  test("an absent key and an explicit null decode identically"):
    assertEquals(
      Json.decode[ServerAttachmentSettingsDto]("""{}"""),
      Json.decode[ServerAttachmentSettingsDto]("""{"enabled":null,"allowed_types":null,"max_size":null}"""),
    )

  test("a body that is not JSON is a DecodeFailure, not an exception"):
    assert(Json.decode[ServerAttachmentSettingsDto]("not json").isLeft)

  private def domain(body: String): ServerAttachmentSettings =
    Json.decode[ServerAttachmentSettingsDto](body).flatMap(_.toDomain) match
      case Right(settings) => settings
      case Left(failure)   => fail(s"did not convert: ${failure.path.render} ${failure.message}")
