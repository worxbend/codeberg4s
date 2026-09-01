package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.repositories.publishing.Topic

import munit.FunSuite

/** Decodes `golden/repository/topics.json` — a list endpoint whose body is an object, not an array. */
final class TopicNamesDtoSuite extends FunSuite with GoldenFixtures:

  test("the golden topics capture unwraps its envelope"):
    assertEquals(
      names(golden("repository/topics.json")),
      Vector("forge", "forgejo", "git", "self-hosted")
    )

  test("a repository with no topics decodes as an empty vector"):
    assertEquals(names("""{"topics": []}"""), Vector.empty[String])

  test("a null topics array decodes as empty, where a derived codec would abort"):
    assertEquals(names("""{"topics": null}"""), Vector.empty[String])

  test("an absent topics key decodes as empty"):
    assertEquals(names("""{}"""), Vector.empty[String])

  test("a body that is an array rather than the envelope is a decoding failure, not an exception"):
    assert(Json.decode[TopicNamesDto]("""["forge"]""").isLeft)

  test("a name that could not go back into a request path fails, reported inside the envelope"):
    decode("""{"topics": ["forge", "still/valid?no"]}""").toDomain match
      case Left(failure) => assertEquals(failure.path.render, "$.topics[1]")
      case Right(topics) => fail(s"expected a failure, converted $topics")

  /** The topic names as plain strings, so an assertion reads as the payload does. */
  private def names(body: String): Vector[String] =
    decode(body).toDomain match
      case Right(topics) => topics.map(_.value)
      case Left(failure) => fail(s"did not convert: ${failure.path.render} ${failure.message}")

  private def decode(body: String): TopicNamesDto =
    Json.decode[TopicNamesDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"did not decode: ${failure.path.render} ${failure.message}")
