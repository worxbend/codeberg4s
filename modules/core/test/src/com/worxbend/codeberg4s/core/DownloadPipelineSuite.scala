package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.ApiErrorBody
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.TransportCause
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.core.Exec.Result

import munit.FunSuite

import java.nio.charset.StandardCharsets

/** [[ApiPipeline.callDownload]] — the path taken by the two endpoints that answer a ZIP.
  *
  * It is the ordinary pipeline path with two differences worth pinning down: the body is handed back undecoded, and the
  * send is given [[CodebergConfig.maxDownloadBodyBytes]] rather than the smaller bound every other call reads under.
  * Everything else — retry, telemetry, status mapping — is shared code, and is asserted here only to prove a download
  * really does go through it.
  *
  * Run with `F = Either`, so retry and telemetry are observed without a scheduler or a real millisecond.
  */
final class DownloadPipelineSuite extends FunSuite:

  private given JitterSource = JitterSource.Deterministic

  private val config: CodebergConfig = CodebergConfig(Auth.Anonymous)

  private val zip: Array[Byte] = Array[Byte](0x50, 0x4B, 0x03, 0x04, 0x00)

  private val request: CodebergRequest =
    CodebergRequest("repos.actions.artifacts.download", HttpMethod.Get, List("repos", "o", "r"), Nil, Nil, None)

  private def pipeline(http: FakeHttpPort, telemetry: Telemetry[Result]): ApiPipeline[Result] =
    ApiPipeline[Result](http, config, FakeTimer(0L), telemetry, _ => ApiErrorBody.Empty)

  private def zipResponse(status: Int, bytes: Array[Byte]): CodebergResponse =
    CodebergResponse(status, Map("content-type" -> List("application/zip")), ResponseBody.of(bytes, StandardCharsets.UTF_8))

  private def bodyOf(status: Int, json: String): CodebergResponse =
    CodebergResponse(status, Map.empty, ResponseBody.utf8(json))

  test("a successful download returns the bytes verbatim"):
    val result = pipeline(FakeHttpPort.always(zipResponse(200, zip)), Telemetry.noOp[Result]).callDownload(request)

    assertEquals(result.map(_.body.bytes.toList), Right(zip.toList))

  test("the response headers reach the caller"):
    val result = pipeline(FakeHttpPort.always(zipResponse(200, zip)), Telemetry.noOp[Result]).callDownload(request)

    assertEquals(result.map(_.header("content-type")), Right(Some("application/zip")))

  test("a successful body is never decoded, so arbitrary bytes survive"):
    // 0xFF 0xFE is not valid UTF-8. Round-tripping it through a String would
    // replace it, which is exactly why this path hands the body back untouched.
    val raw    = Array[Byte](-1, -2, 0, 65)
    val result = pipeline(FakeHttpPort.always(zipResponse(200, raw)), Telemetry.noOp[Result]).callDownload(request)

    assertEquals(result.map(_.body.bytes.toList), Right(raw.toList))

  test("the send is given the archive bound, not the smaller one every other call uses"):
    val http = FakeHttpPort.always(zipResponse(200, zip))

    val result = pipeline(http, Telemetry.noOp[Result]).callDownload(request)

    assertEquals(result.map(_.status), Right(200))
    assertEquals(http.bounds, Vector(config.maxDownloadBodyBytes))
    assertNotEquals(config.maxDownloadBodyBytes, config.maxResponseBodyBytes)

  test("a non-2xx status becomes an Api error carrying the parsed body"):
    val recorded = ApiPipeline[Result](
      FakeHttpPort.always(bodyOf(410, """{"message":"gone"}""")),
      config,
      FakeTimer(0L),
      Telemetry.noOp[Result],
      _ => ApiErrorBody(Some("gone"), None, Nil),
    )

    recorded.callDownload(request) match
      case Left(error: CodebergError.Api) =>
        assertEquals(error.status, 410)
        assertEquals(error.body.message, Some("gone"))
      case other                          => fail(s"expected an Api error, got $other")

  test("an error body is read as text even though the success body is bytes"):
    val seen = ApiPipeline[Result](
      FakeHttpPort.always(bodyOf(404, """{"message":"nope"}""")),
      config,
      FakeTimer(0L),
      Telemetry.noOp[Result],
      text => ApiErrorBody(Some(text), None, Nil),
    )

    seen.callDownload(request) match
      case Left(error: CodebergError.Api) => assertEquals(error.body.message, Some("""{"message":"nope"}"""))
      case other                          => fail(s"expected an Api error, got $other")

  test("a transport failure becomes a Transport error"):
    // Tls, not Timeout: a timeout is retryable, so it would correctly surface
    // as RetriesExhausted wrapping the transport failure rather than as the
    // bare failure this test is about.
    val cause = TransportCause.Tls("handshake")
    val http  = FakeHttpPort.broken(TransportFailure(cause))

    pipeline(http, Telemetry.noOp[Result]).callDownload(request) match
      case Left(error: CodebergError.Transport) => assertEquals(error.cause, cause)
      case other                                => fail(s"expected a Transport error, got $other")

  test("a retryable status is retried before it succeeds"):
    val http = FakeHttpPort(
      Vector(
        Right(CodebergResponse(503, Map.empty, ResponseBody.Empty)),
        Right(zipResponse(200, zip)),
      )
    )

    val result = pipeline(http, Telemetry.noOp[Result]).callDownload(request)

    assertEquals(result.map(_.body.bytes.toList), Right(zip.toList))
    assertEquals(http.sends, 2)

  test("telemetry sees the request and then the response, in that order"):
    val telemetry = RecordingTelemetry(failing = false)

    val result = pipeline(FakeHttpPort.always(zipResponse(200, zip)), telemetry).callDownload(request)

    assertEquals(result.map(_.status), Right(200))
    assertEquals(telemetry.events, Vector("request repos.actions.artifacts.download", "response 200"))

  test("a telemetry sink that fails does not fail the download it is watching"):
    val telemetry = RecordingTelemetry(failing = true)

    val result = pipeline(FakeHttpPort.always(zipResponse(200, zip)), telemetry).callDownload(request)

    assertEquals(result.map(_.body.bytes.toList), Right(zip.toList))
