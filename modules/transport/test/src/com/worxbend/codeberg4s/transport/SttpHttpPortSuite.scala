package com.worxbend.codeberg4s.transport

import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.TransportCause
import com.worxbend.codeberg4s.UserAgent
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.ApiToken
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.auth.Password
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.CodebergResponse
import com.worxbend.codeberg4s.core.RequestBody
import com.worxbend.codeberg4s.core.ResponseBody
import com.worxbend.codeberg4s.core.Timer
import com.worxbend.codeberg4s.core.TransportFailure

import sttp.capabilities.StreamMaxLengthExceededException
import sttp.client4.Backend
import sttp.client4.GenericRequest
import sttp.client4.SttpClientException
import sttp.client4.basicRequest
import sttp.client4.testing.BackendStub
import sttp.client4.testing.RecordingBackend
import sttp.client4.testing.ResponseStub
import sttp.model.Header
import sttp.model.Method
import sttp.model.StatusCode
import sttp.model.Uri

import munit.FunSuite

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpTimeoutException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException

/** Request-shape and failure-classification tests for [[SttpHttpPort]].
  *
  * Everything runs against `BackendStub`, so no test touches the network. Assertions are on what the adapter puts on
  * the wire — the URI, the headers, the method — because that is what silently breaks in a client library.
  */
final class SttpHttpPortSuite extends FunSuite:

  private val Secret: String = "cb-0123456789abcdef-secret"

  /** Runs stub callbacks on the calling thread, so a recorded interaction is visible as soon as the future completes. */
  private given ExecutionContext = ExecutionContext.parasitic

  /** A timer whose deadlines never fire, for every test that is not about the whole-exchange bound. */
  private val Idle: Timer[Future] = ManualTimer()

  /** A [[com.worxbend.codeberg4s.core.Timer]] whose sleeps complete only when the test says so, or fail on demand.
    *
    * The whole-exchange bound is a race between the send and a sleep on the timer, so the test has to hold both ends: a
    * stub backend that never answers, and a deadline it can fire by hand. Nothing here waits a real millisecond.
    */
  private final class ManualTimer extends Timer[Future]:
    private val armed = ListBuffer.empty[(FiniteDuration, Promise[Unit])]

    override def nowMillis: Future[Long] = Future.successful(0L)

    override def sleep(duration: FiniteDuration): Future[Unit] =
      val pending = Promise[Unit]()
      armed.append((duration, pending))
      pending.future

    /** Every duration a sleep was armed for, in order. */
    def requested: List[FiniteDuration] = armed.toList.map((duration, _) => duration)

    /** Elapses every armed deadline. */
    def fire(): Unit = armed.foreach((_, pending) => pending.trySuccess(()))

  /** A timer that has been closed: every sleep is refused, as `FutureTimer.sleep` refuses one after `close()`. */
  private object ClosedTimer extends Timer[Future]:
    override def nowMillis: Future[Long] = Future.successful(0L)

    override def sleep(duration: FiniteDuration): Future[Unit] =
      Future.failed(RejectedExecutionException("timer closed"))

  test("path segments are percent-encoded, including a slash a validated type would never allow"):
    val backend = recording(respondingOk)
    val port    = SttpHttpPort(backend, configFor(Auth.Anonymous), Idle)

    send(port, awkwardRequest).map: _ =>
      assertEquals(
        sent(backend).uri.toString,
        "https://forge.example/api/v1/repos/ow%20ner/re%2Fpo/issues?state=open&q=a+b%26c&page=2",
      )

  test("the method is taken from the domain request"):
    val backend = recording(respondingOk)
    val port    = SttpHttpPort(backend, configFor(Auth.Anonymous), Idle)

    send(port, awkwardRequest.copy(method = HttpMethod.Patch)).map: _ =>
      assertEquals(sent(backend).method, Method.PATCH)

  test("a token is sent as the AuthorizationHeaderToken scheme, not as Bearer"):
    val backend = recording(respondingOk)
    val port    = SttpHttpPort(backend, configFor(Auth.Token(token(Secret))), Idle)

    send(port, awkwardRequest).map: _ =>
      assertEquals(headerOf(backend, "authorization"), Some(s"token $Secret"))

  test("basic credentials are sent as standard basic auth"):
    val backend = recording(respondingOk)
    val port    = SttpHttpPort(backend, configFor(Auth.Basic("dependabot", password("hunter2"))), Idle)

    send(port, awkwardRequest).map: _ =>
      assertEquals(headerOf(backend, "authorization"), Some("Basic ZGVwZW5kYWJvdDpodW50ZXIy"))

  test("an anonymous client sends no Authorization header at all"):
    val backend = recording(respondingOk)
    val port    = SttpHttpPort(backend, configFor(Auth.Anonymous), Idle)

    send(port, awkwardRequest).map: _ =>
      assertEquals(headerOf(backend, "authorization"), None)

  test("a caller's Authorization header cannot override the configured credential"):
    val backend = recording(respondingOk)
    val port    = SttpHttpPort(backend, configFor(Auth.Token(token(Secret))), Idle)
    val request = awkwardRequest.copy(headers = List("Authorization" -> "token someone-elses-token"))

    send(port, request).map: _ =>
      assertEquals(valuesOf(backend, "authorization"), List(s"token $Secret"))

  test("a caller's Authorization header is dropped whatever case it spells the name in"):
    val backend = recording(respondingOk)
    val port    = SttpHttpPort(backend, configFor(Auth.Anonymous), Idle)
    val request = awkwardRequest.copy(headers = List("authorization" -> "token someone-elses-token"))

    send(port, request).map: _ =>
      assertEquals(valuesOf(backend, "authorization"), Nil)

  test("a caller's Proxy-Authorization header never reaches the wire"):
    val backend = recording(respondingOk)
    val port    = SttpHttpPort(backend, configFor(Auth.Anonymous), Idle)
    val request = awkwardRequest.copy(headers = List("Proxy-Authorization" -> "Basic c29tZTpvbmU="))

    send(port, request).map: _ =>
      assertEquals(valuesOf(backend, "proxy-authorization"), Nil)

  test("a caller's Content-Type still overrides the one the body implies"):
    val backend = recording(respondingOk)
    val port    = SttpHttpPort(backend, configFor(Auth.Anonymous), Idle)
    val request = awkwardRequest.copy(
      method  = HttpMethod.Post,
      headers = List("Content-Type" -> "text/plain; charset=utf-8"),
      body    = Some(RequestBody.Json("# Title")),
    )

    send(port, request).map: _ =>
      assertEquals(valuesOf(backend, "content-type"), List("text/plain; charset=utf-8"))

  test("the configured user agent is sent"):
    val agent  = orFail(UserAgent.from("codeberg4s-test/1.0"))
    val config = configFor(Auth.Anonymous).copy(userAgent = agent)

    val backend = recording(respondingOk)
    val port    = SttpHttpPort(backend, config, Idle)

    send(port, awkwardRequest).map: _ =>
      assertEquals(headerOf(backend, "user-agent"), Some("codeberg4s-test/1.0"))

  test("the configured user agent wins over one a caller supplied"):
    val backend = recording(respondingOk)
    val port    = SttpHttpPort(backend, configFor(Auth.Anonymous), Idle)
    val request = awkwardRequest.copy(headers = List("User-Agent" -> "someone-else"))

    send(port, request).map: _ =>
      assertEquals(headerOf(backend, "user-agent"), Some(UserAgent.Default.value))

  test("a caller's own header is sent"):
    val backend = recording(respondingOk)
    val port    = SttpHttpPort(backend, configFor(Auth.Anonymous), Idle)
    val request = awkwardRequest.copy(headers = List("X-Forgejo-OTP" -> "123456"))

    send(port, request).map: _ =>
      assertEquals(headerOf(backend, "x-forgejo-otp"), Some("123456"))

  test("a JSON body is sent with the JSON content type"):
    val backend = recording(respondingOk)
    val port    = SttpHttpPort(backend, configFor(Auth.Anonymous), Idle)
    val request = awkwardRequest.copy(method = HttpMethod.Post, body = Some(RequestBody.Json("""{"title":"x"}""")))

    send(port, request).map: _ =>
      assertEquals(headerOf(backend, "content-type"), Some("application/json"))

  test("a 500 is a response, not a transport failure"):
    val backend = recording(responding(500, Nil, "upstream exploded"))
    val port    = SttpHttpPort(backend, configFor(Auth.Anonymous), Idle)

    send(port, awkwardRequest).map: result =>
      assertEquals(result, Right(CodebergResponse(500, Map.empty, ResponseBody.utf8("upstream exploded"))))

  test("response header names are lowercased and repeated values are kept in order"):
    val headers = List(Header("X-Total-Count", "1590"), Header("Link", "<a>; rel=\"next\""), Header("Link", "<b>"))
    val backend = recording(responding(200, headers, "[]"))
    val port    = SttpHttpPort(backend, configFor(Auth.Anonymous), Idle)

    send(port, awkwardRequest).map: result =>
      assertEquals(headersOf(result).get("x-total-count"), Some(List("1590")))
      assertEquals(headersOf(result).get("link"), Some(List("<a>; rel=\"next\"", "<b>")))

  test("an unresolvable host is a DNS failure"):
    causeOf(new UnknownHostException("forge.invalid")).map: cause =>
      assertEquals(cause, TransportCause.Dns("forge.invalid"))

  test("a refused connection is a connection failure"):
    causeOf(new ConnectException("connection refused")).map: cause =>
      assertEquals(cause, TransportCause.ConnectionFailed("connection refused"))

  test("a reset socket is a connection failure"):
    causeOf(new SocketException("connection reset")).map: cause =>
      assertEquals(cause, TransportCause.ConnectionFailed("connection reset"))

  test("a socket read timeout is a timeout"):
    causeOf(new SocketTimeoutException("read timed out")).map: cause =>
      assertEquals(cause, TransportCause.Timeout("read timed out"))

  test("an HTTP client timeout is a timeout"):
    causeOf(new HttpTimeoutException("request timed out")).map: cause =>
      assertEquals(cause, TransportCause.Timeout("request timed out"))

  test("a TLS failure is classified as TLS"):
    causeOf(new javax.net.ssl.SSLException("certificate expired")).map: cause =>
      assertEquals(cause, TransportCause.Tls("certificate expired"))

  test("an interrupted call is classified as interrupted"):
    causeOf(new InterruptedException("interrupted")).map: cause =>
      assertEquals(cause, TransportCause.Interrupted("interrupted"))

  test("a plain I/O failure with no case of its own is a connection failure, so it is retried"):
    causeOf(new EOFException("unexpected end of stream")).map: cause =>
      assertEquals(cause, TransportCause.ConnectionFailed("unexpected end of stream"))

  test("a closed channel is an I/O failure, and so a connection failure"):
    causeOf(new ClosedChannelException()).map: cause =>
      assertEquals(cause, TransportCause.ConnectionFailed("java.nio.channels.ClosedChannelException"))

  test("a java.util.concurrent timeout is a timeout"):
    causeOf(new TimeoutException("waited too long")).map: cause =>
      assertEquals(cause, TransportCause.Timeout("waited too long"))

  test("a programming error from a backend is unknown, which the retry engine does not repeat"):
    // An IllegalStateException is what the JDK client throws once it has been
    // shut down, and an UnsupportedOperationException is what a user-supplied
    // backend throws for something it does not implement. Neither is I/O, and
    // neither heals by waiting.
    causeOf(new UnsupportedOperationException("streaming is not supported")).map: cause =>
      assertEquals(cause, TransportCause.Unknown("streaming is not supported"))

  test("a body that passed the configured bound is classified as too large, not as unknown"):
    // ResponseTooLarge is never retried, and neither is Unknown any more, but the
    // two are still different answers: one says the bound worked and the other
    // says something is wrong with the client.
    causeOf(StreamMaxLengthExceededException(1024L)).map: cause =>
      assertEquals(cause, TransportCause.ResponseTooLarge("Stream length limit of 1024 bytes exceeded"))

  test("the same failure is recognised through the sttp exception that wraps it"):
    // This is the shape a real backend produces: sttp maps the internal exception
    // to SttpClientException.ReadException before it reaches the recover block, so
    // matching only the outermost type would classify every oversized body as unknown.
    val wrapped = SttpClientException.ReadException(sttpRequest, StreamMaxLengthExceededException(1024L))

    causeOf(wrapped).map: cause =>
      assertEquals(cause, TransportCause.ResponseTooLarge("Stream length limit of 1024 bytes exceeded"))

  test("a request carries the bound the caller asked for, not one the adapter chose"):
    val backend = recording(respondingOk)
    val port    = SttpHttpPort(backend, configFor(Auth.Anonymous), Idle)

    send(port, awkwardRequest).map: _ =>
      assertEquals(sent(backend).options.maxResponseBodyLength, Some(CodebergConfig.DefaultMaxResponseBodyBytes))

  test("a larger bound reaches sttp unchanged, which is how an archive download is served"):
    val backend = recording(respondingOk)
    val port    = SttpHttpPort(backend, configFor(Auth.Anonymous), Idle)

    send(port, awkwardRequest, CodebergConfig.DefaultMaxDownloadBodyBytes).map: _ =>
      assertEquals(sent(backend).options.maxResponseBodyLength, Some(CodebergConfig.DefaultMaxDownloadBodyBytes))

  test("the bound is whatever the caller passed, never a number the adapter kept"):
    val backend = recording(respondingOk)
    val port    = SttpHttpPort(backend, configFor(Auth.Anonymous).copy(maxResponseBodyBytes = 111L), Idle)

    send(port, awkwardRequest, 222L).map: _ =>
      assertEquals(sent(backend).options.maxResponseBodyLength, Some(222L))

  test("an exception this library does not recognise is unknown, never dropped"):
    causeOf(new IllegalStateException("something else entirely")).map: cause =>
      assertEquals(cause, TransportCause.Unknown("something else entirely"))

  test("a very long failure message is truncated"):
    causeOf(new IllegalStateException("x".repeat(500))).map: cause =>
      assertEquals(cause, TransportCause.Unknown(s"${"x".repeat(SttpHttpPort.MaxDetailLength)}..."))

  test("the token reaches the Authorization header and nothing else this adapter produces"):
    val backend = recording(failingWith(new IllegalStateException("boom")))
    val port    = SttpHttpPort(backend, configFor(Auth.Token(token(Secret))), Idle)

    send(port, awkwardRequest).map: result =>
      val rendered = List(port.toString, result.toString, sent(backend).uri.toString).mkString(" | ")

      assertEquals(headerOf(backend, "authorization"), Some(s"token $Secret"))
      assert(!rendered.contains(Secret), rendered)

  // --- the whole-exchange bound --------------------------------------------

  test("a response whose body never completes fails as a timeout once the read timeout elapses"):
    // sttp's readTimeout maps onto the JDK's HttpRequest timeout, which fires
    // only until the headers arrive; a server that stalls mid-body would hang
    // the Future forever without this race.
    val timer  = ManualTimer()
    val config = configFor(Auth.Anonymous).copy(readTimeout = 7.seconds)
    val port   = SttpHttpPort(neverAnswering, config, timer)

    val result = send(port, awkwardRequest)

    assert(!result.isCompleted, "the send completed before either the body or the deadline")
    assertEquals(timer.requested, List(7.seconds))

    timer.fire()

    assertEquals(result.value.map(_.toOption), Some(Some(Left(TransportFailure(SttpHttpPort.exchangeTimedOut(config))))))

  test("the timeout detail names the bound that fired and nothing about the request"):
    val config = configFor(Auth.Anonymous).copy(readTimeout = 7.seconds)

    assertEquals(
      SttpHttpPort.exchangeTimedOut(config),
      TransportCause.Timeout("no complete response within the read timeout of 7 seconds"),
    )

  test("a response that arrives in time is unaffected by the deadline that later elapses"):
    val timer = ManualTimer()
    val port  = SttpHttpPort(respondingOk, configFor(Auth.Anonymous), timer)

    send(port, awkwardRequest).map: result =>
      timer.fire()

      assertEquals(result.map(_.status), Right(200))

  test("a deadline the timer refuses leaves the send to decide, so a closed client does not fail an answered call"):
    val port = SttpHttpPort(respondingOk, configFor(Auth.Anonymous), ClosedTimer)

    send(port, awkwardRequest).map(result => assertEquals(result.map(_.status), Right(200)))

  test("the JDK's own header timeout is still armed on the request, alongside the whole-exchange bound"):
    val backend = recording(respondingOk)
    val config  = configFor(Auth.Anonymous).copy(readTimeout = 7.seconds)
    val port    = SttpHttpPort(backend, config, Idle)

    send(port, awkwardRequest).map(_ => assertEquals(sent(backend).options.readTimeout, 7.seconds))

  test("a base URI sttp cannot parse fails the call instead of throwing"):
    val backend = recording(respondingOk)
    val config  = configFor(Auth.Anonymous).copy(baseUri = orFail(BaseUri.from("https://forge.example:nope/api/v1")))
    val port    = SttpHttpPort(backend, config, Idle)

    send(port, awkwardRequest).map: result =>
      assertEquals(result, Left(TransportFailure(TransportCause.Unknown(SttpHttpPort.UnparseableBaseUri))))

  // --- fixtures -------------------------------------------------------------

  private def awkwardRequest: CodebergRequest =
    CodebergRequest(
      operation = "issues.list",
      method    = HttpMethod.Get,
      path      = List("repos", "ow ner", "re/po", "issues"),
      query     = List("state" -> "open", "q" -> "a b&c", "page" -> "2"),
      headers   = Nil,
      body      = None,
    )

  private def configFor(auth: Auth): CodebergConfig =
    CodebergConfig(auth).copy(baseUri = orFail(BaseUri.from("https://forge.example/api/v1")))

  private def send(
      port: SttpHttpPort,
      request: CodebergRequest,
      maxBodyBytes: Long = CodebergConfig.DefaultMaxResponseBodyBytes,
  ): Future[Either[TransportFailure, CodebergResponse]] =
    port.send(request, "https://forge.example/api/v1/repos/ow%20ner", maxBodyBytes)

  private def causeOf(error: Throwable): Future[TransportCause] =
    val port = SttpHttpPort(failingWith(error), configFor(Auth.Anonymous), Idle)

    send(port, awkwardRequest).map:
      case Left(failure) => failure.cause
      case Right(other)  => fail(s"expected a transport failure, got $other")

  private def headersOf(result: Either[TransportFailure, CodebergResponse]): Map[String, List[String]] =
    result match
      case Right(response) => response.headers
      case Left(failure)   => fail(s"expected a response, got $failure")

  // --- stub backends --------------------------------------------------------

  private def respondingOk: BackendStub[Future] =
    responding(200, Nil, "{}")

  private def responding(status: Int, headers: List[Header], body: String): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status), headers))

  /** A minimal sttp request, only so an `SttpClientException` can be built the way a real backend builds one. */
  private def sttpRequest: GenericRequest[?, ?] =
    basicRequest.get(Uri.unsafeParse("https://forge.example/api/v1"))

  private def failingWith(error: Throwable): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenThrow(error)

  /** A backend whose response never completes — a server that sent its headers and then stalled mid-body. */
  private def neverAnswering: BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespondF(Future.never)

  private def recording(delegate: BackendStub[Future]): Backend[Future] & RecordingBackend =
    RecordingBackend(delegate)

  private def sent(backend: RecordingBackend): GenericRequest[?, ?] =
    backend.allInteractions.headOption match
      case Some((request, _)) => request
      case None               => fail("no request reached the backend")

  private def headerOf(backend: RecordingBackend, name: String): Option[String] =
    sent(backend).headers.find(_.is(name)).map(_.value)

  /** Every value sent under `name`, in order.
    *
    * [[headerOf]] reports the first match and so cannot tell "sent once" from "sent twice with different values", which
    * is exactly the difference the credential tests are about. `Header.is` compares the name case-insensitively, the
    * way HTTP does.
    */
  private def valuesOf(backend: RecordingBackend, name: String): List[String] =
    sent(backend).headers.filter(_.is(name)).map(_.value).toList

  // --- validated fixtures ---------------------------------------------------

  private def token(value: String): ApiToken =
    orFail(ApiToken.from(value))

  private def password(value: String): Password =
    orFail(Password.from(value))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
