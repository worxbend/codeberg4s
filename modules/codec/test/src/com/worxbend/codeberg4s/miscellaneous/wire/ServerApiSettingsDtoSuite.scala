package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.miscellaneous.ServerApiSettings

import munit.FunSuite

/** `golden/misc/settings-api.json` — the capture that documents the paging clamp the whole library is built around. */
final class ServerApiSettingsDtoSuite extends FunSuite with GoldenFixtures:

  test("the golden /settings/api body decodes field for field"):
    assertEquals(
      Json.decode[ServerApiSettingsDto](golden("misc/settings-api.json")),
      Right(ServerApiSettingsDto(Some(50L), Some(30L), Some(1000L), Some(10485760L))),
    )

  test("the golden body converts to the domain, clamp and all"):
    assertEquals(
      Json.decode[ServerApiSettingsDto](golden("misc/settings-api.json")).flatMap(_.toDomain),
      Right(ServerApiSettings(50L, 30L, Some(1000L), Some(10485760L))),
    )

  test("Codeberg's clamp is exactly the bound PageSize enforces"):
    val settings = domain(golden("misc/settings-api.json"))

    assertEquals(settings.maxResponseItems, 50L)
    assertEquals(settings.defaultPagingNum, 30L)

  test("an absent key and an explicit null decode identically"):
    assertEquals(
      Json.decode[ServerApiSettingsDto]("""{}"""),
      Json.decode[ServerApiSettingsDto](
        """{"max_response_items":null,"default_paging_num":null,
          |"default_git_trees_per_page":null,"default_max_blob_size":null}""".stripMargin
      ),
    )

  test("an instance that will not say what its response cap is fails at $.max_response_items"):
    ServerApiSettingsDto(None, Some(30L), None, None).toDomain match
      case Left(failure) => assertEquals(failure.path.render, "$.max_response_items")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("an instance that will not say what its default page size is fails at $.default_paging_num"):
    ServerApiSettingsDto(Some(50L), None, None, None).toDomain match
      case Left(failure) => assertEquals(failure.path.render, "$.default_paging_num")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("the two fields describing other endpoints stay optional"):
    assertEquals(
      ServerApiSettingsDto(Some(50L), Some(30L), None, None).toDomain,
      Right(ServerApiSettings(50L, 30L, None, None)),
    )

  test("an unknown key a future Forgejo adds does not break the decode"):
    assertEquals(
      Json.decode[ServerApiSettingsDto]("""{"max_response_items":25,"default_paging_num":10,"new_knob":true}"""),
      Right(ServerApiSettingsDto(Some(25L), Some(10L), None, None)),
    )

  test("a body that is not JSON is a DecodeFailure, not an exception"):
    assert(Json.decode[ServerApiSettingsDto]("<html>proxy error</html>").isLeft)

  private def domain(body: String): ServerApiSettings =
    Json.decode[ServerApiSettingsDto](body).flatMap(_.toDomain) match
      case Right(settings) => settings
      case Left(failure)   => fail(s"did not convert: ${failure.path.render} ${failure.message}")
