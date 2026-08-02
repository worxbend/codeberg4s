package com.worxbend.codeberg4s.repositories.publishing.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.GoldenFixtures
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.ReleaseAsset
import com.worxbend.codeberg4s.repositories.Tag
import com.worxbend.codeberg4s.repositories.wire.ReleaseAssetDto
import com.worxbend.codeberg4s.repositories.wire.TagDto

import munit.FunSuite

/** The two response shapes the publishing group is the first to receive at the '''top level''' of a body.
  *
  * `Tag` and `Attachment` are both already decoded by wave 2, but only ever as elements of an array or as a field of a
  * release. `POST /tags`, `GET /tags/{tag}` and the three single-asset endpoints send one of them as the whole body, so
  * the failure paths move from `$[2].name` to `$.name` and that is worth an assertion of its own.
  *
  * The payloads are not hand-written: each is lifted verbatim out of a golden capture with `ujson`, so what is decoded
  * here is exactly what Codeberg sent, only unwrapped. The pinned spec declares the same `Tag` and `Attachment`
  * definitions for these endpoints as for the ones that were captured, which is what makes the lift legitimate.
  */
final class PublishingResponsesSuite extends FunSuite with GoldenFixtures:

  test("one element of the golden tag listing decodes as a whole body"):
    val decoded = tag(firstOf("repository/tags-list.json"))

    assertEquals(decoded.name.value, "v15.0.6")
    assertEquals(decoded.commitSha.value, "5f7e2e5c003c066a865ea483e42809fa87d85eae")
    assertEquals(decoded.commit.map(_.sha.value), Some("5f7e2e5c003c066a865ea483e42809fa87d85eae"))
    assert(decoded.message.exists(_.nonEmpty), "the annotation of an annotated tag was lost")

  test("a tag body missing its name fails at the root, not at an index"):
    assertEquals(tagFailure("""{"id":"5f7e2e5c003c066a865ea483e42809fa87d85eae"}""").path.render, "$.name")

  test("a tag body whose id is not hexadecimal is rejected rather than forging a later path"):
    val failure = tagFailure("""{"name":"v1","id":"../../etc"}""")

    assertEquals(failure.path.render, "$.id")
    assertEquals(failure.message, "must be hexadecimal")

  test("an absent tag message and an explicit null decode identically"):
    assertEquals(tag("""{"name":"v1","id":"abcdef12"}""").message, None)
    assertEquals(tag("""{"name":"v1","id":"abcdef12","message":null}""").message, None)

  test("an absent commit and an explicit null decode identically"):
    assertEquals(tag("""{"name":"v1","id":"abcdef12"}""").commit, None)
    assertEquals(tag("""{"name":"v1","id":"abcdef12","commit":null}""").commit, None)

  test("one attachment of the golden release capture decodes as a whole body"):
    val decoded = asset(firstAssetOf("repository/release-latest.json"))

    assertEquals(decoded.id, 1730449L)
    assertEquals(decoded.name, "forgejo-16.0.2-linux-amd64")
    assertEquals(decoded.size, 119142664L)
    assertEquals(decoded.downloadCount, 705L)
    assertEquals(decoded.uuid, Some("49e93aa2-3684-4039-9404-c7215f353642"))

  test("an attachment body missing its id fails at the root"):
    assertEquals(assetFailure("""{"name":"checksums.txt"}""").path.render, "$.id")

  test("an attachment body missing its name fails at the root"):
    assertEquals(assetFailure("""{"id":1}""").path.render, "$.name")

  test("an absent size or download count is zero, never a failure"):
    val decoded = asset("""{"id":1,"name":"checksums.txt"}""")

    assertEquals((decoded.size, decoded.downloadCount), (0L, 0L))

  test("an absent created_at and an explicit null decode identically"):
    assertEquals(asset("""{"id":1,"name":"f"}""").createdAt, None)
    assertEquals(asset("""{"id":1,"name":"f","created_at":null}""").createdAt, None)

  test("Forgejo's zero-time sentinel on an attachment is absence, not an instant"):
    assertEquals(asset("""{"id":1,"name":"f","created_at":"0001-01-01T00:00:00Z"}""").createdAt, None)

  // --- harness --------------------------------------------------------------

  /** The first element of a golden array capture, re-serialised as a body of its own. */
  private def firstOf(fixture: String): String =
    ujson.read(golden(fixture)).arrOpt.flatMap(_.headOption) match
      case Some(element) => ujson.write(element)
      case None          => fail(s"$fixture is not a non-empty JSON array")

  /** The first element of a golden release capture's `assets`, re-serialised as a body of its own. */
  private def firstAssetOf(fixture: String): String =
    ujson.read(golden(fixture)).objOpt.flatMap(_.get("assets")).flatMap(_.arrOpt).flatMap(_.headOption) match
      case Some(element) => ujson.write(element)
      case None          => fail(s"$fixture carries no assets array")

  private def tag(body: String): Tag =
    Json.decode[TagDto](body).flatMap(_.toDomain) match
      case Right(value)  => value
      case Left(failure) => fail(s"expected a tag, got ${failure.path.render}: ${failure.message}")

  private def tagFailure(body: String): DecodeFailure =
    Json.decode[TagDto](body).flatMap(_.toDomain) match
      case Left(failure) => failure
      case Right(value)  => fail(s"expected a failure, got ${value.name.value}")

  private def asset(body: String): ReleaseAsset =
    Json.decode[ReleaseAssetDto](body).flatMap(_.toDomainAt(JsonPath.Root)) match
      case Right(value)  => value
      case Left(failure) => fail(s"expected an attachment, got ${failure.path.render}: ${failure.message}")

  private def assetFailure(body: String): DecodeFailure =
    Json.decode[ReleaseAssetDto](body).flatMap(_.toDomainAt(JsonPath.Root)) match
      case Left(failure) => failure
      case Right(value)  => fail(s"expected a failure, got ${value.name}")
