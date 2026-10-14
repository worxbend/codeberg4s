package com.worxbend.codeberg4s.transport

import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.core.{CodebergRequest, CodebergResponse, RequestBody, Timer, TransportFailure}
import com.worxbend.codeberg4s.syntax.discard
import com.worxbend.codeberg4s.{BaseUri, CodebergConfig, HttpMethod, TransportCause}

import sttp.client4.Backend

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.FunSuite

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

import java.io.{BufferedReader, ByteArrayOutputStream, IOException, InputStreamReader}
import java.net.{InetSocketAddress, ServerSocket}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.zip.{DeflaterOutputStream, GZIPOutputStream}

/** Real pinned JDK/sttp backend; every peer binds only loopback on an ephemeral port. */
final class TransportLocalHttpRegressionSuite extends FunSuite:
  private given ExecutionContext = ExecutionContext.parasitic

  private val idle: Timer[Future] = new Timer[Future]:
    override def nowMillis: Future[Long]                       = Future.successful(0L)
    override def sleep(duration: FiniteDuration): Future[Unit] = Future.never

  private val json   = ("{\"value\":\"" + "x".repeat(4096) + "\"}").getBytes(StandardCharsets.UTF_8)
  private val binary = Array.tabulate[Byte](4096)(index => (index % 8).toByte)

  for encoding <- List("identity", "gzip", "deflate") do
    for (kind, payload, contentType) <- List(
                                          ("JSON", json, "application/json; charset=utf-8"),
                                          ("binary", binary, "application/octet-stream"),
                                        )
    do
      for status <- List(200, 500) do
        for chunked <- List(false, true) do
          for margin <- List(-1, 0, 1) do
            test(s"$encoding $kind $status ${framing(chunked)} decoded limit margin $margin"):
              val wire  = compressed(payload, encoding)
              val limit = payload.length.toLong + margin
              if encoding != "identity" then
                assert(wire.length.toLong < limit, "compressed guard must not mask decoded guard")
              withServer(exchange => respond(exchange, status, contentType, encoding, wire, chunked)): server =>
                withBackend: backend =>
                  val result = send(server, backend, limit)
                  if margin < 0 then assertTooLarge(result)
                  else
                    val response = successful(result)
                    assertEquals(response.status, status)
                    assertEquals(response.body.bytes.toList, payload.toList)

  test("a small body under a Long bound beyond Int.MaxValue remains readable"):
    withServer(exchange => respond(exchange, 200, "application/octet-stream", "identity", binary, true)): server =>
      withBackend: backend =>
        val result = send(server, backend, Int.MaxValue.toLong + 1L)
        assertEquals(successful(result).body.bytes.toList, binary.toList)

  test("decompressed text keeps the declared non-UTF8 charset"):
    val text    = "café"
    val payload = text.getBytes(StandardCharsets.ISO_8859_1)
    withServer(exchange =>
      respond(exchange, 200, "text/plain; charset=iso-8859-1", "gzip", compressed(payload, "gzip"), true)
    ): server =>
      withBackend: backend =>
        val response = successful(send(server, backend, 128L))
        assertEquals(response.body.bytes.toList, payload.toList)
        assertEquals(response.body.text, text)

  for encoding <- List("gzip", "deflate") do
    test(s"malformed $encoding body is a transport failure rather than success"):
      val wire = Array[Byte](1, 2, 3, 4, 5)
      withServer(exchange => respond(exchange, 500, "application/json", encoding, wire, true)): server =>
        withBackend: backend =>
          val result = send(server, backend, 128L)
          assert(result.isLeft, s"malformed compressed body was accepted: $result")

  test("early EOF with misleading Content-Length fails rather than returning a partial body"):
    // A raw socket guarantees physical EOF; HttpServer's fixed-length writer
    // otherwise reports the intentional underwrite through its own bookkeeping.
    val listener = ServerSocket()
    val worker   = Executors.newSingleThreadExecutor()
    try
      listener.bind(InetSocketAddress("127.0.0.1", 0))
      listener.setSoTimeout(5000)
      val peer = worker.submit[Unit](new java.util.concurrent.Callable[Unit]:
        override def call(): Unit =
          val socket = listener.accept()
          try
            socket.setSoTimeout(5000)
            val input = BufferedReader(InputStreamReader(socket.getInputStream, StandardCharsets.US_ASCII))
            var line  = input.readLine()
            while line != null && line.nonEmpty do line = input.readLine()
            val output = socket.getOutputStream
            output.write(
              "HTTP/1.1 200 OK\r\nContent-Length: 128\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII)
            )
            output.write(Array[Byte](1, 2, 3))
            output.flush()
          finally socket.close())
      withBackend: backend =>
        val uri    =
          BaseUri.from(s"http://127.0.0.1:${listener.getLocalPort}/api/v1").fold(error => fail(error.toString), identity)
        val config = CodebergConfig(Auth.Anonymous).copy(baseUri = uri, readTimeout = 5.seconds)
        val result = Await.result(SttpHttpPort(backend, config, idle).send(request(), "redacted", 256L), 8.seconds)
        assert(result.isLeft, s"partial fixed-length body was accepted: $result")
      peer.get(5L, TimeUnit.SECONDS)
    finally
      listener.close()
      worker.shutdownNow().discard
      val terminated = worker.awaitTermination(5L, TimeUnit.SECONDS)
      assert(terminated, "truncated-response peer did not terminate")

  private val shapes: List[(String, HttpMethod, Option[RequestBody])] = List(
    ("read", HttpMethod.Get, None),
    ("download", HttpMethod.Get, None),
    ("PUT JSON", HttpMethod.Put, Some(RequestBody.Json("{\"password\":\"synthetic-body-secret\"}"))),
    ("PATCH JSON", HttpMethod.Patch, Some(RequestBody.Json("{\"password\":\"synthetic-body-secret\"}"))),
    (
      "POST multipart",
      HttpMethod.Post,
      Some(RequestBody.Multipart("file", "probe.bin", binary, "application/octet-stream"))
    ),
  )

  for status <- List(301, 302, 303, 307, 308) do
    for (name, method, body) <- shapes do
      test(s"$status redirect for $name never calls its destination"):
        val destinations = AtomicInteger()
        val origins      = AtomicInteger()
        withServer(exchange =>
          destinations.incrementAndGet().discard
          exchange.getRequestBody.readAllBytes().discard
          respond(exchange, 200, "application/json", "identity", "{}".getBytes(StandardCharsets.UTF_8), false)
        ): destination =>
          withServer(exchange =>
            origins.incrementAndGet().discard
            exchange.getRequestBody.readAllBytes().discard
            exchange.getResponseHeaders.set("Location", s"${base(destination)}/destination")
            respond(exchange, status, "application/json", "identity", "{}".getBytes(StandardCharsets.UTF_8), false)
          ): origin =>
            withBackend: backend =>
              val response = successful(send(origin, backend, 8192L, request(name, method, body)))
              assertEquals(destinations.get(), 0, "redirect destination received a request")
              assertEquals(origins.get(), 1)
              assertEquals(response.status, status)

  test("a same-origin read redirect is not followed either"):
    val destinations = AtomicInteger()
    withServer(exchange =>
      if exchange.getRequestURI.getPath.endsWith("destination") then
        destinations.incrementAndGet().discard
        respond(exchange, 200, "application/json", "identity", json, false)
      else
        exchange.getResponseHeaders.set("Location", "/api/v1/destination")
        respond(exchange, 302, "application/json", "identity", Array.emptyByteArray, true)
    ): server =>
      withBackend: backend =>
        val response = successful(send(server, backend, 8192L))
        assertEquals(destinations.get(), 0)
        assertEquals(response.status, 302)

  test("an accepted submillisecond timeout reaches the native backend without zero-duration refusal"):
    withServer(exchange => respond(exchange, 200, "application/json", "identity", json, false)): server =>
      withBackend: backend =>
        val config = configFor(server).copy(readTimeout = 999.microseconds)
        val result = Await.result(SttpHttpPort(backend, config, idle).send(request(), "redacted", 8192L), 5.seconds)
        // A 1 ms native header deadline may legitimately win on a loaded host;
        // successful transport construction, not microsecond wall time, is the contract.
        result match
          case Right(response)                                   => assertEquals(response.status, 200)
          case Left(TransportFailure(_: TransportCause.Timeout)) => ()
          case other => fail(s"validated timeout was rejected by native conversion: $other")

  test("owned backend stalled reader cannot starve a single-thread caller continuation"):
    val headers              = CountDownLatch(1)
    val release              = CountDownLatch(1)
    val finished             = CountDownLatch(1)
    val deadline             = Promise[Unit]()
    val timer: Timer[Future] = new Timer[Future]:
      override def nowMillis: Future[Long]                       = Future.successful(0L)
      override def sleep(duration: FiniteDuration): Future[Unit] = deadline.future
    val caller               = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())
    try
      withServer(exchange =>
        try
          exchange.sendResponseHeaders(200, 0L)
          exchange.getResponseBody.write('x'.toInt)
          exchange.getResponseBody.flush()
          headers.countDown()
          val released = release.await(10L, TimeUnit.SECONDS)
          if !released then throw IOException("test did not release stalled body")
          exchange.getResponseBody.write('y'.toInt)
        finally
          exchange.close()
          finished.countDown()
      ): server =>
        val backend = SttpHttpPort.defaultBackend(3.seconds, caller)
        try
          val port    = SttpHttpPort(backend, configFor(server), timer)(using caller)
          val pending = port.send(request(), "redacted", 8192L).map(identity)(using caller)
          try
            val sentHeaders = headers.await(5L, TimeUnit.SECONDS)
            assert(sentHeaders, "peer never sent headers")
            // Observe actual blocked read, not a sleep or a headers-only race.
            val reading     = awaitBlockedHttpReader()
            assert(reading, "backend never entered the stalled body reader")
            deadline.success(()).discard
            val logical     = Await.result(pending, 3.seconds)
            assertTooLate(logical)
            val callerAlive = Await.result(Future(42)(using caller), 3.seconds)
            assertEquals(callerAlive, 42)
            assert(!caller.isShutdown)
          finally
            release.countDown()
            val peerFinished = finished.await(5L, TimeUnit.SECONDS)
            assert(peerFinished, "peer failed to finish after release")
        finally Await.result(backend.close(), 5.seconds)
    finally
      release.countDown()
      caller.shutdown()
      val terminated = caller.awaitTermination(5L, TimeUnit.SECONDS)
      assert(terminated, "caller executor did not terminate after peer release")

  private def awaitBlockedHttpReader(): Boolean =
    val until = System.nanoTime() + 5.seconds.toNanos
    var found = false
    while !found && System.nanoTime() < until do
      found = Thread.getAllStackTraces.asScala.values.exists: stack =>
        stack.exists(frame => frame.getClassName.contains("HttpResponseInputStream") && frame.getMethodName == "read")
      if !found then LockSupport.parkNanos(1000000L)
    found

  private def framing(chunked: Boolean): String = if chunked then "chunked" else "fixed-length"

  private def compressed(payload: Array[Byte], encoding: String): Array[Byte] =
    if encoding == "identity" then payload
    else
      val bytes      = ByteArrayOutputStream()
      val compressor = if encoding == "gzip" then GZIPOutputStream(bytes) else DeflaterOutputStream(bytes)
      try compressor.write(payload)
      finally compressor.close()
      bytes.toByteArray

  private def request(
      operation: String = "regression.read",
      method: HttpMethod = HttpMethod.Get,
      body: Option[RequestBody] = None,
  ): CodebergRequest = CodebergRequest(operation, method, List("probe"), Nil, Nil, body)

  private def base(server: HttpServer): String = s"http://127.0.0.1:${server.getAddress.getPort}/api/v1"

  private def configFor(server: HttpServer): CodebergConfig =
    val uri = BaseUri.from(base(server)).fold(error => fail(error.toString), identity)
    CodebergConfig(Auth.Anonymous).copy(baseUri = uri, readTimeout = 5.seconds)

  private def send(
      server: HttpServer,
      backend: Backend[Future],
      limit: Long,
      call: CodebergRequest = request(),
  ): Either[TransportFailure, CodebergResponse] =
    Await.result(SttpHttpPort(backend, configFor(server), idle).send(call, "redacted", limit), 8.seconds)

  private def successful(result: Either[TransportFailure, CodebergResponse]): CodebergResponse =
    result.fold(error => fail(s"expected response: $error"), identity)

  private def assertTooLarge(result: Either[TransportFailure, CodebergResponse]): Unit =
    result match
      case Left(TransportFailure(_: TransportCause.ResponseTooLarge)) => ()
      case other => fail(s"expected decoded-byte bound to reject response: $other")

  private def assertTooLate(result: Either[TransportFailure, CodebergResponse]): Unit =
    result match
      case Left(TransportFailure(_: TransportCause.Timeout)) => ()
      case other => fail(s"expected whole-exchange timeout before peer release: $other")

  private def respond(
      exchange: HttpExchange,
      status: Int,
      contentType: String,
      encoding: String,
      wire: Array[Byte],
      chunked: Boolean,
  ): Unit =
    exchange.getResponseHeaders.set("Content-Type", contentType)
    if encoding != "identity" then exchange.getResponseHeaders.set("Content-Encoding", encoding)
    try
      exchange.sendResponseHeaders(status, if chunked then 0L else wire.length.toLong)
      exchange.getResponseBody.write(wire)
    finally exchange.close()

  private def withBackend(use: Backend[Future] => Unit): Unit =
    val backend = SttpHttpPort.defaultBackend(3.seconds, ExecutionContext.global)
    try use(backend)
    finally Await.result(backend.close(), 5.seconds)

  private def withServer(handle: HttpExchange => Unit)(use: HttpServer => Unit): Unit =
    val workers      = Executors.newCachedThreadPool()
    val server       = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val handlerError = AtomicReference[Throwable]()
    try
      server.setExecutor(workers)
      server.createContext(
        "/api/v1",
        exchange =>
          try handle(exchange)
          catch
            case _: IOException   => exchange.close() // bounded reader/timeout may close the peer early
            case error: Exception =>
              handlerError.compareAndSet(null, error).discard
              exchange.close()
      ).discard
      server.start()
      use(server)
      assertEquals(Option(handlerError.get()), None, "local peer handler failed")
    finally
      server.stop(0)
      workers.shutdownNow().discard
      val terminated = workers.awaitTermination(5L, TimeUnit.SECONDS)
      assert(terminated, "local HTTP server workers did not terminate")
