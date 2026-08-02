package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.issues.Reaction

import munit.FunSuite

import java.time.Instant

/** [[ReactionDto]] against payloads written by hand from `spec/swagger.v1.json`.
  *
  * '''No golden fixture contains a reaction''' — the harvest was anonymous and none of the captured issues or comments
  * carried one — so these payloads are the spec's `Reaction` definition read literally.
  *
  * Two properties are under test: the `docs/HAZARDS.md` §1 rule that present, JSON `null` and absent decode to the same
  * three answers; and that the reaction vocabulary is '''open''', because the spec declares `content` as a bare string
  * with no `enum`.
  */
final class ReactionDtoSuite extends FunSuite:

  private val Full: String =
    """{
      |  "content": "+1",
      |  "user": {"id": 532348, "login": "jkassel"},
      |  "created_at": "2026-07-31T17:20:04+02:00"
      |}""".stripMargin

  private val Nulled: String = """{"content": "+1", "user": null, "created_at": null}"""

  private val Minimal: String = """{"content": "+1"}"""

  test("every declared field decodes when present"):
    val reaction = decoded(Full)

    assertEquals(reaction.content.value, "+1")
    assertEquals(reaction.user.map(_.login), Some("jkassel"))
    assertEquals(reaction.createdAt, Some(Instant.parse("2026-07-31T15:20:04Z")))

  test("JSON null and an absent key decode identically, for every optional field"):
    assertEquals(decoded(Nulled), decoded(Minimal))

  test("a reaction with no account is not a failure, because the model does not require one"):
    val reaction = decoded(Minimal)

    assertEquals(reaction.user, None)
    assertEquals(reaction.createdAt, None)

  test("the vocabulary is open: a custom emoji shortcode decodes exactly like a documented one"):
    assertEquals(decoded("""{"content":"rocket"}""").content.value, "rocket")
    assertEquals(decoded("""{"content":"🎉"}""").content.value, "🎉")

  test("the content is trimmed, so a padded value is still usable as an argument to the removal call"):
    assertEquals(decoded("""{"content":"  +1  "}""").content.value, "+1")

  test("a reaction with no content is a decoding failure at $.content"):
    failureAt("""{"user":{"id":1,"login":"a"}}""", "$.content")

  test("a blank content is a decoding failure at $.content too, never a silently empty reaction"):
    failureAt("""{"content":"   "}""", "$.content")

  test("a failure inside the account is reported at $.user, not at the reaction"):
    failureAt("""{"content":"+1","user":{"login":"nobody"}}""", "$.user.id")

  test("a failing element of a listing reports its position, not the array's"):
    val decoded = Json
      .decode[Vector[ReactionDto]]("""[{"content":"+1"},{"user":{"id":1,"login":"a"}}]""")
      .flatMap(dtos => ReactionDto.toDomainAll(JsonPath.Root, dtos))

    decoded match
      case Left(failure) => assertEquals(failure.path.render, "$[1].content")
      case Right(value)  => fail(s"expected a failure, converted $value")

  private def decoded(body: String): Reaction =
    Json.decode[ReactionDto](body).flatMap(_.toDomain) match
      case Right(value)  => value
      case Left(failure) => fail(s"could not decode the reaction: $failure")

  private def failureAt(body: String, path: String): Unit =
    Json.decode[ReactionDto](body).flatMap(_.toDomain) match
      case Left(failure) => assertEquals(failure.path.render, path)
      case Right(value)  => fail(s"expected a failure, converted $value")
