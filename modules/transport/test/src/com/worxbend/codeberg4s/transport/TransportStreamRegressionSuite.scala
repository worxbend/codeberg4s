package com.worxbend.codeberg4s.transport

import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.core.{
  AttemptOutcome,
  CodebergRequest,
  CodebergResponse,
  Exec,
  JitterSource,
  RetryEligibility,
  RetryEngine,
  Timer,
  TransportFailure
}
import com.worxbend.codeberg4s.retry.{Jitter, RetryPolicy}
import com.worxbend.codeberg4s.syntax.discard
import com.worxbend.codeberg4s.{CallContext, CodebergConfig, CodebergError, HttpMethod, TransportCause}

import sttp.capabilities.Effect
import sttp.client4.compression.{CompressionHandlers, Decompressor}
import sttp.client4.httpclient.HttpClientFutureBackend
import sttp.client4.wrappers.DelegateBackend
import sttp.client4.{Backend, GenericRequest, Response}

import munit.FunSuite

import scala.annotation.tailrec
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, FilterInputStream, IOException, InputStream}
import java.net.http.{HttpClient, HttpHeaders, HttpRequest, HttpResponse}
import java.net.{Authenticator, CookieHandler, ProxySelector, URI}
import java.time.Duration as JDuration
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}
import java.util.concurrent.{CompletableFuture, ExecutionException, Executor}
import java.util.zip.{DeflaterOutputStream, GZIPOutputStream}
import java.util.{List as JList, Map as JMap, Optional}
import javax.net.ssl.{SSLContext, SSLParameters, SSLSession}

/** Instrument at the JDK acquisition seam, retaining real pinned sttp guards, decompressors and response-description
  * cleanup. BackendStub does not implement native abnormal acquisition cleanup, so it cannot prove the native closure
  * contract.
  */
final class TransportStreamRegressionSuite extends FunSuite:
  private given ExecutionContext  = ExecutionContext.parasitic
  private val config              = CodebergConfig(Auth.Anonymous)
  private val request             = CodebergRequest("regression.stream", HttpMethod.Get, List("probe"), Nil, Nil, None)
  private val idle: Timer[Future] = new Timer[Future]:
    override def nowMillis: Future[Long]                       = Future.successful(0L)
    override def sleep(duration: FiniteDuration): Future[Unit] = Future.never

  for encoding <- List("gzip", "deflate") do
    for status <- List(200, 500) do
      test(s"$encoding $status decoded bytes consumed stay bounded and acquired streams close on limit exit"):
        val payload = Array.fill[Byte](1024 * 1024)(42)
        // Compressed input fits the bound; decoded input does not. The instrument
        // wraps the real decompressor, so it counts decoded bytes before allocation.
        val wire    = CountingStream(ByteArrayInputStream(compressed(payload, encoding)))
        val decoded = AtomicReference[CountingStream]()
        val backend = HttpClientFutureBackend.usingClient(
          AcquiringClient(wire, status, encoding),
          compressionHandlers = countingDecompressors(decoded),
        )
        val port    = SttpHttpPort(backend, config, idle)
        try
          val result = Await.result(port.send(request, "redacted", 8192L), 3.seconds)
          result match
            case Left(TransportFailure(_: TransportCause.ResponseTooLarge)) => ()
            case other => fail(s"decoded expansion bypassed the limit: $other")
          val reader = Option(decoded.get()).getOrElse(fail("decompressor was never entered"))
          assert(reader.consumed.get() <= 8192L + 1L, s"reader consumed ${reader.consumed.get()} decoded bytes")
          assert(reader.closed.get(), "decoded stream remained open after limit exit")
          assert(wire.closed.get(), "acquired compressed stream remained open after limit exit")
        finally Await.result(backend.close(), 3.seconds)

  test("positive submillisecond timeout is accepted by actual native request conversion"):
    val wire    = CountingStream(ByteArrayInputStream(Array[Byte](1, 2, 3)))
    val client  = AcquiringClient(wire, 200, "identity")
    val backend = HttpClientFutureBackend.usingClient(client)
    try
      val port   = SttpHttpPort(backend, config.copy(readTimeout = 1.nanosecond), idle)
      val result = Await.result(port.send(request, "redacted", 8192L), 3.seconds)
      assertEquals(result.map(_.status), Right(200))
      assertEquals(client.admitted.get().timeout().get().toMillis, 1L)
      assert(wire.closed.get())
    finally Await.result(backend.close(), 3.seconds)

  test("mapped stream closes the acquired body on read failure"):
    val wire    = CountingStream(new InputStream:
      override def read(): Int = throw IOException("synthetic read failure")
      override def read(bytes: Array[Byte], offset: Int, length: Int): Int = throw IOException("synthetic read failure"))
    val backend = HttpClientFutureBackend.usingClient(AcquiringClient(wire, 500, "identity"))
    try
      val result = Await.result(SttpHttpPort(backend, config, idle).send(request, "redacted", 8192L), 3.seconds)
      assert(result.isLeft, s"read failure was accepted: $result")
      assert(wire.closed.get(), "body remained open after reader failed")
    finally Await.result(backend.close(), 3.seconds)

  test("mapped stream closes the acquired body after successful read"):
    val wire    = CountingStream(ByteArrayInputStream(Array[Byte](1, 2, 3)))
    val backend = HttpClientFutureBackend.usingClient(AcquiringClient(wire, 200, "identity"))
    try
      val result = Await.result(SttpHttpPort(backend, config, idle).send(request, "redacted", 8192L), 3.seconds)
      assertEquals(result.map(_.body.bytes.toList), Right(List[Byte](1, 2, 3)))
      assert(wire.closed.get(), "body remained open after success")
    finally Await.result(backend.close(), 3.seconds)

  test("a response acquired after logical timeout is still consumed and closed without replacing the timeout"):
    val wire                 = CountingStream(ByteArrayInputStream(Array[Byte](1, 2, 3)))
    val arrival              = CompletableFuture[HttpResponse[InputStream]]()
    val deadline             = Promise[Unit]()
    val timer: Timer[Future] = new Timer[Future]:
      override def nowMillis: Future[Long]                       = Future.successful(0L)
      override def sleep(duration: FiniteDuration): Future[Unit] = deadline.future
    val client               = AcquiringClient(wire, 200, "identity", Some(arrival))
    val backend              = HttpClientFutureBackend.usingClient(client)
    try
      val pending  = SttpHttpPort(backend, config, timer).send(request, "redacted", 8192L)
      assert(!pending.isCompleted)
      deadline.success(()).discard
      val timedOut = Await.result(pending, 3.seconds)
      timedOut match
        case Left(TransportFailure(_: TransportCause.Timeout)) => ()
        case other                                             => fail(s"deadline did not win: $other")
      arrival.complete(client.response(client.admitted.get())).discard
      Await.result(wire.exited.future, 3.seconds)
      assert(wire.closed.get(), "late-acquired body remained open after eventual reader exit")
      assertEquals(wire.consumed.get(), 3L)
      assertEquals(Await.result(pending, 3.seconds), timedOut)
    finally Await.result(backend.close(), 3.seconds)

  // "repeat" catches a duplicate ordinary callback close replacing the primary,
  // but also exercises native abnormal cleanup after a reader throwable. The
  // production response map must own closure without that ordinary finalizer.
  for closing <- List("first", "repeat", "always") do
    for (name, limit, expected) <- List(
                                     (
                                       "decoded limit",
                                       2L,
                                       TransportCause.ResponseTooLarge("decoded stream length limit of 2 bytes exceeded")
                                     ),
                                     (
                                       "negative limit",
                                       -1L,
                                       TransportCause.Unknown("response byte limit must not be negative")
                                     ),
                                   )
    do
      test(s"native $name stays primary with $closing close IOException"):
        val stream  = ClosingFailureStream(ByteArrayInputStream(Array[Byte](1, 2, 3)), closing)
        val backend = HttpClientFutureBackend.usingClient(AcquiringClient(stream, 500, "identity"))
        try
          val result = Await.result(SttpHttpPort(backend, config, idle).send(request, "redacted", limit), 3.seconds)
          assertEquals(result, Left(TransportFailure(expected)))
          assert(stream.closes.get() >= 1, "cleanup was never attempted")
          if limit < 0L then assertEquals(stream.reads.get(), 0)
        finally Await.result(backend.close(), 3.seconds)

    test(s"native gzip acquisition preserves its primary IOException with $closing close IOException"):
      val primary         = IOException("primary gzip acquisition failure")
      val stream          = ClosingFailureStream(failingRead(primary), closing)
      val native          = HttpClientFutureBackend.usingClient(AcquiringClient(stream, 200, "gzip"))
      val physicalFailure = Promise[Throwable]()
      val backend         = new DelegateBackend[Future, Any](native) with Backend[Future]:
        override def send[T](call: GenericRequest[T, Any & Effect[Future]]): Future[Response[T]] =
          val sending = native.send(call)
          sending.onComplete {
            case Failure(error) => physicalFailure.trySuccess(error).discard
            case Success(_)     =>
              physicalFailure.tryFailure(IllegalStateException("gzip acquisition failure was accepted")).discard
          }(using ExecutionContext.parasitic)
          sending
      try
        val result = Await.result(SttpHttpPort(backend, config, idle).send(request, "redacted", 8L), 3.seconds)
        assertEquals(result, Left(TransportFailure(TransportCause.ConnectionFailed("primary gzip acquisition failure"))))
        assert(hasCause(Await.result(physicalFailure.future, 3.seconds), primary), "native acquisition lost its primary")
        if closing != "repeat" then
          assert(primary.getSuppressed.nonEmpty, "native abnormal cleanup lost its secondary failure")
        assert(stream.closes.get() >= 1, "acquisition failure never attempted cleanup")
      finally Await.result(backend.close(), 3.seconds)

    test(s"native reader IOException identity survives $closing close IOException"):
      val primary         = IOException("primary reader IOException")
      val stream          = ClosingFailureStream(failingRead(primary), closing)
      val native          = HttpClientFutureBackend.usingClient(AcquiringClient(stream, 500, "identity"))
      val physicalFailure = Promise[Throwable]()
      // Observe the real backend failure without replacing SttpHttpPort's
      // response description: a fix may legitimately prevent duplicate native
      // closes, and the test must exercise the production response policy.
      val backend         = new DelegateBackend[Future, Any](native) with Backend[Future]:
        override def send[T](call: GenericRequest[T, Any & Effect[Future]]): Future[Response[T]] =
          val sending = native.send(call)
          sending.onComplete {
            case Failure(error) => physicalFailure.trySuccess(error).discard
            case Success(_) => physicalFailure.tryFailure(IllegalStateException("reader failure was accepted")).discard
          }(using ExecutionContext.parasitic)
          sending
      try
        val result        = Await.result(SttpHttpPort(backend, config, idle).send(request, "redacted", 8L), 3.seconds)
        assertEquals(result, Left(TransportFailure(TransportCause.ConnectionFailed("primary reader IOException"))))
        val error         = Await.result(physicalFailure.future, 3.seconds)
        assert(hasCause(error, primary), "native finalizer discarded primary throwable identity")
        val retainedClose = primary.getSuppressed.exists:
          case _: IOException => true
          case _              => false
        assert(retainedClose, "secondary native close was not retained")
        assert(stream.closes.get() >= 1, "cleanup was never attempted")
      finally Await.result(backend.close(), 3.seconds)

    test(s"native boxed synthetic fatal remains a failed effect with $closing close IOException"):
      // Throw a nonfatal box from the physical Future callback, not a raw fatal
      // on a Future worker (which Scala may report without completing it).
      val fatal   = LinkageError("synthetic native primary linkage failure")
      val primary = ExecutionException("boxed synthetic fatal reader", fatal)
      val stream  = ClosingFailureStream(failingRead(primary), closing)
      val backend = HttpClientFutureBackend.usingClient(AcquiringClient(stream, 200, "identity"))
      try
        val sent = SttpHttpPort(backend, config, idle).send(request, "redacted", 8L)
        Await.ready(sent, 3.seconds).value match
          case Some(Failure(error)) =>
            assert(hasCause(error, primary), "native cleanup discarded the primary box")
            assert(hasCause(error, fatal), "native cleanup discarded the synthetic fatal cause identity")
          case other                => fail(s"fatal was laundered into the transport rail: $other")
        assert(stream.closes.get() >= 1, "cleanup was never attempted")
      finally Await.result(backend.close(), 3.seconds)

    if closing != "repeat" then
      test(s"native successful read with $closing close IOException fails legitimately"):
        val stream  = ClosingFailureStream(ByteArrayInputStream(Array[Byte](1, 2, 3)), closing)
        val backend = HttpClientFutureBackend.usingClient(AcquiringClient(stream, 200, "identity"))
        try
          val result = Await.result(SttpHttpPort(backend, config, idle).send(request, "redacted", 8L), 3.seconds)
          assertEquals(result, Left(TransportFailure(TransportCause.ConnectionFailed("secondary native close failure"))))
          assert(stream.closes.get() >= 1, "cleanup was never attempted")
        finally Await.result(backend.close(), 3.seconds)

  test("native decoded refusal plus close I/O is not retried by the real RetryEngine"):
    val attempts                  = AtomicInteger()
    val backoffs                  = AtomicInteger()
    val timer: Timer[Exec.Result] = new Timer[Exec.Result]:
      override def nowMillis: Exec.Result[Long]                       = Right(0L)
      override def sleep(duration: FiniteDuration): Exec.Result[Unit] =
        backoffs.incrementAndGet().discard
        Right(())
    val policy                    = RetryPolicy.Default.copy(jitter = Jitter.None)
    val engine  = RetryEngine[Exec.Result](policy, timer)(using Exec.eitherExec)(using JitterSource.Deterministic)
    val context = CallContext(request.operation, request.method, "redacted", None, 0L)
    // Only the retry interpreter is synchronous: each attempt still sends
    // through the actual pinned usingClient backend and SttpHttpPort response map.
    val result  =
      engine.runWith[CodebergResponse](request.operation, HttpMethod.Get, RetryEligibility.IdempotentOnly): _ =>
        attempts.incrementAndGet().discard
        val stream  = ClosingFailureStream(ByteArrayInputStream(Array[Byte](1, 2, 3)), "always")
        val backend = HttpClientFutureBackend.usingClient(AcquiringClient(stream, 200, "identity"))
        try
          val attempt = Await.result(SttpHttpPort(backend, config, idle).send(request, "redacted", 2L), 3.seconds)
          Right(AttemptOutcome(attempt.left.map(failure => CodebergError.Transport(context, failure.cause)), None))
        finally Await.result(backend.close(), 3.seconds)
    assertEquals(attempts.get(), 1, "oversized body was downloaded again after a secondary close failure")
    assertEquals(backoffs.get(), 0)
    assertEquals(
      result,
      Left(CodebergError.Transport(
        context,
        TransportCause.ResponseTooLarge("decoded stream length limit of 2 bytes exceeded")
      ))
    )

  for outcome <- List("refusal", "read failure") do
    test(s"native boxed cleanup fatal is not hidden by an ordinary $outcome"):
      val fatal    = LinkageError("synthetic boxed cleanup fatal")
      val boxed    = ExecutionException("boxed native cleanup", fatal)
      val delegate = if outcome == "read failure" then failingRead(IOException("ordinary read failure"))
      else ByteArrayInputStream(Array[Byte](1, 2, 3))
      val stream   = new FilterInputStream(delegate):
        override def close(): Unit =
          super.close()
          throw boxed
      val backend  = HttpClientFutureBackend.usingClient(AcquiringClient(stream, 200, "identity"))
      try
        val limit = if outcome == "refusal" then 2L else 8L
        val sent  = SttpHttpPort(backend, config, idle).send(request, "redacted", limit)
        Await.ready(sent, 3.seconds).value match
          case Some(Failure(error)) =>
            assert(hasCause(error, boxed), "cleanup box was discarded")
            assert(hasCause(error, fatal), "cleanup fatal cause was discarded")
          case other                => fail(s"boxed cleanup fatal became a library result: $other")
      finally Await.result(backend.close(), 3.seconds)

  for cleanup <- List("fatal", "interrupt") do
    for phase <- List("read", "decompression") do
      test(s"native $phase failure cannot hide suppressed $cleanup cleanup"):
        val primary           = IOException("primary native read failure")
        val unsafe: Throwable = if cleanup == "fatal" then LinkageError("synthetic suppressed cleanup")
        else InterruptedException("synthetic suppressed cleanup")
        val boxed             = ExecutionException("boxed suppressed cleanup", unsafe)
        val closes            = AtomicInteger()
        val delegate          = if phase == "read" then failingRead(primary)
        else ByteArrayInputStream(Array.fill[Byte](16)(0))
        val stream            = new FilterInputStream(delegate):
          override def close(): Unit =
            super.close()
            val count = closes.incrementAndGet()
            if phase == "decompression" || count > 1 then throw boxed
        val native            = HttpClientFutureBackend.usingClient(AcquiringClient(
          stream,
          200,
          if phase == "decompression" then "gzip" else "identity"
        ))
        val physicalFailure   = Promise[Throwable]()
        val backend           = new DelegateBackend[Future, Any](native) with Backend[Future]:
          override def send[T](call: GenericRequest[T, Any & Effect[Future]]): Future[Response[T]] =
            val sending = native.send(call)
            sending.onComplete {
              case Failure(error) => physicalFailure.trySuccess(error).discard
              case Success(_) => physicalFailure.tryFailure(IllegalStateException("native read was accepted")).discard
            }(using ExecutionContext.parasitic)
            sending
        try
          val sent     = SttpHttpPort(backend, config, idle).send(request, "redacted", 64L)
          val original = Await.result(physicalFailure.future, 3.seconds)
          Await.ready(sent, 3.seconds).value match
            case Some(Failure(delivered)) => assert(delivered.eq(original), "adapter changed the unsafe native failure")
            case other => fail(s"suppressed unsafe cleanup became a retryable library failure: $other")
          if phase == "read" then
            assert(hasCause(original, primary), "native primary reader identity was lost")
            assert(closes.get() >= 2, "repeat-only cleanup path was not exercised")
        finally Await.result(backend.close(), 3.seconds)

  private def failingRead(error: Throwable): InputStream = new InputStream:
    override def read(): Int                                             = throw error
    override def read(bytes: Array[Byte], offset: Int, length: Int): Int = throw error

  private def hasCause(error: Throwable, target: Throwable): Boolean =
    @tailrec
    def loop(current: Option[Throwable], seen: List[Throwable]): Boolean = current match
      case Some(value) if value eq target                              => true
      case Some(value) if seen.length < 16 && !seen.exists(_ eq value) =>
        loop(Option(value.getCause), value :: seen)
      case _                                                           => false
    loop(Some(error), Nil)

  private final class ClosingFailureStream(delegate: InputStream, mode: String) extends FilterInputStream(delegate):
    val reads: AtomicInteger  = AtomicInteger()
    val closes: AtomicInteger = AtomicInteger()

    override def read(): Int =
      reads.incrementAndGet().discard
      in.read()

    override def read(bytes: Array[Byte], offset: Int, length: Int): Int =
      reads.incrementAndGet().discard
      in.read(bytes, offset, length)

    override def close(): Unit =
      val number = closes.incrementAndGet()
      super.close()
      if mode == "always" || (mode == "first" && number == 1) || (mode == "repeat" && number > 1) then
        throw IOException("secondary native close failure")

  private def countingDecompressors(decoded: AtomicReference[CountingStream]): CompressionHandlers[Any, InputStream] =
    val original      = HttpClientFutureBackend.DefaultCompressionHandlers
    val decompressors = original.decompressors.map: delegate =>
      new Decompressor[InputStream]:
        override def encoding: String                      = delegate.encoding
        override def apply(body: InputStream): InputStream =
          val stream = CountingStream(delegate(body))
          decoded.set(stream)
          stream
    original.copy(decompressors = decompressors)

  private def compressed(payload: Array[Byte], encoding: String): Array[Byte] =
    val bytes      = ByteArrayOutputStream()
    val compressor = if encoding == "gzip" then GZIPOutputStream(bytes) else DeflaterOutputStream(bytes)
    try compressor.write(payload)
    finally compressor.close()
    bytes.toByteArray

  private final class CountingStream(delegate: InputStream) extends FilterInputStream(delegate):
    val consumed: AtomicLong  = AtomicLong()
    val closed: AtomicBoolean = AtomicBoolean()
    val exited: Promise[Unit] = Promise[Unit]()

    override def read(): Int =
      val result = in.read()
      if result >= 0 then consumed.incrementAndGet().discard
      result

    override def read(bytes: Array[Byte], offset: Int, length: Int): Int =
      val result = in.read(bytes, offset, length)
      if result > 0 then consumed.addAndGet(result.toLong).discard
      result

    override def close(): Unit =
      try super.close()
      finally
        closed.set(true)
        exited.trySuccess(()).discard

  /** This foreign JDK client intentionally returns InputStream bodies for the pinned backend's
    * BodyHandlers.ofInputStream path. It owns no executor/socket; the real sttp backend still converts each native
    * request and handles its body.
    */
  private final class AcquiringClient(
      stream: InputStream,
      status: Int,
      encoding: String,
      arrival: Option[CompletableFuture[HttpResponse[InputStream]]] = None,
  ) extends HttpClient:
    val admitted: AtomicReference[HttpRequest]            = AtomicReference()
    override def cookieHandler(): Optional[CookieHandler] = Optional.empty()
    override def connectTimeout(): Optional[JDuration]    = Optional.empty()
    override def followRedirects(): HttpClient.Redirect   = HttpClient.Redirect.NEVER
    override def proxy(): Optional[ProxySelector]         = Optional.empty()
    override def sslContext(): SSLContext                 = SSLContext.getDefault
    override def sslParameters(): SSLParameters           = SSLParameters()
    override def authenticator(): Optional[Authenticator] = Optional.empty()
    override def version(): HttpClient.Version            = HttpClient.Version.HTTP_1_1
    override def executor(): Optional[Executor]           = Optional.empty()

    override def send[T](request: HttpRequest, handler: HttpResponse.BodyHandler[T]): HttpResponse[T] =
      throw UnsupportedOperationException("fixture supports only the Future backend")

    override def sendAsync[T](
        request: HttpRequest,
        handler: HttpResponse.BodyHandler[T]
    ): CompletableFuture[HttpResponse[T]] =
      admitted.set(request)
      arrival.getOrElse(
        CompletableFuture.completedFuture(response(request))
      ).asInstanceOf[CompletableFuture[HttpResponse[T]]]

    override def sendAsync[T](
        request: HttpRequest,
        handler: HttpResponse.BodyHandler[T],
        push: HttpResponse.PushPromiseHandler[T],
    ): CompletableFuture[HttpResponse[T]] = sendAsync(request, handler)

    def response(nativeRequest: HttpRequest): HttpResponse[InputStream] = new HttpResponse[InputStream]:
      override def statusCode(): Int                                       = status
      override def request(): HttpRequest                                  = nativeRequest
      override def previousResponse(): Optional[HttpResponse[InputStream]] = Optional.empty()
      override def headers(): HttpHeaders                                  =
        val fields = if encoding == "identity" then JMap.of[String, JList[String]]()
        else JMap.of[String, JList[String]]("Content-Encoding", JList.of(encoding))
        HttpHeaders.of(fields, (_, _) => true)
      override def body(): InputStream                                     = stream
      override def sslSession(): Optional[SSLSession]                      = Optional.empty()
      override def uri(): URI                                              = nativeRequest.uri()
      override def version(): HttpClient.Version                           = HttpClient.Version.HTTP_1_1
