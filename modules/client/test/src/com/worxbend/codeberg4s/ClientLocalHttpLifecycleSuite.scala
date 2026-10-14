package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.retry.RetryPolicy
import com.worxbend.codeberg4s.syntax.discard

import com.sun.net.httpserver.HttpServer
import munit.FunSuite

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual owned-backend body reading must not monopolize a bounded caller executor. */
final class ClientLocalHttpLifecycleSuite extends FunSuite:
  for typed <- List(false, true) do
    test(
      s"owned backend settles the ${if typed then "typed" else "exception"} rail on a single-thread caller before peer release"
    ):
      val caller   = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())
      val peer     = Executors.newSingleThreadExecutor()
      val headers  = CountDownLatch(1)
      val release  = CountDownLatch(1)
      val finished = CountDownLatch(1)
      val server   = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      server.setExecutor(peer)
      server.createContext(
        "/api/v1/version",
        exchange =>
          try
            exchange.sendResponseHeaders(200, 0L)
            exchange.getResponseBody.write('{'.toInt)
            exchange.getResponseBody.flush()
            headers.countDown()
            if release.await(10L, TimeUnit.SECONDS) then exchange.getResponseBody.write('}'.toInt)
          catch case _: IOException => () // logical timeout or bounded reader may abandon this peer
          finally
            exchange.close()
            finished.countDown()
      ).discard
      server.start()
      val base     = BaseUri.from(s"http://127.0.0.1:${server.getAddress.getPort}/api/v1").fold(
        error => fail(error.toString),
        identity
      )
      val config = CodebergConfig(Auth.Anonymous).copy(baseUri = base, readTimeout = 1.second, retry = RetryPolicy.Off)
      val client   = CodebergClient(config)(using caller)
      try
        val pending: Future[Either[CodebergError, ServerVersion]] = if typed then client.version.attempt.get()
        else
          client.version.get().map[Either[CodebergError, ServerVersion]](Right(_))(using caller).recover {
            case exception: CodebergException => Left(exception.error)
          }(using caller)
        assert(headers.await(5L, TimeUnit.SECONDS), "peer did not send headers")
        Await.result(pending, 4.seconds) match
          case Left(CodebergError.Transport(_, _: TransportCause.Timeout)) => ()
          case other => fail(s"expected a logical timeout before releasing the stalled body, got $other")
        assertEquals(Await.result(Future(42)(using caller), 3.seconds), 42)
        assert(!caller.isShutdown, "client must not shut down its caller executor")
      finally
        release.countDown()
        client.close()
        Await.result(client.released, 5.seconds)
        assert(finished.await(5L, TimeUnit.SECONDS), "peer did not finish after release")
        server.stop(0)
        peer.shutdownNow().discard
        caller.shutdownNow().discard
        assert(peer.awaitTermination(5L, TimeUnit.SECONDS))
        assert(caller.awaitTermination(5L, TimeUnit.SECONDS))
