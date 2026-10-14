package com.worxbend.codeberg4s.transport

import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.core.{CodebergRequest, Timer, TransportFailure}
import com.worxbend.codeberg4s.syntax.discard
import com.worxbend.codeberg4s.{BaseUri, CodebergConfig, HttpMethod, TransportCause}

import sttp.capabilities.Effect
import sttp.client4.wrappers.DelegateBackend
import sttp.client4.{Backend, GenericRequest, Response}

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import munit.FunSuite

import scala.annotation.tailrec
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutorService, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors, TimeUnit}

/** Exercise the production default wrapper and its actual private body-I/O lane. Reflection only reads the owned
  * executor for assertions: no new production seam, substituted executor, fake owned wrapper, or client-field mutation.
  * All native peers bind loopback and barriers are released even after failure.
  */
final class OwnedBackendLifetimeSuite extends FunSuite:
  private val request = CodebergRequest("owned.lifetime", HttpMethod.Get, List("probe"), Nil, Nil, None)

  for phase <- List("headers", "body", "decompression failure") do
    test(s"owned close after logical timeout retains I/O until late $phase physically settles"):
      val entered              = CountDownLatch(1)
      val release              = CountDownLatch(1)
      val peerExited           = CountDownLatch(1)
      val peerFailure          = AtomicReference[Throwable]()
      val reports              = ConcurrentLinkedQueue[Throwable]()
      val caller               = ExecutionContext.fromExecutorService(
        Executors.newSingleThreadExecutor(),
        error => reports.add(error).discard
      )
      val workers              = Executors.newCachedThreadPool()
      val server               = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      val deadline             = Promise[Unit]()
      val timer: Timer[Future] = new Timer[Future]:
        override def nowMillis: Future[Long]                       = Future.successful(0L)
        override def sleep(duration: FiniteDuration): Future[Unit] = deadline.future
      val backend              =
        try SttpHttpPort.defaultBackend(3.seconds, caller)
        catch
          case error: Throwable =>
            server.stop(0)
            workers.shutdownNow().discard
            workers.awaitTermination(5L, TimeUnit.SECONDS).discard
            caller.shutdownNow().discard
            caller.awaitTermination(5L, TimeUnit.SECONDS).discard
            throw error
      // Read the executor actually owned by this production default backend,
      // not a pool built solely for the fixture.
      val io                   =
        try ownedIo(backend)
        catch
          case error: Throwable =>
            try Await.result(backend.close(), 3.seconds)
            finally
              server.stop(0)
              workers.shutdownNow().discard
              workers.awaitTermination(5L, TimeUnit.SECONDS).discard
              caller.shutdownNow().discard
              caller.awaitTermination(5L, TimeUnit.SECONDS).discard
            throw error
      val physical             = Promise[Response[Any]]()
      val observed             = new DelegateBackend[Future, Any](backend) with Backend[Future]:
        override def send[T](call: GenericRequest[T, Any & Effect[Future]]): Future[Response[T]] =
          val sending = backend.send(call)
          sending.onComplete {
            case Success(response) => physical.trySuccess(response.copy(body = response.body: Any)).discard
            case Failure(error)    => physical.tryFailure(error).discard
          }(using ExecutionContext.parasitic)
          sending
      try
        server.setExecutor(workers)
        server.createContext(
          "/api/v1",
          exchange =>
            try respondHeld(exchange, phase, entered, release)
            catch
              case _: IOException if phase == "decompression failure" => () // native acquisition cleanup may close peer
              case error: Throwable                                   => peerFailure.compareAndSet(null, error).discard
            finally
              exchange.close()
              peerExited.countDown()
        ).discard
        server.start()
        val base        = BaseUri.from(s"http://127.0.0.1:${server.getAddress.getPort}/api/v1")
          .fold(error => fail(error.toString), identity)
        val config      = CodebergConfig(Auth.Anonymous).copy(baseUri = base, readTimeout = 30.seconds)
        val pending     = SttpHttpPort(observed, config, timer)(using caller).send(request, "redacted", 64L)
          .map(identity)(using caller)
        val peerEntered = entered.await(5L, TimeUnit.SECONDS)
        assert(peerEntered, "peer never entered the physical barrier")
        if phase != "headers" then
          val reading = awaitNativeRead()
          assert(reading, "native backend never entered the held body read")
        assert(!physical.isCompleted, "physical send settled before the barrier was released")
        deadline.success(()).discard
        val timedOut    = Await.result(pending, 3.seconds)
        timedOut match
          case Left(TransportFailure(_: TransportCause.Timeout)) => ()
          case other => fail(s"logical deadline did not settle before physical release: $other")

        // Critical ordering: close is acknowledged while the peer/acquisition
        // or reader is still held. Neither logical timeout nor close drains it.
        val firstClose          = backend.close()
        val closeAcknowledged   = Await.result(firstClose, 3.seconds)
        assertEquals(closeAcknowledged, ())
        assert(firstClose eq backend.close(), "close acknowledgement was not idempotent")
        assert(!physical.isCompleted, "close was mistaken for physical drainage")
        assert(!io.isShutdown, "owned body-I/O lane shut down before physical settlement")
        val laneThread          = Await.result(Future(Thread.currentThread().getName)(using io), 3.seconds)
        assert(laneThread.startsWith("codeberg4s-http-io-"), "probe did not use the production owned lane")
        assert(!caller.isShutdown, "owned close shut down the caller continuation executor")
        val callerBeforeRelease = Await.result(Future(42)(using caller), 3.seconds)
        assertEquals(callerBeforeRelease, 42)

        release.countDown()
        val completed          = Await.ready(physical.future, 8.seconds).value
        if phase == "decompression failure" then
          completed match
            case Some(Failure(error)) =>
              assert(hasIoCause(error), s"malformed gzip acquisition was not an I/O failure: $error")
            case other                => fail(s"malformed gzip acquisition was accepted: $other")
        else
          completed match
            case Some(Success(response)) => response.body match
                case Right(bytes: Array[Byte]) => assertEquals(bytes.toList, List[Byte](1, 2, 3))
                case other => fail(s"late native response did not finish with its complete bytes: $other")
            case other                   => fail(s"late native completion/cleanup failed after owned close: $other")
        val peerFinished       = peerExited.await(5L, TimeUnit.SECONDS)
        assert(peerFinished, "peer did not exit after physical release")
        assertEquals(Option(peerFailure.get()), None, "local HTTP peer failed")
        val terminated         = io.awaitTermination(5L, TimeUnit.SECONDS)
        assert(terminated, "owned I/O executor did not shut down after physical completion/cleanup")
        assert(io.isShutdown)
        val unchangedLogical   = Await.result(pending, 3.seconds)
        assertEquals(unchangedLogical, timedOut, "late outcome replaced logical timeout")
        assertEquals(reports.asScala.toList, Nil, "late continuation/cleanup was rejected or reported a defect")
        assert(!caller.isShutdown)
        val callerAfterRelease = Await.result(Future(43)(using caller), 3.seconds)
        assertEquals(callerAfterRelease, 43)
      finally
        release.countDown()
        try
          Await.result(backend.close(), 3.seconds)
          // Force the local peer down only after giving the released operation
          // a bounded chance to finish. On RED this also unblocks native reads.
          if physical.isCompleted then Await.ready(physical.future, 3.seconds).discard
        finally
          server.stop(0)
          workers.shutdownNow().discard
          val peersTerminated  = workers.awaitTermination(5L, TimeUnit.SECONDS)
          // Test-only emergency cleanup follows the behavioral assertions; it
          // cannot turn a premature/missing production shutdown into GREEN.
          io.shutdownNow().discard
          val ioTerminated     = io.awaitTermination(5L, TimeUnit.SECONDS)
          caller.shutdownNow().discard
          val callerTerminated = caller.awaitTermination(5L, TimeUnit.SECONDS)
          assert(peersTerminated, "peer workers leaked after cleanup")
          assert(ioTerminated, "owned I/O workers leaked after cleanup")
          assert(callerTerminated, "caller workers leaked after cleanup")

  private def respondHeld(
      exchange: HttpExchange,
      phase: String,
      entered: CountDownLatch,
      release: CountDownLatch,
  ): Unit =
    if phase != "headers" then
      if phase == "decompression failure" then exchange.getResponseHeaders.set("Content-Encoding", "gzip")
      exchange.sendResponseHeaders(200, 0L)
      // GZIPInputStream blocks in native acquisition waiting for the next magic
      // byte; the ordinary decoded reader blocks waiting for the next body byte.
      exchange.getResponseBody.write(if phase == "decompression failure" then 0x1F else 1)
      exchange.getResponseBody.flush()
    entered.countDown()
    val released = release.await(15L, TimeUnit.SECONDS)
    if !released then throw IOException("test never released the local physical response barrier")
    if phase == "headers" then exchange.sendResponseHeaders(200, 0L)
    val rest     = phase match
      case "headers" => Array[Byte](1, 2, 3)
      case "body"    => Array[Byte](2, 3)
      case _         => Array.fill[Byte](16)(0) // deliberately invalid second gzip magic byte
    exchange.getResponseBody.write(rest)
    exchange.getResponseBody.flush()

  private def ownedIo(backend: Backend[Future]): ExecutionContextExecutorService =
    val field = backend.getClass.getDeclaredFields.find(_.getName == "io")
      .getOrElse(fail("production OwnedClientBackend no longer exposes its private io field to this assertion"))
    field.setAccessible(true)
    field.get(backend).asInstanceOf[Option[ExecutionContextExecutorService]]
      .getOrElse(fail("default backend has no owned body-I/O executor"))

  private def awaitNativeRead(): Boolean =
    // This observation accompanies the per-test held peer and captured pending
    // physical Future. Executor ownership/shutdown is asserted separately on
    // the exact executor read from this production wrapper, not from a name.
    @tailrec
    def poll(until: Long): Boolean =
      val found = Thread.getAllStackTraces.asScala.exists: (thread, stack) =>
        thread.getName.startsWith("codeberg4s-http-io-") && stack.exists(frame =>
          frame.getClassName.contains("HttpResponseInputStream") && frame.getMethodName == "read"
        )
      if found then true
      else if System.nanoTime() >= until then false
      else
        LockSupport.parkNanos(1000000L)
        poll(until)
    poll(System.nanoTime() + 5.seconds.toNanos)

  private def hasIoCause(error: Throwable): Boolean =
    @tailrec
    def loop(current: Option[Throwable], seen: List[Throwable]): Boolean = current match
      case Some(_: IOException)                                        => true
      case Some(value) if seen.length < 16 && !seen.exists(_ eq value) =>
        loop(Option(value.getCause), value :: seen)
      case _                                                           => false
    loop(Some(error), Nil)
