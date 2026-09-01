package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{Json, WireModel}
import com.worxbend.codeberg4s.repositories.actions.ActionArtifact

import munit.FunSuite

import java.time.Instant

/** Decoding an `ActionArtifact`.
  *
  * '''Payloads written by hand from `spec/swagger.v1.json`, not captured'''; see [[ActionArtifactDto]].
  */
final class ActionArtifactDtoSuite extends FunSuite:

  test("a full artifact decodes field for field"):
    val dto = decode(ActionArtifactDtoSuite.FullBody)

    assertEquals(dto.id, Some(881L))
    assertEquals(dto.name, Some("coverage"))
    assertEquals(dto.sizeInBytes, Some(2048L))
    assertEquals(dto.expired, Some(false))
    assertEquals(dto.runId, Some(4711L))
    assertEquals(dto.archiveDownloadUrl, Some("https://forge.example/api/v1/repos/o/r/actions/artifacts/881/zip"))

  test("a full artifact converts, timestamps included"):
    val artifact = domain(ActionArtifactDtoSuite.FullBody)

    assertEquals(artifact.id.value, 881L)
    assertEquals(artifact.runId.map(_.value), Some(4711L))
    assertEquals(artifact.isExpired, false)
    assertEquals(artifact.createdAt, Some(Instant.parse("2026-07-30T19:14:15Z")))
    assertEquals(artifact.expiresAt, Some(Instant.parse("2026-08-29T19:14:15Z")))

  test("an expired artifact says so"):
    assertEquals(domain("""{"id":881,"expired":true}""").isExpired, true)

  test("an artifact whose expiry the instance did not report is not treated as expired"):
    assertEquals(domain("""{"id":881}""").isExpired, false)

  test("an artifact without an id cannot be converted"):
    assertEquals(failurePath("""{"name":"coverage"}"""), Some("$.id"))

  test("an artifact whose id is not a positive identifier cannot be converted"):
    assertEquals(failurePath("""{"id":0}"""), Some("$.id"))

  test("a run_id that is not a positive identifier costs the link, not the artifact"):
    assertEquals(domain("""{"id":881,"run_id":0}""").runId, None)

  test("JSON null and an absent key decode identically for every field"):
    assertEquals(decode(ActionArtifactDtoSuite.NullBody), decode("""{"id":881}"""))

  test("a bad element of an artifact array reports its own position"):
    val dtos = Json.decode[Vector[ActionArtifactDto]]("""[{"id":1},{"name":"no id"}]""") match
      case Right(decoded) => decoded
      case Left(failure)  => fail(s"the array did not decode: ${failure.message}")

    assertEquals(
      WireModel.all(JsonPath.Root, dtos).swap.toOption.map(_.path.render),
      Some("$[1].id"),
    )

  private def decode(body: String): ActionArtifactDto =
    Json.decode[ActionArtifactDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

  private def domain(body: String): ActionArtifact =
    decode(body).toDomain match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

  private def failurePath(body: String): Option[String] =
    decode(body).toDomain.swap.toOption.map(_.path.render)

/** The payloads this suite decodes, shaped to the spec's `ActionArtifact` definition. */
object ActionArtifactDtoSuite:

  private val FullBody: String =
    """{
      |  "id": 881,
      |  "name": "coverage",
      |  "size_in_bytes": 2048,
      |  "expired": false,
      |  "archive_download_url": "https://forge.example/api/v1/repos/o/r/actions/artifacts/881/zip",
      |  "run_id": 4711,
      |  "created_at": "2026-07-30T21:14:15+02:00",
      |  "updated_at": "2026-07-30T21:14:16+02:00",
      |  "expires_at": "2026-08-29T21:14:15+02:00"
      |}""".stripMargin

  private val NullBody: String =
    """{
      |  "id": 881,
      |  "name": null,
      |  "size_in_bytes": null,
      |  "expired": null,
      |  "archive_download_url": null,
      |  "run_id": null,
      |  "created_at": null,
      |  "updated_at": null,
      |  "expires_at": null
      |}""".stripMargin
