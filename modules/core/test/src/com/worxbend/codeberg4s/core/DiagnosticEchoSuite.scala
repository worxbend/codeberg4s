package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.auth.{ApiToken, Auth, Password}
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.retry.{Jitter, RetryPolicy}
import com.worxbend.codeberg4s.syntax.discard
import com.worxbend.codeberg4s.{
  ApiErrorBody,
  CodebergConfig,
  CodebergError,
  HttpMethod,
  JsonPath,
  TransportCause,
  ValidationError
}

import munit.FunSuite

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Synthetic echoes at the core boundary: no transport, JSON adapter, scheduler or socket. */
final class DiagnosticEchoSuite extends FunSuite:

  private val token       = "diagnostic-only-configured-token"
  private val password    = " diagnostic-only-pässwörd-雪 "
  private val username    = "diagnostic-üser"
  private val basicWire   = Base64.getEncoder.encodeToString(s"$username:$password".getBytes(StandardCharsets.UTF_8))
  private val credentials = Vector(
    ("token", Auth.Token(valid(ApiToken.from(token))), Vector(token)),
    ("UTF-8 Basic", valid(Auth.basic(username, valid(Password.from(password)))), Vector(password, basicWire)),
  )
  private val request     = CodebergRequest("diagnostics.read", HttpMethod.Get, List("version"), Nil, Nil, None)

  private def valid[A](value: Either[ValidationError, A]): A = value match
    case Right(result) => result
    case Left(error)   => fail(s"invalid synthetic fixture: $error")

  private def pipeline(
      auth: Auth,
      http: FakeHttpPort,
      telemetry: RecordingTelemetry,
  ): ApiPipeline[Exec.Result] =
    val config = CodebergConfig(auth).copy(retry = RetryPolicy.Default.copy(maxAttempts = 2, jitter = Jitter.None))
    ApiPipeline[Exec.Result](
      http,
      config,
      FakeTimer(0L),
      telemetry,
      text => ApiErrorBody(Some(s"message $text"), Some(s"https://docs.example/$text"), List(s"detail $text")),
    )(using Exec.eitherExec)(using JitterSource.Deterministic)

  private def errorOf[A](result: Exec.Result[A]): CodebergError = result match
    case Left(error) => error
    case Right(_)    => fail("expected a diagnostic failure")

  private def clean(text: String, secrets: Vector[String]): Unit =
    secrets.foreach(secret => assert(!text.contains(secret), "synthetic credential survived in a diagnostic field"))

  private def cleanError(error: CodebergError, secrets: Vector[String]): Unit =
    clean(error.toString, secrets)
    clean(error.describe, secrets)

  private def cleanEvents(telemetry: RecordingTelemetry, secrets: Vector[String]): Unit =
    telemetry.seen.foreach(ctx       => clean(ctx.toString, secrets))
    telemetry.observed.foreach(error => cleanError(error, secrets))

  private val entryPoints: Vector[(String, ApiPipeline[Exec.Result] => Exec.Result[Unit])] = Vector(
    "call" ->
      (pipeline =>
        pipeline.call[String](request, RetryEligibility.IdempotentOnly)(using body => Right(body.text)).map(_ => ())
      ),
    "unit" -> (pipeline => pipeline.callUnit(request, RetryEligibility.IdempotentOnly)),
    "page" ->
      (pipeline => pipeline.callPage[String](request, PageParams.First)(using _ => Right(Vector.empty[String])).map(_ => ())),
    "envelope" ->
      (pipeline =>
        pipeline.callEnvelopePage[String](request, PageParams.First)(using
          _ => Right(ListEnvelope(Vector.empty[String], None))).map(_ => ())
      ),
    "download" -> (pipeline => pipeline.callDownload(request).map(_ => ())),
  )

  credentials.foreach: (label, auth, secrets) =>
    entryPoints.foreach: (entry, invoke) =>
      test(s"$label echoes are scrubbed in $entry response contexts and every retry/final error"):
        val echo      = secrets.mkString("echo ", " / ", " end")
        val response  = CodebergResponse(503, Map("x-request-id" -> List(echo)), ResponseBody.utf8(echo))
        val http      = FakeHttpPort.always(response)
        val telemetry = RecordingTelemetry(false)
        val error     = errorOf(invoke(pipeline(auth, http, telemetry)))
        assertEquals(http.sends, 2)
        assertEquals(
          telemetry.events,
          Vector(
            "request diagnostics.read",
            "response 503",
            "error Api",
            "request diagnostics.read",
            "response 503",
            "error Api",
            "error RetriesExhausted",
          )
        )
        cleanEvents(telemetry, secrets)
        cleanError(error, secrets)
        error match
          case CodebergError.RetriesExhausted(_, 2, CodebergError.Api(ctx, 503, body, _)) =>
            assert(body.message.exists(_.contains("message")), "ordinary error wording must remain useful")
            assert(body.url.exists(_.contains("docs.example")), "ordinary documentation URL must remain useful")
            assert(body.errors.exists(_.contains("detail")), "ordinary validation details must remain useful")
            assert(ctx.requestId.nonEmpty, "redaction must not discard the whole ordinary request id")
          case other => fail(s"status and retry category must survive: $other")

    entryPoints.foreach: (entry, invoke) =>
      test(s"$label request-id echo is scrubbed before a successful $entry response callback"):
        val echo      = secrets.mkString("request-", " / ", " end")
        val telemetry = RecordingTelemetry(false)
        val http = FakeHttpPort.always(CodebergResponse(200, Map("x-request-id" -> List(echo)), ResponseBody.utf8("safe")))
        assert(invoke(pipeline(auth, http, telemetry)).isRight)
        assertEquals(telemetry.events, Vector("request diagnostics.read", "response 200"))
        cleanEvents(telemetry, secrets)
        assert(telemetry.seen.lastOption.flatMap(_.requestId).nonEmpty)

    test(s"$label echoes are scrubbed from ordinary decoder paths reasons and excerpts"):
      val echo                   = secrets.mkString("echo ", " / ", " end")
      val decode: Decode[String] = _ => Left(DecodeFailure(JsonPath.Root.field(echo), s"reader rejected $echo"))
      val telemetry              = RecordingTelemetry(false)
      val http  = FakeHttpPort.always(CodebergResponse(200, Map("x-request-id" -> List(echo)), ResponseBody.utf8(echo)))
      val error = errorOf(pipeline(auth, http, telemetry).call[String](request, RetryEligibility.Never)(using decode))
      assertEquals(
        telemetry.events,
        Vector("request diagnostics.read", "response 200", "error DecodingFailed", "error DecodingFailed")
      )
      cleanEvents(telemetry, secrets)
      cleanError(error, secrets)
      error match
        case CodebergError.DecodingFailed(_, snippet, _, reason) =>
          assert(snippet.contains("echo"), "ordinary excerpt must not be wholly suppressed")
          assert(reason.contains("reader rejected"), "ordinary reader explanation must remain useful")
        case other                                               => fail(s"expected DecodingFailed, got $other")

    secrets.foreach: secret =>
      test(s"$label ordinary excerpt redacts before a secret crosses the snippet boundary (${secret.length} chars)"):
        val prefix    = "x".repeat(CodebergError.MaxSnippetLength - 5)
        val body      = prefix + secret + " trailing data"
        val telemetry = RecordingTelemetry(false)
        val http      = FakeHttpPort.always(CodebergResponse(200, Map.empty, ResponseBody.utf8(body)))
        val error     = errorOf(pipeline(auth, http, telemetry).call[String](request, RetryEligibility.Never)(using
          _ => Left(DecodeFailure(JsonPath.Root.field("version"), "expected version"))))
        error match
          case CodebergError.DecodingFailed(_, snippet, path, reason) =>
            assert(snippet.startsWith(prefix), "useful ordinary prefix must survive")
            assert(!snippet.contains(secret.take(5)), "truncating before redaction leaked the beginning of a credential")
            assert(snippet.length <= CodebergError.MaxSnippetLength + 3, "excerpt became unbounded")
            assertEquals(path, JsonPath.Root.field("version"))
            assertEquals(reason, "expected version")
          case other                                                  => fail(s"expected DecodingFailed, got $other")
        telemetry.observed.foreach:
          case CodebergError.DecodingFailed(_, snippet, _, _) =>
            assert(!snippet.contains(secret.take(5)), "an attempt/final telemetry excerpt leaked a credential prefix")
          case other                                          => fail(s"expected decoder telemetry, got $other")

    Vector[TransportCause](
      TransportCause.ConnectionFailed(secrets.mkString("socket echo ", " / ", " end")),
      TransportCause.Timeout(secrets.mkString("timeout echo ", " / ", " end")),
      TransportCause.Tls(secrets.mkString("TLS echo ", " / ", " end")),
      TransportCause.Dns(secrets.mkString("DNS echo ", " / ", " end")),
      TransportCause.Interrupted(secrets.mkString("interrupt echo ", " / ", " end")),
      TransportCause.ResponseTooLarge(secrets.mkString("size echo ", " / ", " end")),
      TransportCause.Unknown(secrets.mkString("unknown echo ", " / ", " end")),
    ).foreach: cause =>
      test(s"$label is scrubbed from ${cause.productPrefix} transport diagnostics before telemetry"):
        val telemetry = RecordingTelemetry(false)
        val http      = FakeHttpPort.broken(TransportFailure(cause))
        val error     = errorOf(pipeline(auth, http, telemetry).callUnit(request, RetryEligibility.Never))
        assertEquals(telemetry.events, Vector("request diagnostics.read", "error Transport", "error Transport"))
        cleanEvents(telemetry, secrets)
        cleanError(error, secrets)
        error match
          case CodebergError.Transport(_, sanitized) =>
            assertEquals(sanitized.productPrefix, cause.productPrefix)
            assert(sanitized.describe.contains("echo"), "ordinary transport diagnostic was unnecessarily suppressed")
          case other                                 => fail(s"expected Transport, got $other")

  test("sensitive decoding withholds unconfigured secrets in reader paths and reasons before every callback"):
    val secret    = "diagnostic-only-newly-issued-secret"
    val body      = s"{\"$secret\":\"$secret\"}"
    val decode    =
      Decode.sensitive[String](_ => Left(DecodeFailure(JsonPath.Root.field(secret), s"duplicate key '$secret'")))
    val telemetry = RecordingTelemetry(false)
    val http      = FakeHttpPort.always(CodebergResponse(200, Map.empty, ResponseBody.utf8(body)))
    val error     =
      errorOf(pipeline(Auth.Anonymous, http, telemetry).call[String](request, RetryEligibility.Never)(using decode))
    cleanEvents(telemetry, Vector(secret))
    cleanError(error, Vector(secret))
    error match
      case CodebergError.DecodingFailed(_, snippet, path, reason) =>
        assertEquals(snippet, ApiPipeline.redactedSnippet(ResponseBody.utf8(body).size))
        assertEquals(path, JsonPath.Root)
        assert(!reason.contains("duplicate key"), "sensitive reader reason must not quote payload-derived text")
      case other                                                  => fail(s"expected DecodingFailed, got $other")

  test("sensitive decoder also withholds free-form non-2xx diagnostics"):
    val secret    = "diagnostic-only-newly-issued-secret"
    val telemetry = RecordingTelemetry(false)
    val http      = FakeHttpPort.always(CodebergResponse(403, Map.empty, ResponseBody.utf8(secret)))
    val error = errorOf(pipeline(Auth.Anonymous, http, telemetry).call[String](request, RetryEligibility.Never)(using
      Decode.sensitive[String](body => Right(body.text))))
    cleanEvents(telemetry, Vector(secret))
    cleanError(error, Vector(secret))
    error match
      case CodebergError.Api(_, 403, _, _) => ()
      case other                           => fail(s"expected Api(403), got $other")

  test("successful sensitive data is returned unchanged rather than redacted as a model"):
    val secret    = "diagnostic-only-newly-issued-secret"
    val telemetry = RecordingTelemetry(false)
    val http      = FakeHttpPort.always(CodebergResponse(200, Map.empty, ResponseBody.utf8(secret)))
    assertEquals(
      pipeline(Auth.Anonymous, http, telemetry).call[String](request, RetryEligibility.Never)(using
        Decode.sensitive[String](body => Right(body.text))),
      Right(secret)
    )
    assertEquals(telemetry.events, Vector("request diagnostics.read", "response 200"))

  private val sensitiveEntryPoints: Vector[(String, ApiPipeline[Exec.Result] => Exec.Result[Unit])] = Vector(
    "call"     ->
      (pipeline =>
        pipeline.callSensitive[String](request, RetryEligibility.IdempotentOnly)(using
          body => Right(body.text)).map(_ => ())
      ),
    "unit"     -> (pipeline => pipeline.callUnitSensitive(request, RetryEligibility.IdempotentOnly)),
    "page"     ->
      (pipeline =>
        pipeline.callPageSensitive[String](request, PageParams.First)(using _ => Right(Vector.empty[String])).map(_ => ())
      ),
    "envelope" ->
      (pipeline =>
        pipeline.callEnvelopePageSensitive[String](request, PageParams.First)(using
          _ => Right(ListEnvelope(Vector.empty[String], None))).map(_ => ())
      ),
    "download" -> (pipeline => pipeline.callDownloadSensitive(request).map(_ => ())),
  )

  sensitiveEntryPoints.foreach: (entry, invoke) =>
    test(s"sensitive $entry withholds unknown response ids and every non-2xx retry diagnostic"):
      val secret    = "diagnostic-only-newly-issued-secret"
      val response  = CodebergResponse(503, Map("x-request-id" -> List(secret)), ResponseBody.utf8(secret))
      val telemetry = RecordingTelemetry(false)
      val http      = FakeHttpPort.always(response)
      val error     = errorOf(invoke(pipeline(Auth.Anonymous, http, telemetry)))
      cleanEvents(telemetry, Vector(secret))
      cleanError(error, Vector(secret))
      assertEquals(http.sends, 2)
      assert(telemetry.seen.forall(_.requestId.isEmpty))
      error match
        case CodebergError.RetriesExhausted(_, 2, CodebergError.Api(_, 503, body, _)) =>
          assertEquals(body, ApiErrorBody.Empty)
        case other => fail(s"expected nested Api, got $other")

    test(s"sensitive $entry withholds unknown transport detail before every retry/final callback"):
      val secret    = "diagnostic-only-newly-issued-secret"
      val telemetry = RecordingTelemetry(false)
      val http      = FakeHttpPort.broken(TransportFailure(TransportCause.Timeout(secret)))
      val error     = errorOf(invoke(pipeline(Auth.Anonymous, http, telemetry)))
      cleanEvents(telemetry, Vector(secret))
      cleanError(error, Vector(secret))
      assertEquals(http.sends, 2)
      error match
        case CodebergError.RetriesExhausted(_, 2, CodebergError.Transport(_, TransportCause.Timeout(detail))) =>
          assertEquals(detail, Redaction.Mask)
        case other => fail(s"expected nested Timeout, got $other")

    test(s"sensitive $entry withholds an unknown response id even when the call succeeds"):
      val secret    = "diagnostic-only-newly-issued-secret"
      val telemetry = RecordingTelemetry(false)
      val http = FakeHttpPort.always(CodebergResponse(200, Map("x-request-id" -> List(secret)), ResponseBody.utf8(secret)))
      assert(invoke(pipeline(Auth.Anonymous, http, telemetry)).isRight)
      cleanEvents(telemetry, Vector(secret))
      assert(telemetry.seen.forall(_.requestId.isEmpty))
      assertEquals(telemetry.events, Vector("request diagnostics.read", "response 200"))

  private val headerPasswordMaterial = password.trim
  private val headerEchoes           = Vector(
    "raw whole"               -> password,
    "raw leading edge"        -> s"${password}ordinary-id-suffix",
    "raw trailing edge"       -> s"ordinary-id-prefix$password",
    "native fully trimmed"    -> headerPasswordMaterial,
    "native leading trimmed"  -> s"${password.stripLeading()}ordinary-id-suffix",
    "native trailing trimmed" -> s"ordinary-id-prefix${password.stripTrailing()}",
  )

  entryPoints.foreach: (entry, invoke) =>
    headerEchoes.foreach: (representation, header) =>
      Vector(200, 503).foreach: status =>
        test(s"Basic password $representation request-id material is withheld in $entry status $status diagnostics"):
          val telemetry = RecordingTelemetry(false)
          val response = CodebergResponse(status, Map("X-Request-Id" -> List(header)), ResponseBody.utf8("ordinary body"))
          val http   = FakeHttpPort.always(response)
          val result = invoke(pipeline(credentials(1)._2, http, telemetry))
          if status.equals(200) then
            assert(result.isRight)
            assertEquals(telemetry.events, Vector("request diagnostics.read", "response 200"))
          else
            val error = errorOf(result)
            assertEquals(http.sends, 2)
            assertEquals(telemetry.events.count(_.equals("response 503")), 2)
            assertEquals(telemetry.observed.size, 3)
            cleanError(error, Vector(headerPasswordMaterial, basicWire))
            error match
              case CodebergError.RetriesExhausted(_, 2, CodebergError.Api(_, 503, _, _)) => ()
              case other => fail(s"expected nested retry failure, got $other")
          cleanEvents(telemetry, Vector(headerPasswordMaterial, basicWire))
          if header.contains("ordinary-id-") then
            assert(telemetry.seen.filter(_.requestId.nonEmpty).forall(_.requestId.exists(_.contains("ordinary-id-"))))

    Vector(200, 503).foreach: status =>
      test(s"Basic $entry status $status keeps an ordinary request id after header normalization"):
        val telemetry   = RecordingTelemetry(false)
        val http        = FakeHttpPort.always(CodebergResponse(
          status,
          Map("X-Request-Id" -> List(" \t ordinary-request-id-42 \t ")),
          ResponseBody.utf8("ordinary body"),
        ))
        val result      = invoke(pipeline(credentials(1)._2, http, telemetry))
        if status.equals(200) then assert(result.isRight)
        else errorOf(result).discard
        val responseIds = telemetry.events.zip(telemetry.seen).collect:
          case (event, ctx) if event.startsWith("response") => ctx.requestId
        assert(responseIds.nonEmpty)
        assertEquals(responseIds.distinct, Vector(Some("ordinary-request-id-42")))
        telemetry.observed.foreach:
          case CodebergError.Api(ctx, _, _, _)           => assertEquals(ctx.requestId, Some("ordinary-request-id-42"))
          case CodebergError.RetriesExhausted(ctx, _, _) => assertEquals(ctx.requestId, Some("ordinary-request-id-42"))
          case other                                     => fail(s"expected status/retry telemetry, got $other")

  credentials.foreach: (label, auth, secrets) =>
    test(s"$label configured credentials in a successful decoded value are not sanitized as data"):
      val body      = secrets.mkString(" / ")
      val telemetry = RecordingTelemetry(false)
      val http      = FakeHttpPort.always(CodebergResponse(200, Map.empty, ResponseBody.utf8(body)))
      assertEquals(
        pipeline(auth, http, telemetry).call[String](request, RetryEligibility.Never)(using
          response => Right(response.text)),
        Right(body)
      )
      cleanEvents(telemetry, secrets)

    test(s"$label configured credentials are scrubbed from URI components before percent encoding"):
      val credentialRequest = request.copy(path = secrets.toList, query = secrets.map(secret => (secret, secret)).toList)
      val telemetry = RecordingTelemetry(false)
      val http      = FakeHttpPort.always(CodebergResponse(204, Map.empty, ResponseBody.Empty))
      assert(pipeline(auth, http, telemetry).callUnit(credentialRequest, RetryEligibility.Never).isRight)
      cleanEvents(telemetry, secrets)
      secrets.foreach: secret =>
        val encoded = Redaction.uri("", List(secret), Nil).stripPrefix("/")
        assert(
          telemetry.seen.forall(ctx => !ctx.uri.contains(encoded)),
          "encoded configured credential survived in a URI"
        )

  test("credential scrubbing cannot change whether the original query name marks an unknown value sensitive"):
    val secret            = "diagnostic-only-unconfigured-query-secret"
    val auth              = Auth.Token(valid(ApiToken.from("token")))
    val credentialRequest = request.copy(query = List("token" -> secret))
    val telemetry         = RecordingTelemetry(false)
    val http              = FakeHttpPort.always(CodebergResponse(204, Map.empty, ResponseBody.Empty))
    assert(pipeline(auth, http, telemetry).callUnit(credentialRequest, RetryEligibility.Never).isRight)
    cleanEvents(telemetry, Vector(secret))

  test("an error-body parsing bound cannot leave a clipped configured credential in tolerant-parser fields"):
    val body      = "x".repeat(ApiPipeline.MaxErrorBodyBytes - 5) + token + " trailing data"
    val telemetry = RecordingTelemetry(false)
    val http      = FakeHttpPort.always(CodebergResponse(403, Map.empty, ResponseBody.utf8(body)))
    val error     = errorOf(pipeline(
      Auth.Token(valid(ApiToken.from(token))),
      http,
      telemetry
    ).callUnit(request, RetryEligibility.Never))
    error match
      case CodebergError.Api(_, 403, parsed, _) => assertEquals(parsed, ApiErrorBody.Empty)
      case other                                => fail(s"expected Api, got $other")
    cleanEvents(telemetry, Vector(token))
    cleanError(error, Vector(token))
    telemetry.observed.foreach:
      case CodebergError.Api(_, 403, parsed, _) => assertEquals(parsed, ApiErrorBody.Empty)
      case other                                => fail(s"expected Api telemetry, got $other")
