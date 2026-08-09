package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.ApiErrorBody
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.core.Exec.Result

import munit.FunSuite

import java.nio.charset.StandardCharsets

/** [[ApiPipeline.callBinary]] — the path taken by the two endpoints that answer a ZIP.
  *
  * Run with `F = Either`, so retry and telemetry are observed without a scheduler or a real millisecond.
  */
final class BinaryPipelineSuite extends FunSuite:

  private given JitterSource = JitterSource.Deterministic

  private val config: CodebergConfig = CodebergConfig(Auth.Anonymous)

  private val zip: Array[Byte] = Array[Byte](0x50, 0x4B, 0x03, 0x04, 0x00)

  private val request: CodebergRequest =
    CodebergRequest("actions.artifacts.download", HttpMethod.Get, List("repos", "o", "r"), Nil, Nil, None)

  private final class StubBinaryPort(responses: List[Either[TransportFailure, BinaryResponse]])
      extends BinaryHttpPort[Result]:
    private var remaining: List[Either[TransportFailure, BinaryResponse]] = responses

    def sendBinary(request: CodebergRequest, redactedUri: String): Result[Either[TransportFailure, BinaryResponse]] =
      remaining match
        case head :: tail => remaining = tail; Right(head)
        case Nil          => Right(Left(TransportFailure(com.worxbend.codeberg4s.TransportCause.Unknown("exhausted"))))

    def calls: Int = responses.length - remaining.length

  private def pipeline(telemetry: Telemetry[Result]): ApiPipeline[Result] =
    ApiPipeline[Result](
      FakeHttpPort(Vector(Right(CodebergResponse(200, Map.empty, ResponseBody.Empty)))),
      config,
      FakeTimer(0L),
      telemetry,
      _ => ApiErrorBody.Empty,
    )

  private def ok(bytes: Array[Byte]): BinaryResponse =
    BinaryResponse(200, Map("content-type" -> List("application/zip")), bytes)

  test("a successful download returns the bytes verbatim"):
    val port   = StubBinaryPort(List(Right(ok(zip))))
    val result = pipeline(Telemetry.noOp[Result]).callBinary(request, port)

    assertEquals(result.map(_.bytes.toList), Right(zip.toList))

  test("the response headers reach the caller"):
    val port   = StubBinaryPort(List(Right(ok(zip))))
    val result = pipeline(Telemetry.noOp[Result]).callBinary(request, port)

    assertEquals(result.map(_.contentType), Right(Some("application/zip")))

  test("a successful body is never decoded, so arbitrary bytes survive"):
    // 0xFF 0xFE is not valid UTF-8. Round-tripping it through a String would
    // replace it, which is exactly why this path exists.
    val raw    = Array[Byte](-1, -2, 0, 65)
    val port   = StubBinaryPort(List(Right(ok(raw))))
    val result = pipeline(Telemetry.noOp[Result]).callBinary(request, port)

    assertEquals(result.map(_.bytes.toList), Right(raw.toList))

  test("a non-2xx status becomes an Api error carrying the parsed body"):
    val body     = """{"message":"gone"}""".getBytes(StandardCharsets.UTF_8)
    val port     = StubBinaryPort(List(Right(BinaryResponse(410, Map.empty, body))))
    val recorded = ApiPipeline[Result](
      FakeHttpPort(Vector(Right(CodebergResponse(200, Map.empty, ResponseBody.Empty)))),
      config,
      FakeTimer(0L),
      Telemetry.noOp[Result],
      _ => ApiErrorBody(Some("gone"), None, Nil),
    )

    recorded.callBinary(request, port) match
      case Left(error: CodebergError.Api) =>
        assertEquals(error.status, 410)
        assertEquals(error.body.message, Some("gone"))
      case other                          => fail(s"expected an Api error, got $other")

  test("an error body is read as text even though the success body is bytes"):
    val body = """{"message":"nope"}""".getBytes(StandardCharsets.UTF_8)
    val port = StubBinaryPort(List(Right(BinaryResponse(404, Map.empty, body))))

    val seen = ApiPipeline[Result](
      FakeHttpPort(Vector(Right(CodebergResponse(200, Map.empty, ResponseBody.Empty)))),
      config,
      FakeTimer(0L),
      Telemetry.noOp[Result],
      text => ApiErrorBody(Some(text), None, Nil),
    )

    seen.callBinary(request, port) match
      case Left(error: CodebergError.Api) => assertEquals(error.body.message, Some("""{"message":"nope"}"""))
      case other                          => fail(s"expected an Api error, got $other")

  test("a transport failure becomes a Transport error"):
    // Tls, not Timeout: a timeout is retryable, so it would correctly surface
    // as RetriesExhausted wrapping the transport failure rather than as the
    // bare failure this test is about.
    val cause = com.worxbend.codeberg4s.TransportCause.Tls("handshake")
    val port  = StubBinaryPort(List(Left(TransportFailure(cause))))

    pipeline(Telemetry.noOp[Result]).callBinary(request, port) match
      case Left(error: CodebergError.Transport) => assertEquals(error.cause, cause)
      case other                                => fail(s"expected a Transport error, got $other")

  test("a retryable status is retried before it succeeds"):
    val port = StubBinaryPort(
      List(
        Right(BinaryResponse(503, Map.empty, Array.emptyByteArray)),
        Right(ok(zip)),
      )
    )

    val result = pipeline(Telemetry.noOp[Result]).callBinary(request, port)

    assertEquals(result.map(_.bytes.toList), Right(zip.toList))
    assertEquals(port.calls, 2)

  test("telemetry sees the request and then the response, in that order"):
    val telemetry = RecordingTelemetry(failing = false)
    val port      = StubBinaryPort(List(Right(ok(zip))))

    pipeline(telemetry).callBinary(request, port).toOption.foreach(_ => ())

    assertEquals(telemetry.events, Vector("request actions.artifacts.download", "response 200"))

  test("a telemetry sink that fails does not fail the download it is watching"):
    val telemetry = RecordingTelemetry(failing = true)
    val port      = StubBinaryPort(List(Right(ok(zip))))

    val result = pipeline(telemetry).callBinary(request, port)

    assertEquals(result.map(_.bytes.toList), Right(zip.toList))
