package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json

import munit.FunSuite

/** Decodes `golden/repository/topics.json` — a list endpoint whose body is an object, not an array. */
final class TopicNamesDtoSuite extends FunSuite with GoldenFixtures:

  test("the golden topics capture unwraps its envelope"):
    assertEquals(decode(golden("repository/topics.json")).toDomain, Vector("forge", "forgejo", "git", "self-hosted"))

  test("a repository with no topics decodes as an empty vector"):
    assertEquals(decode("""{"topics": []}""").toDomain, Vector.empty[String])

  test("a null topics array decodes as empty, where a derived codec would abort"):
    assertEquals(decode("""{"topics": null}""").toDomain, Vector.empty[String])

  test("an absent topics key decodes as empty"):
    assertEquals(decode("""{}""").toDomain, Vector.empty[String])

  test("a body that is an array rather than the envelope is a decoding failure, not an exception"):
    assert(Json.decode[TopicNamesDto]("""["forge"]""").isLeft)

  private def decode(body: String): TopicNamesDto =
    Json.decode[TopicNamesDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"did not decode: ${failure.path.render} ${failure.message}")
