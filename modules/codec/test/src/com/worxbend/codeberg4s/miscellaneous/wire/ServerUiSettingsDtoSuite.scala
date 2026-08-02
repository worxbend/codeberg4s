package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.miscellaneous.ServerUiSettings

import munit.FunSuite

/** `GET /settings/ui`, the fourth of the settings endpoints and the only one with no golden capture.
  *
  * '''Derived from `spec/swagger.v1.json`'s `GeneralUISettings` definition.'''
  */
final class ServerUiSettingsDtoSuite extends FunSuite:

  test("a full body decodes field for field"):
    assertEquals(
      Json.decode[ServerUiSettingsDto](ServerUiSettingsDtoSuite.FullBody),
      Right(ServerUiSettingsDto(Vector("+1", "heart"), Vector("codeberg"), Some("forgejo-auto"))),
    )

  test("the body converts to the domain"):
    assertEquals(
      Json.decode[ServerUiSettingsDto](ServerUiSettingsDtoSuite.FullBody).flatMap(_.toDomain),
      Right(ServerUiSettings(Vector("+1", "heart"), Vector("codeberg"), Some("forgejo-auto"))),
    )

  test("an absent key and an explicit null decode identically"):
    assertEquals(
      Json.decode[ServerUiSettingsDto]("""{}"""),
      Json.decode[ServerUiSettingsDto](
        """{"allowed_reactions":null,"custom_emojis":null,"default_theme":null}"""
      ),
    )

  test("an instance that reports nothing still converts, because no field is required"):
    assertEquals(
      Json.decode[ServerUiSettingsDto]("""{}""").flatMap(_.toDomain),
      Right(ServerUiSettings(Vector.empty, Vector.empty, None)),
    )

  test("silence about reactions is not permission"):
    assertEquals(ServerUiSettingsDto(Vector.empty, Vector.empty, None).toDomain.map(_.allows("+1")), Right(false))

  test("a reaction the instance listed is allowed, and one it did not is not"):
    val settings = ServerUiSettingsDto(Vector("+1", "heart"), Vector.empty, None).toDomain

    assertEquals(settings.map(_.allows("heart")), Right(true))
    assertEquals(settings.map(_.allows("rocket")), Right(false))

  test("a non-string element of an array is dropped rather than failing the whole body"):
    assertEquals(
      Json.decode[ServerUiSettingsDto]("""{"allowed_reactions":["+1",7,"heart"]}""").map(_.allowedReactions),
      Right(Vector("+1", "heart")),
    )

  test("an unknown key a future Forgejo adds does not break the decode"):
    assertEquals(
      Json.decode[ServerUiSettingsDto]("""{"default_theme":"gitea","new_knob":true}""").map(_.defaultTheme),
      Right(Some("gitea")),
    )

  test("a body that is not JSON is a DecodeFailure, not an exception"):
    assert(Json.decode[ServerUiSettingsDto]("<html>proxy error</html>").isLeft)

object ServerUiSettingsDtoSuite:

  private val FullBody: String =
    """{"allowed_reactions":["+1","heart"],"custom_emojis":["codeberg"],"default_theme":"forgejo-auto"}"""
