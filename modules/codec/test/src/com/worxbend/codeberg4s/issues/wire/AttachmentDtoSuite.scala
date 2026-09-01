package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{Json, WireModel}
import com.worxbend.codeberg4s.issues.AttachmentKind

import munit.FunSuite

import java.time.Instant

/** [[AttachmentDto]] against payloads written by hand from `spec/swagger.v1.json`.
  *
  * '''No golden fixture covers an attachment.''' The harvest was anonymous, `assets` is `[]` on all thirteen captured
  * issues and both captured comments, and uploading needs a token — so the payloads here are the spec's `Attachment`
  * definition read literally, and they are evidence about this decoder rather than about Forgejo.
  *
  * The property under test is the one `docs/HAZARDS.md` §1 makes non-negotiable: for every optional field, '''present,
  * JSON `null` and absent''' must decode to the same three answers, with `null` and absent indistinguishable.
  */
final class AttachmentDtoSuite extends FunSuite:

  private val Full: String =
    """{
      |  "id": 88,
      |  "name": "failing-run.txt",
      |  "size": 1024,
      |  "download_count": 3,
      |  "type": "attachment",
      |  "uuid": "3a1c",
      |  "browser_download_url": "https://forge.example/attachments/3a1c",
      |  "created_at": "2026-07-31T17:20:04+02:00"
      |}""".stripMargin

  private val Nulled: String =
    """{
      |  "id": 88,
      |  "name": "failing-run.txt",
      |  "size": null,
      |  "download_count": null,
      |  "type": null,
      |  "uuid": null,
      |  "browser_download_url": null,
      |  "created_at": null
      |}""".stripMargin

  private val Minimal: String = """{"id": 88, "name": "failing-run.txt"}"""

  test("every declared field decodes when present"):
    val attachment = decoded(Full)

    assertEquals(attachment.id.value, 88L)
    assertEquals(attachment.name, "failing-run.txt")
    assertEquals(attachment.size, 1024L)
    assertEquals(attachment.downloadCount, 3L)
    assertEquals(attachment.kind, Some(AttachmentKind.Uploaded))
    assertEquals(attachment.uuid, Some("3a1c"))
    assertEquals(attachment.browserDownloadUrl, Some("https://forge.example/attachments/3a1c"))
    assertEquals(attachment.createdAt, Some(Instant.parse("2026-07-31T15:20:04Z")))

  test("JSON null and an absent key decode identically, for every optional field"):
    assertEquals(decoded(Nulled), decoded(Minimal))

  test("an absent size or download count is zero, which is not the same as an empty file"):
    val attachment = decoded(Minimal)

    assertEquals(attachment.size, 0L)
    assertEquals(attachment.downloadCount, 0L)
    assertEquals(attachment.kind, None)
    assertEquals(attachment.uuid, None)
    assertEquals(attachment.browserDownloadUrl, None)
    assertEquals(attachment.createdAt, None)

  test("the external kind is recognised, and an unknown one is kept verbatim rather than dropped"):
    assertEquals(decoded("""{"id":1,"name":"a","type":"external"}""").kind, Some(AttachmentKind.External))
    assertEquals(decoded("""{"id":1,"name":"a","type":"lfs"}""").kind, Some(AttachmentKind.Other("lfs")))

  test("the kind is matched case-insensitively after trimming, as the rest of this library matches wire words"):
    assertEquals(decoded("""{"id":1,"name":"a","type":"  ATTACHMENT "}""").kind, Some(AttachmentKind.Uploaded))

  test("an attachment with no id is a decoding failure at $.id"):
    failureAt("""{"name":"failing-run.txt"}""", "$.id")

  test("a non-positive id is a decoding failure at $.id too, not a request for /assets/0"):
    failureAt("""{"id":0,"name":"failing-run.txt"}""", "$.id")

  test("an attachment with no name is a decoding failure at $.name"):
    failureAt("""{"id":88}""", "$.name")

  test("a failing element of a listing reports its position, not the array's"):
    val decoded = Json
      .decode[Vector[AttachmentDto]]("""[{"id":1,"name":"a"},{"id":2}]""")
      .flatMap(dtos => WireModel.all(JsonPath.Root, dtos))

    decoded match
      case Left(failure) => assertEquals(failure.path.render, "$[1].name")
      case Right(value)  => fail(s"expected a failure, converted $value")

  test("a key the DTO does not model does not break the decode"):
    assertEquals(decoded("""{"id":88,"name":"a","download_url":"elsewhere"}""").id.value, 88L)

  private def decoded(body: String): com.worxbend.codeberg4s.issues.IssueAttachment =
    Json.decode[AttachmentDto](body).flatMap(_.toDomain) match
      case Right(value)  => value
      case Left(failure) => fail(s"could not decode the attachment: $failure")

  private def failureAt(body: String, path: String): Unit =
    Json.decode[AttachmentDto](body).flatMap(_.toDomain) match
      case Left(failure) => assertEquals(failure.path.render, path)
      case Right(value)  => fail(s"expected a failure, converted $value")
