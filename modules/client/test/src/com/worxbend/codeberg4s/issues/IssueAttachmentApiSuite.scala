package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.CodebergError

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend

import munit.FunSuite

import scala.concurrent.Future

import java.nio.charset.StandardCharsets
import java.time.Instant

/** [[IssueAttachmentApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * '''The payloads here are hand-written to match `spec/swagger.v1.json`, not captured.''' No golden fixture contains
  * an issue or comment attachment; see the class note on [[IssueAttachmentApi]]. What is asserted is the wiring — the
  * two path families, the multipart part, the query parameters and the retry decisions — none of which depends on the
  * response being real.
  */
final class IssueAttachmentApiSuite extends FunSuite with IssueLaneHarness:

  private val Attachment: AttachmentId = orFail(AttachmentId.from(88L))

  private val AttachmentBody: String =
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

  private def upload: UploadAttachment =
    orFail(UploadAttachment.of("failing-run.txt", "log".getBytes(StandardCharsets.UTF_8)))

  test("an issue's attachment listing targets /issues/{index}/assets and sends no paging, because none is declared"):
    val backend = RecordingBackend(responding(200, s"[$AttachmentBody]"))

    onApi(backend): api =>
      api.listForIssue(Handle, Name, Number).map: attachments =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/assets")
        assertEquals(queryOf(backend), Nil)
        assertEquals(attachments.map(_.name), Vector("failing-run.txt"))

  test("an upload POSTs a multipart body, not JSON"):
    val backend = RecordingBackend(responding(201, AttachmentBody))

    onApi(backend): api =>
      api.uploadToIssue(Handle, Name, Number, upload).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assert(bodyOf(backend).startsWith("multipart"), s"expected a multipart body, got ${bodyOf(backend)}")

  test("an upload puts name and updated_at in the query string, which is where the spec declares them"):
    val backend = RecordingBackend(responding(201, AttachmentBody))
    val command = upload.named("build.log").recordedAt(Instant.parse("2026-07-01T00:00:00Z"))

    onApi(backend): api =>
      api.uploadToIssue(Handle, Name, Number, command).map: _ =>
        assertEquals(queryOf(backend), List("name" -> "build.log", "updated_at" -> "2026-07-01T00:00:00Z"))

  test("an upload with neither optional setting sends an empty query string"):
    val backend = RecordingBackend(responding(201, AttachmentBody))

    onApi(backend): api =>
      api.uploadToIssue(Handle, Name, Number, upload).map(_ => assertEquals(queryOf(backend), Nil))

  test("an upload is never retried, because a repeat would attach the file twice"):
    val backend = RecordingBackend(flakyThen(201, AttachmentBody))

    onApi(backend): api =>
      api.attempt
        .uploadToIssue(Handle, Name, Number, upload)
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on an upload must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("a single-attachment read on an issue addresses it under the issue's assets"):
    val backend = RecordingBackend(responding(200, AttachmentBody))

    onApi(backend): api =>
      api.getOnIssue(Handle, Name, Number, Attachment).map: attachment =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966/assets/88")
        assertEquals(attachment.kind, Some(AttachmentKind.Uploaded))
        assertEquals(attachment.size, 1024L)

  test("an edit PATCHes only the fields the caller set, so an ordinary rename never mentions the download URL"):
    val backend = RecordingBackend(responding(201, AttachmentBody))

    onApi(backend): api =>
      api
        .editOnIssue(Handle, Name, Number, Attachment, EditAttachment.Empty.renamedTo("build.log"))
        .map: _ =>
          assertEquals(methodOf(backend), "PATCH")
          assertEquals(bodyOf(backend), """{"name":"build.log"}""")

  test("an edit is never retried, because a repeat could overwrite somebody else's rename"):
    val backend = RecordingBackend(flakyThen(201, AttachmentBody))

    onApi(backend): api =>
      api.attempt
        .editOnIssue(Handle, Name, Number, Attachment, EditAttachment.Empty.renamedTo("build.log"))
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the PATCH was retried"))

  test("a delete is retried, because it names one attachment row id"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.deleteOnIssue(Handle, Name, Number, Attachment).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(backend.allInteractions.size, 2, "the 503 on a delete was not retried")

  test("a comment's attachments hang off the comment, with no issue number in the path"):
    val backend = RecordingBackend(responding(200, s"[$AttachmentBody]"))

    onApi(backend): api =>
      api
        .listForComment(Handle, Name, CommentRef)
        .map: _ =>
          assertEquals(
            pathOf(backend),
            "https://forge.example/api/v1/repos/Codeberg/Community/issues/comments/20366420/assets",
          )

  test("a single-attachment read on a comment addresses it under the comment's assets"):
    val backend = RecordingBackend(responding(200, AttachmentBody))

    onApi(backend): api =>
      api
        .getOnComment(Handle, Name, CommentRef, Attachment)
        .map: _ =>
          assertEquals(
            pathOf(backend),
            "https://forge.example/api/v1/repos/Codeberg/Community/issues/comments/20366420/assets/88",
          )

  test("a comment upload uses the same multipart shape and the same query parameters"):
    val backend = RecordingBackend(responding(201, AttachmentBody))

    onApi(backend): api =>
      api.uploadToComment(Handle, Name, CommentRef, upload.named("build.log")).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(queryOf(backend), List("name" -> "build.log"))

  test("a comment edit and a comment delete reach the same attachment by different methods"):
    val edited  = RecordingBackend(responding(201, AttachmentBody))
    val deleted = RecordingBackend(responding(204, ""))

    for
      _ <- onApi(edited)(_.editOnComment(Handle, Name, CommentRef, Attachment, EditAttachment.Empty))
      _ <- onApi(deleted)(_.deleteOnComment(Handle, Name, CommentRef, Attachment))
    yield
      assertEquals(methodOf(edited), "PATCH")
      assertEquals(bodyOf(edited), "{}")
      assertEquals(methodOf(deleted), "DELETE")

  test("a 413 reaches both rails as the very same failure, which is what an oversized upload produces"):
    onApi(responding(413, IssueAttachmentApiSuite.TooLargeBody)): api =>
      for
        raised <- api.uploadToIssue(Handle, Name, Number, upload).failed
        typed  <- api.attempt.uploadToIssue(Handle, Name, Number, upload)
      yield assertRailsAgree(raised, typed)

  test("a 413 carries the upload operation id, so an alert can name the endpoint"):
    onApi(responding(413, IssueAttachmentApiSuite.TooLargeBody)): api =>
      api.attempt.uploadToIssue(Handle, Name, Number, upload).map:
        case Left(CodebergError.Api(ctx, status, _, _)) =>
          assertEquals(ctx.operation, IssueAttachmentApi.UploadToIssueOperation)
          assertEquals(status, 413)
        case other                                      => fail(s"expected an Api failure, got $other")

  test("a 200 whose payload has no name becomes DecodingFailed at $.name"):
    onApi(responding(200, """{"id":88}""")): api =>
      api.attempt.getOnIssue(Handle, Name, Number, Attachment).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.name")
        case other                                             => fail(s"expected a decoding failure, got $other")

  private def onApi[A](backend: Backend[Future])(use: IssueAttachmentApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(IssueAttachmentApi(pipeline)))

/** The error bodies this suite stubs. */
object IssueAttachmentApiSuite:

  private val TooLargeBody: String =
    """{"message":"UploadAttachment","url":"https://codeberg.org/api/swagger","errors":["file too large"]}"""
