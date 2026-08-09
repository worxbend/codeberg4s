package com.worxbend.codeberg4s.transport

import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.TransportCause
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.CodebergResponse
import com.worxbend.codeberg4s.core.RequestBody
import com.worxbend.codeberg4s.core.TransportFailure

import sttp.client4.GenericRequest
import sttp.client4.MultipartBody
import sttp.client4.testing.BackendStub
import sttp.client4.testing.RecordingBackend
import sttp.model.HeaderNames

import munit.FunSuite

import scala.concurrent.ExecutionContext
import scala.util.Success

import java.nio.charset.StandardCharsets

/** The body shapes core can express, and what each becomes on the wire.
  *
  * Forgejo is not uniform: `/markdown` consumes JSON while `/markdown/raw` consumes `text/plain`, and a release asset
  * is `multipart/form-data`. Getting the content type wrong produces a request the server half-ignores rather than one
  * it rejects, so each case asserts the header as well as the payload.
  */
final class RequestBodySuite extends FunSuite:

  private given ExecutionContext = ExecutionContext.parasitic

  private val config: CodebergConfig =
    CodebergConfig(Auth.Anonymous).copy(baseUri = BaseUri.Codeberg)

  private val Payload: Array[Byte] = "hi".getBytes(StandardCharsets.UTF_8)

  /** Sends `body` and reports both what the port answered and every request the backend actually saw.
    *
    * The backend list matters for the refusal case: a body the transport rejects must never reach a socket, and an
    * assertion on the answer alone would not notice a request that was sent and then reported as failed.
    */
  private def attempt(body: RequestBody): (Either[TransportFailure, CodebergResponse], List[GenericRequest[?, ?]]) =
    val recording = RecordingBackend(BackendStub.asynchronousFuture.whenAnyRequest.thenRespondOk())
    val port      = SttpHttpPort(recording, config)
    val request   = CodebergRequest(
      operation = "probe",
      method    = HttpMethod.Post,
      path      = List("probe"),
      query     = Nil,
      headers   = Nil,
      body      = Some(body),
    )
    port.send(request, "probe").value match
      case Some(Success(answer)) => (answer, recording.allInteractions.map(_._1))
      case other                 => fail(s"expected the send to have completed, got $other")

  private def send(body: RequestBody): GenericRequest[?, ?] =
    attempt(body) match
      case (_, request :: _) => request
      case (answer, Nil)     => fail(s"the backend saw no request; the port answered $answer")

  private def contentTypeOf(request: GenericRequest[?, ?]): Option[String] =
    request.header(HeaderNames.ContentType)

  test("a JSON body is sent as application/json"):
    val request = send(RequestBody.Json("""{"a":1}"""))

    assert(contentTypeOf(request).exists(_.startsWith("application/json")))

  test("a text body keeps the media type the caller chose"):
    val request = send(RequestBody.Text("# heading", RequestBody.TextMediaType))

    assertEquals(contentTypeOf(request), Some(RequestBody.TextMediaType))

  test("a text body is not wrapped in JSON quoting"):
    val request = send(RequestBody.Text("# heading", RequestBody.TextMediaType))

    assert(request.body.show.contains("# heading"))

  test("a binary body keeps the media type the caller chose"):
    val request = send(RequestBody.Binary("bytes".getBytes(StandardCharsets.UTF_8), "image/png"))

    assertEquals(contentTypeOf(request), Some("image/png"))

  test("a multipart body is sent as multipart/form-data"):
    val request =
      send(RequestBody.Multipart("attachment", "notes.txt", "hi".getBytes(StandardCharsets.UTF_8), "text/plain"))

    assert(contentTypeOf(request).exists(_.startsWith("multipart/form-data")))

  test("a multipart body names the field and file the endpoint expects"):
    // The boundary is sttp's to choose when it serialises, which is why the
    // transport never sets Content-Type for this case by hand — a boundary that
    // disagreed with the body sttp writes would be rejected as malformed.
    val request =
      send(RequestBody.Multipart("attachment", "notes.txt", "hi".getBytes(StandardCharsets.UTF_8), "text/plain"))

    request.body match
      case multipart: MultipartBody[?] =>
        assertEquals(multipart.parts.map(_.name).toList, List("attachment"))
        assertEquals(multipart.parts.map(_.fileName).toList, List(Some("notes.txt")))
      case other                       => fail(s"expected a multipart body, got ${other.show}")

  test("a multipart media type carrying a line break is refused, and no request reaches the backend"):
    // Defence in depth: UploadAttachment.as and UploadAsset.as already refuse
    // this, so getting here means a Multipart was built from a raw string. The
    // value would have become the part's own Content-Type header, and the CRLF
    // in it would have ended that header and opened one of the caller's
    // choosing.
    val (answer, seen) =
      attempt(RequestBody.Multipart("attachment", "notes.txt", Payload, "text/plain\r\nX-Injected: 1"))

    val expected: Either[TransportFailure, CodebergResponse] =
      Left(TransportFailure(TransportCause.Unknown(SttpHttpPort.UnsafeMultipartMediaType)))

    assertEquals(answer, expected)
    assert(seen.isEmpty, s"the request was sent anyway: $seen")

  test("a blank multipart media type is refused for the same reason"):
    val (answer, seen) = attempt(RequestBody.Multipart("attachment", "notes.txt", Payload, "   "))

    val expected: Either[TransportFailure, CodebergResponse] =
      Left(TransportFailure(TransportCause.Unknown(SttpHttpPort.UnsafeMultipartMediaType)))

    assertEquals(answer, expected)
    assert(seen.isEmpty, s"the request was sent anyway: $seen")

  test("an empty body sends no content"):
    val request = send(RequestBody.Empty)

    assertEquals(request.body.show, "string: ")
