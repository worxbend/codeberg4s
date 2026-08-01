package com.worxbend.codeberg4s.wire

import com.worxbend.codeberg4s.ServerVersion
import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json

import munit.FunSuite

final class ServerVersionDtoSuite extends FunSuite with GoldenFixtures:

  test("the golden /version body decodes"):
    assertEquals(
      Json.decode[ServerVersionDto](golden("version/version.json")),
      Right(ServerVersionDto(Some("16.0.0-dev-668-1bdb1938+gitea-1.22.0"))),
    )

  test("the golden /version body converts to the domain verbatim"):
    assertEquals(
      Json.decode[ServerVersionDto](golden("version/version.json")).flatMap(_.toDomain),
      Right(ServerVersion("16.0.0-dev-668-1bdb1938+gitea-1.22.0")),
    )

  test("an absent version and a null version decode identically"):
    assertEquals(Json.decode[ServerVersionDto]("""{}"""), Json.decode[ServerVersionDto]("""{"version":null}"""))
    assertEquals(Json.decode[ServerVersionDto]("""{}"""), Right(ServerVersionDto(None)))

  test("an unknown key a future Forgejo adds does not break the decode"):
    assertEquals(
      Json.decode[ServerVersionDto]("""{"version":"1.2.3","build":"abcdef"}"""),
      Right(ServerVersionDto(Some("1.2.3"))),
    )

  test("an instance that will not name itself is a decoding failure at $.version"):
    ServerVersionDto(None).toDomain match
      case Left(failure) =>
        assertEquals(failure.path.render, "$.version")
        assert(failure.message.contains("version"), failure.message)
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("a garbage body is a DecodeFailure, not an exception"):
    assert(Json.decode[ServerVersionDto]("not json at all").isLeft)
    assert(Json.decode[ServerVersionDto]("""{"version":"1.2.3""").isLeft)
