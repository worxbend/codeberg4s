package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.ApiErrorBody
import com.worxbend.codeberg4s.CallContext
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.TransportCause
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.retry.Jitter
import com.worxbend.codeberg4s.retry.RetryPolicy
import com.worxbend.codeberg4s.syntax.discard

import munit.FunSuite

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import java.util.concurrent.atomic.AtomicLong

final class ApiPipelineSuite extends FunSuite:

  private val config: CodebergConfig =
    CodebergConfig(Auth.Anonymous).copy(retry = RetryPolicy.Default.copy(jitter = Jitter.None))

  private val expectedUri: String = "https://codeberg.org/api/v1/repos/owner/name/issues?page=1"

  private val listing: CodebergRequest = CodebergRequest(
    operation = "issues.list",
    method    = HttpMethod.Get,
    path      = List("repos", "owner", "name", "issues"),
    query     = List(("page", "1")),
    headers   = Nil,
    body      = None,
  )

  private val creation: CodebergRequest = listing.copy(operation = "issues.create", method = HttpMethod.Post)

  private val credentialled: CodebergRequest = listing.copy(query = List(("page", "1"), ("token", "s3cret")))

  private val forgejoErrorBody: String =
    """{"message":"GetUserByName","url":"https://codeberg.org/api/swagger",""" +
      """"errors":["user redirect does not exist [name: definitely]"]}"""

  /** Stands in for the codec module's real parser: it reports the raw payload as the single detail, which is enough to
    * prove the parsed body reached the failure.
    */
  private val stubErrorBody: String => ApiErrorBody =
    body => ApiErrorBody(Some("GetUserByName"), Some("https://codeberg.org/api/swagger"), List(body.trim))

  /** Stands in for a parser that blows up on the payload it was handed — an HTML error page from a reverse proxy. */
  private val failingErrorBody: String => ApiErrorBody =
    _ => sys.error("this parser cannot read anything")

  private val silent: Telemetry[Exec.Result] = Telemetry.noOp[Exec.Result]

  private def pipelineOf(
      http: HttpPort[Exec.Result],
      timer: Timer[Exec.Result],
      telemetry: Telemetry[Exec.Result],
      errorBody: String => ApiErrorBody,
  ): ApiPipeline[Exec.Result] =
    ApiPipeline[Exec.Result](http, config, timer, telemetry, errorBody)(using Exec.eitherExec)(using
      JitterSource.Deterministic)

  private def responseOf(status: Int, body: String, headers: (String, List[String])*): CodebergResponse =
    CodebergResponse(status, headers.toMap, ResponseBody.utf8(body))

  private def contextOf(operation: String, method: HttpMethod, requestId: Option[String]): CallContext =
    CallContext(operation, method, expectedUri, requestId, 0L)

  test("a 2xx body is decoded and returned"):
    given Decode[String] = body => Right(body.text)
    val http             = FakeHttpPort.always(responseOf(200, "payload"))

    val result = pipelineOf(http, FakeTimer(0L), silent, stubErrorBody)
      .call[String](listing, RetryEligibility.IdempotentOnly)

    assertEquals(result, Right("payload"))
    assertEquals(http.sends, 1)

  test("the URI handed to the transport is redacted"):
    given Decode[String] = body => Right(body.text)
    val http             = FakeHttpPort.always(responseOf(200, "payload"))

    val result = pipelineOf(http, FakeTimer(0L), silent, stubErrorBody)
      .call[String](credentialled, RetryEligibility.IdempotentOnly)

    assertEquals(result, Right("payload"))
    assertEquals(http.uris, Vector("https://codeberg.org/api/v1/repos/owner/name/issues?page=1&token=***"))

  test("a 404 becomes an Api failure carrying the parsed error body and the request id"):
    given Decode[String] = body => Right(body.text)
    val http             = FakeHttpPort.always(responseOf(404, forgejoErrorBody, "x-request-id" -> List("abc123")))

    val result = pipelineOf(http, FakeTimer(0L), silent, stubErrorBody)
      .call[String](listing, RetryEligibility.IdempotentOnly)

    assertEquals(
      result,
      Left(
        CodebergError.Api(
          contextOf("issues.list", HttpMethod.Get, Some("abc123")),
          404,
          ApiErrorBody(Some("GetUserByName"), Some("https://codeberg.org/api/swagger"), List(forgejoErrorBody)),
          None,
        )
      ),
    )
    assertEquals(http.sends, 1)

  test("a 500 is attempted again and the later success is returned"):
    given Decode[String] = body => Right(body.text)
    val timer            = FakeTimer(0L)
    val http             = FakeHttpPort(Vector(Right(responseOf(500, "boom")), Right(responseOf(200, "payload"))))

    val result = pipelineOf(http, timer, silent, stubErrorBody)
      .call[String](listing, RetryEligibility.IdempotentOnly)

    assertEquals(result, Right("payload"))
    assertEquals(http.sends, 2)
    assertEquals(timer.sleeps, Vector(250.millis))

  test("a 429 is retried after the delay the server asked for"):
    given Decode[String] = body => Right(body.text)
    val timer            = FakeTimer(0L)

    val http = FakeHttpPort(
      Vector(
        Right(responseOf(429, "slow down", "retry-after" -> List("2"))),
        Right(responseOf(200, "payload")),
      )
    )

    val result = pipelineOf(http, timer, silent, stubErrorBody)
      .call[String](listing, RetryEligibility.IdempotentOnly)

    assertEquals(result, Right("payload"))
    assertEquals(timer.sleeps, Vector(2.seconds))

  test("the Retry-After the server sent reaches the caller on the error"):
    val http = FakeHttpPort.always(responseOf(429, "slow down", "retry-after" -> List("2")))

    val result = pipelineOf(http, FakeTimer(0L), silent, stubErrorBody)
      .callUnit(creation, RetryEligibility.Never)

    result match
      case Left(CodebergError.Api(_, 429, _, retryAfter)) => assertEquals(retryAfter, Some(2.seconds))
      case other                                          => fail(s"expected a 429 carrying Retry-After, got $other")

  test("a mutating call is not repeated under IdempotentOnly, whatever the status"):
    val timer = FakeTimer(0L)
    val http  = FakeHttpPort.always(responseOf(503, "unavailable"))

    val result = pipelineOf(http, timer, silent, stubErrorBody)
      .callUnit(creation, RetryEligibility.IdempotentOnly)

    assert(result.isLeft)
    assertEquals(http.sends, 1)
    assertEquals(timer.sleeps, Vector.empty[FiniteDuration])

  test("a body that does not decode becomes DecodingFailed with a snippet bounded at MaxSnippetLength"):
    given Decode[String] = _ => Left(DecodeFailure(JsonPath.of("items"), "expected an array"))
    val http             = FakeHttpPort.always(responseOf(200, "x" * 2000))

    val result = pipelineOf(http, FakeTimer(0L), silent, stubErrorBody)
      .call[String](listing, RetryEligibility.IdempotentOnly)

    result match
      case Left(CodebergError.DecodingFailed(ctx, snippet, path, cause)) =>
        assertEquals(ctx, contextOf("issues.list", HttpMethod.Get, None))
        assertEquals(snippet.length, CodebergError.MaxSnippetLength)
        assertEquals(path, JsonPath.of("items"))
        assertEquals(cause, "expected an array")
      case other                                                         =>
        fail(s"expected a decoding failure, got $other")

    assertEquals(http.sends, 1)

  test("a sensitive body is replaced by the placeholder, never excerpted"):
    val credential = "gto_thisisarealtoken"
    val payload    = s"""{"sha1": "$credential"}"""

    given Decode[String] =
      Decode.sensitive(_ => Left(DecodeFailure(JsonPath.of("id"), "no such field")))

    val http = FakeHttpPort.always(responseOf(201, payload))

    val result = pipelineOf(http, FakeTimer(0L), silent, stubErrorBody)
      .call[String](creation, RetryEligibility.Never)

    result match
      case Left(error @ CodebergError.DecodingFailed(_, snippet, path, cause)) =>
        assertEquals(snippet, ApiPipeline.redactedSnippet(payload.length))
        assertEquals(path, JsonPath.of("id"))
        assertEquals(cause, "no such field")
        assert(!error.describe.contains(credential), s"the body reached the rendered failure: ${error.describe}")
      case other                                                               =>
        fail(s"expected a decoding failure, got $other")

  test("the placeholder is what a telemetry sink observes, not only what the caller receives"):
    val credential = "gto_thisisarealtoken"

    given Decode[String] = Decode.sensitive(_ => Left(DecodeFailure(JsonPath.Root, "not an object")))

    val http      = FakeHttpPort.always(responseOf(201, s"""{"sha1": "$credential"}"""))
    val telemetry = RecordingTelemetry(failing = false)

    pipelineOf(http, FakeTimer(0L), telemetry, stubErrorBody)
      .call[String](creation, RetryEligibility.Never)
      .discard

    val rendered = telemetry.observed.map(_.describe)

    assert(rendered.nonEmpty, "the sink observed no failure at all")
    rendered.foreach(line => assert(!line.contains(credential), s"a credential was observed: $line"))

  test("an ordinary decoder keeps its excerpt, because that is what makes a failure diagnosable"):
    given Decode[String] = _ => Left(DecodeFailure(JsonPath.Root, "not an object"))
    val http             = FakeHttpPort.always(responseOf(200, """{"unexpected": true}"""))

    val result = pipelineOf(http, FakeTimer(0L), silent, stubErrorBody)
      .call[String](listing, RetryEligibility.Never)

    result match
      case Left(CodebergError.DecodingFailed(_, snippet, _, _)) => assertEquals(snippet, """{"unexpected": true}""")
      case other                                                => fail(s"expected a decoding failure, got $other")

  test("a decoding failure is never attempted again"):
    given Decode[String] = _ => Left(DecodeFailure(JsonPath.Root, "not an object"))
    val http             = FakeHttpPort.always(responseOf(200, "{}"))

    val result = pipelineOf(http, FakeTimer(0L), silent, stubErrorBody)
      .call[String](listing, RetryEligibility.AlwaysRetry)

    assert(result.isLeft)
    assertEquals(http.sends, 1)

  test("a request that never reaches the server becomes a Transport failure"):
    given Decode[String] = body => Right(body.text)
    val cause            = TransportCause.Tls("certificate expired")
    val http             = FakeHttpPort.broken(TransportFailure(cause))

    val result = pipelineOf(http, FakeTimer(0L), silent, stubErrorBody)
      .call[String](listing, RetryEligibility.IdempotentOnly)

    assertEquals(result, Left(CodebergError.Transport(contextOf("issues.list", HttpMethod.Get, None), cause)))
    assertEquals(http.sends, 1)

  test("a transport failure that keeps recurring ends as RetriesExhausted preserving the last failure"):
    given Decode[String] = body => Right(body.text)
    val cause            = TransportCause.Timeout("read timed out")
    val http             = FakeHttpPort.broken(TransportFailure(cause))
    val context          = contextOf("issues.list", HttpMethod.Get, None)

    val result = pipelineOf(http, FakeTimer(0L), silent, stubErrorBody)
      .call[String](listing, RetryEligibility.IdempotentOnly)

    assertEquals(result, Left(CodebergError.RetriesExhausted(context, 3, CodebergError.Transport(context, cause))))
    assertEquals(http.sends, 3)

  test("a successful call reports a request and a response and no error"):
    given Decode[String] = body => Right(body.text)
    val telemetry        = RecordingTelemetry(failing = false)
    val http             = FakeHttpPort.always(responseOf(200, "payload"))

    val result = pipelineOf(http, FakeTimer(0L), telemetry, stubErrorBody)
      .call[String](listing, RetryEligibility.IdempotentOnly)

    assertEquals(result, Right("payload"))
    assertEquals(telemetry.events, Vector("request issues.list", "response 200"))

  test("a failing call reports the attempt in order and then the failure the caller receives"):
    given Decode[String] = body => Right(body.text)
    val telemetry        = RecordingTelemetry(failing = false)
    val http             = FakeHttpPort.always(responseOf(404, forgejoErrorBody))

    val result = pipelineOf(http, FakeTimer(0L), telemetry, stubErrorBody)
      .call[String](listing, RetryEligibility.IdempotentOnly)

    assert(result.isLeft)
    assertEquals(telemetry.events, Vector("request issues.list", "response 404", "error Api", "error Api"))

  test("a retried call reports one request and one response per attempt"):
    given Decode[String] = body => Right(body.text)
    val telemetry        = RecordingTelemetry(failing = false)
    val http             = FakeHttpPort(Vector(Right(responseOf(503, "down")), Right(responseOf(200, "payload"))))

    val result = pipelineOf(http, FakeTimer(0L), telemetry, stubErrorBody)
      .call[String](listing, RetryEligibility.IdempotentOnly)

    assertEquals(result, Right("payload"))
    assertEquals(
      telemetry.events,
      Vector("request issues.list", "response 503", "error Api", "request issues.list", "response 200"),
    )

  test("a telemetry sink that fails does not fail the call it is only watching"):
    given Decode[String] = body => Right(body.text)
    val telemetry        = RecordingTelemetry(failing = true)
    val http             = FakeHttpPort.always(responseOf(200, "payload"))

    val result = pipelineOf(http, FakeTimer(0L), telemetry, stubErrorBody)
      .call[String](listing, RetryEligibility.IdempotentOnly)

    assertEquals(result, Right("payload"))
    assertEquals(telemetry.events, Vector("request issues.list", "response 200"))

  test("an error body the parser cannot read falls back to Empty and never masks the status"):
    given Decode[String] = body => Right(body.text)
    val http             = FakeHttpPort.always(responseOf(422, "<html>not json</html>"))

    val result = pipelineOf(http, FakeTimer(0L), silent, failingErrorBody)
      .call[String](listing, RetryEligibility.Never)

    assertEquals(
      result,
      Left(CodebergError.Api(contextOf("issues.list", HttpMethod.Get, None), 422, ApiErrorBody.Empty, None)),
    )

  test("an empty error body becomes Empty without consulting the parser"):
    given Decode[String] = body => Right(body.text)
    val http             = FakeHttpPort.always(responseOf(500, "   "))

    val result = pipelineOf(http, FakeTimer(0L), silent, failingErrorBody)
      .call[String](listing, RetryEligibility.Never)

    assertEquals(
      result,
      Left(CodebergError.Api(contextOf("issues.list", HttpMethod.Get, None), 500, ApiErrorBody.Empty, None)),
    )

  test("callUnit succeeds on a 204 and never looks at the body"):
    val http = FakeHttpPort.always(responseOf(204, "an unexpected payload"))

    val result = pipelineOf(http, FakeTimer(0L), silent, stubErrorBody)
      .callUnit(listing, RetryEligibility.IdempotentOnly)

    assertEquals(result, Right(()))

  test("callUnit classifies a non-2xx exactly as call does"):
    val http = FakeHttpPort.always(responseOf(403, forgejoErrorBody))

    val result = pipelineOf(http, FakeTimer(0L), silent, stubErrorBody)
      .callUnit(listing, RetryEligibility.Never)

    assertEquals(
      result,
      Left(
        CodebergError.Api(
          contextOf("issues.list", HttpMethod.Get, None),
          403,
          ApiErrorBody(Some("GetUserByName"), Some("https://codeberg.org/api/swagger"), List(forgejoErrorBody)),
          None,
        )
      ),
    )

  test("callPage assembles a page from the response headers, not from the item count"):
    given Decode[Vector[Int]] = _ => Right(Vector(1, 2, 3))

    val http = FakeHttpPort.always(
      responseOf(
        200,
        "[1,2,3]",
        "link"          -> List("<https://codeberg.org/api/v1/x?limit=50&page=2>; rel=\"next\""),
        "x-total-count" -> List("1589"),
      )
    )

    val params = PageParams(PageNumber.First, PageSize.from(50).getOrElse(PageSize.Default))
    val result = pipelineOf(http, FakeTimer(0L), silent, stubErrorBody).callPage[Int](listing, params)

    result match
      case Right(page) =>
        assertEquals(page.items, Vector(1, 2, 3))
        assertEquals(page.params, params)
        assertEquals(page.totalCount, Some(1589))
        assertEquals(page.nextPage, PageNumber.from(2).toOption)
        assertEquals(page.isLast, false)
      case Left(error) =>
        fail(s"expected a page, got $error")

  test("callPage reports the last page when the response carried no next relation"):
    given Decode[Vector[Int]] = _ => Right(Vector.empty[Int])
    val http                  = FakeHttpPort.always(responseOf(200, "[]"))

    val result = pipelineOf(http, FakeTimer(0L), silent, stubErrorBody)
      .callPage[Int](listing, PageParams.First)

    result match
      case Right(page) => assertEquals(page.isLast, true)
      case Left(error) => fail(s"expected a page, got $error")

  test("callPage reports a body that does not decode as DecodingFailed"):
    given Decode[Vector[Int]] = _ => Left(DecodeFailure(JsonPath.Root.index(0), "expected a number"))
    val http                  = FakeHttpPort.always(responseOf(200, """["not a number"]"""))

    val result = pipelineOf(http, FakeTimer(0L), silent, stubErrorBody)
      .callPage[Int](listing, PageParams.First)

    result match
      case Left(CodebergError.DecodingFailed(_, snippet, path, _)) =>
        assertEquals(snippet, """["not a number"]""")
        assertEquals(path, JsonPath.Root.index(0))
      case other                                                   =>
        fail(s"expected a decoding failure, got $other")

  test("the duration on a call context is measured with the timer around the send"):
    given Decode[String] = body => Right(body.text)
    val http             = FakeHttpPort.always(responseOf(404, forgejoErrorBody))

    val result = pipelineOf(http, SteppingTimer(7L), silent, stubErrorBody)
      .call[String](listing, RetryEligibility.Never)

    result match
      case Left(CodebergError.Api(ctx, _, _, _)) => assertEquals(ctx.durationMs, 7L)
      case other                                 => fail(s"expected an Api failure, got $other")

/** A [[Timer]] whose clock advances by a fixed step on every reading.
  *
  * The pipeline measures an attempt by reading the clock on either side of the send, so a timer that ticks between
  * readings is what proves the duration comes from [[Timer.nowMillis]] rather than from a system clock.
  */
private final class SteppingTimer(stepMillis: Long) extends Timer[Exec.Result]:

  private val clock = AtomicLong(0L)

  override def nowMillis: Exec.Result[Long] = Right(clock.getAndAdd(stepMillis))

  override def sleep(duration: FiniteDuration): Exec.Result[Unit] =
    clock.addAndGet(duration.toMillis).discard
    Right(())
