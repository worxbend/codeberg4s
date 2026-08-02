package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.issues.TrackedTime

import munit.FunSuite

import scala.concurrent.duration.DurationInt

import java.time.Instant

/** [[TrackedTimeDto]] against payloads written by hand from `spec/swagger.v1.json`.
  *
  * '''No golden fixture covers timetracking''' — every endpoint requires a token and the harvest was anonymous — so
  * these payloads are the spec's `TrackedTime` definition read literally.
  *
  * The property that matters most here is the unit boundary: `time` is `int64` seconds on the wire and a
  * `FiniteDuration` in the domain, and the conversion happens exactly once.
  */
final class TrackedTimeDtoSuite extends FunSuite:

  private val Full: String =
    """{
      |  "id": 474,
      |  "issue": {"id": 1, "number": 2966, "title": "Bye", "state": "open"},
      |  "issue_id": 1,
      |  "time": 7200,
      |  "user_id": 532348,
      |  "user_name": "jkassel",
      |  "created": "2026-07-31T17:20:04+02:00"
      |}""".stripMargin

  private val Nulled: String =
    """{"id": 474, "issue": null, "issue_id": null, "time": 7200, "user_id": null,
      | "user_name": null, "created": null}""".stripMargin

  private val Minimal: String = """{"id": 474, "time": 7200}"""

  test("every declared field decodes when present"):
    val entry = decoded(Full)

    assertEquals(entry.id.value, 474L)
    assertEquals(entry.issue.map(_.number.value), Some(2966L))
    assertEquals(entry.spent, 2.hours)
    assertEquals(entry.userName, Some("jkassel"))
    assertEquals(entry.createdAt, Some(Instant.parse("2026-07-31T15:20:04Z")))

  test("JSON null and an absent key decode identically, for every optional field"):
    assertEquals(decoded(Nulled), decoded(Minimal))

  test("seconds on the wire become a duration in the domain, and nothing else does the conversion"):
    assertEquals(decoded("""{"id":1,"time":90}""").spent, 90.seconds)
    assertEquals(decoded("""{"id":1,"time":0}""").spent, 0.seconds)

  test("a negative entry decodes, because Forgejo records a correction as one"):
    assertEquals(decoded("""{"id":1,"time":-1800}""").spent, -30.minutes)

  test("the deprecated issue_id and user_id are read off the wire and deliberately not carried into the domain"):
    val dto = Json.decode[TrackedTimeDto](Full) match
      case Right(value)  => value
      case Left(failure) => fail(s"could not decode the entry: $failure")

    assertEquals(dto.issueId, Some(1L))
    assertEquals(dto.userId, Some(532348L))

  test("an entry with no id is a decoding failure at $.id"):
    failureAt("""{"time":7200}""", "$.id")

  test("an entry with no time is a decoding failure at $.time, never a silent zero"):
    failureAt("""{"id":474}""", "$.time")

  test("a failure inside the embedded issue is reported at $.issue, not at the entry"):
    failureAt("""{"id":1,"time":60,"issue":{"id":1,"title":"t","state":"open"}}""", "$.issue.number")

  test("a failing element of a listing reports its position, not the array's"):
    val decoded = Json
      .decode[Vector[TrackedTimeDto]]("""[{"id":1,"time":60},{"id":2}]""")
      .flatMap(dtos => TrackedTimeDto.toDomainAll(JsonPath.Root, dtos))

    decoded match
      case Left(failure) => assertEquals(failure.path.render, "$[1].time")
      case Right(value)  => fail(s"expected a failure, converted $value")

  private def decoded(body: String): TrackedTime =
    Json.decode[TrackedTimeDto](body).flatMap(_.toDomain) match
      case Right(value)  => value
      case Left(failure) => fail(s"could not decode the entry: $failure")

  private def failureAt(body: String, path: String): Unit =
    Json.decode[TrackedTimeDto](body).flatMap(_.toDomain) match
      case Left(failure) => assertEquals(failure.path.render, path)
      case Right(value)  => fail(s"expected a failure, converted $value")
