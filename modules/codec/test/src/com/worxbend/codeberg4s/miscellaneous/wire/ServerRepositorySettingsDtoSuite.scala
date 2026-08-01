package com.worxbend.codeberg4s.miscellaneous.wire

import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.miscellaneous.ServerRepositorySettings

import munit.FunSuite

/** `golden/misc/settings-repository.json` — seven keys, all `false` on Codeberg. */
final class ServerRepositorySettingsDtoSuite extends FunSuite with GoldenFixtures:

  test("the golden /settings/repository body decodes field for field"):
    assertEquals(
      Json.decode[ServerRepositorySettingsDto](golden("misc/settings-repository.json")),
      Right(
        ServerRepositorySettingsDto(
          mirrorsDisabled      = Some(false),
          httpGitDisabled      = Some(false),
          migrationsDisabled   = Some(false),
          starsDisabled        = Some(false),
          forksDisabled        = Some(false),
          timeTrackingDisabled = Some(false),
          lfsDisabled          = Some(false),
        )
      ),
    )

  test("Codeberg disables none of the seven features"):
    assertEquals(
      Json.decode[ServerRepositorySettingsDto](golden("misc/settings-repository.json")).flatMap(_.toDomain),
      Right(ServerRepositorySettings(false, false, false, false, false, false, false)),
    )

  test("an instance that disables a feature says so, and only that feature changes"):
    assertEquals(
      Json
        .decode[ServerRepositorySettingsDto]("""{"forks_disabled":true,"lfs_disabled":true}""")
        .flatMap(_.toDomain),
      Right(
        ServerRepositorySettings(
          mirrorsDisabled      = false,
          httpGitDisabled      = false,
          migrationsDisabled   = false,
          starsDisabled        = false,
          forksDisabled        = true,
          timeTrackingDisabled = false,
          lfsDisabled          = true,
        )
      ),
    )

  test("an empty object is an instance that disables nothing, not a decoding failure"):
    assertEquals(
      Json.decode[ServerRepositorySettingsDto]("""{}""").flatMap(_.toDomain),
      Right(ServerRepositorySettings(false, false, false, false, false, false, false)),
    )

  test("an absent key and an explicit null decode identically"):
    assertEquals(
      Json.decode[ServerRepositorySettingsDto]("""{}"""),
      Json.decode[ServerRepositorySettingsDto]("""{"forks_disabled":null,"lfs_disabled":null}"""),
    )

  test("a body that is not JSON is a DecodeFailure, not an exception"):
    assert(Json.decode[ServerRepositorySettingsDto]("404 page not found").isLeft)
