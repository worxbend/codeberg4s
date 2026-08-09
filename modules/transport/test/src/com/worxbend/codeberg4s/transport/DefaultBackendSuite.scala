package com.worxbend.codeberg4s.transport

import com.worxbend.codeberg4s.syntax.discard

import sttp.client4.BackendOptions

import munit.FunSuite

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.ScalaDurationOps
import scala.jdk.OptionConverters.RichOptional

import java.net.Authenticator
import java.net.http.HttpClient
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** What [[SttpHttpPort.defaultBackend]] builds, and what closing it actually releases.
  *
  * These are the only tests in this module that create a real `java.net.http.HttpClient` rather than a `BackendStub`,
  * because the behaviour under test is a lifecycle and a stub has none. No test here sends a request, so nothing
  * touches the network: the JDK starts the client's selector thread when the client is built, and `isTerminated`
  * reports on it without a socket ever being opened.
  */
final class DefaultBackendSuite extends FunSuite:

  test("closing a backend that owns its JDK client terminates that client"):
    withExecutor: executor =>
      val client  = SttpHttpPort.defaultHttpClient(DefaultBackendSuite.ConnectTimeout, executor)
      val backend = SttpHttpPort.owning(client, executor)

      assert(!client.isTerminated, "a client that was never closed already reports itself terminated")

      backend.close().discard

      assert(
        client.awaitTermination(DefaultBackendSuite.TerminationLimit.toJava),
        "close() left the JDK HTTP client running",
      )

  test("closing the backend leaves the caller's execution context running"):
    withExecutor: executor =>
      val backend =
        SttpHttpPort.owning(SttpHttpPort.defaultHttpClient(DefaultBackendSuite.ConnectTimeout, executor), executor)

      backend.close().discard

      assert(!executor.isShutdown, "closing the backend shut down an execution context the caller owns")

  test("the client is built the way sttp's redirect wrapper needs it"):
    withExecutor: executor =>
      val client = SttpHttpPort.defaultHttpClient(DefaultBackendSuite.ConnectTimeout, executor)

      // sttp wraps every HttpClientFutureBackend in FollowRedirectsBackend,
      // which applies the redirect policy itself. A JDK client that followed
      // redirects as well would apply it twice, which is why sttp's own
      // defaultClient sets NEVER and why this one has to agree.
      assertEquals(client.followRedirects, HttpClient.Redirect.NEVER)
      assertEquals(client.connectTimeout.toScala, Some(DefaultBackendSuite.ConnectTimeout.toJava))
      assertEquals(client.executor.toScala, Option[Executor](executor))

  test("a proxy's credentials answer the proxy's own challenge"):
    val answer = SttpHttpPort.proxyCredentialsFor(Authenticator.RequestorType.PROXY, DefaultBackendSuite.ProxyAuth)

    assertEquals(answer.map(_.getUserName), Some("alice"))
    assertEquals(answer.map(_.getPassword.mkString), Some("s3cret"))

  test("a proxy's credentials are withheld from an origin server's challenge"):
    // A java.net.Authenticator is consulted for origin-server challenges too.
    // Answering one with the proxy's password would hand that password to
    // whichever host the request was aimed at.
    assertEquals(
      SttpHttpPort.proxyCredentialsFor(Authenticator.RequestorType.SERVER, DefaultBackendSuite.ProxyAuth),
      None,
    )

  /** Runs `use` against an execution context that is also an `ExecutorService`, and shuts that service down afterwards.
    *
    * Being an `Executor` is the point rather than an incidental detail: it is the property that made sttp decline to
    * release the HTTP client, so a fixture without it would exercise the one case the defect never reached.
    */
  private def withExecutor(use: ExecutionContextExecutorService => Unit): Unit =
    val executor = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())

    try use(executor)
    finally executor.shutdown()

object DefaultBackendSuite:

  private val ConnectTimeout: FiniteDuration = 3.seconds

  /** Credentials that must reach a proxy and no one else. */
  private val ProxyAuth: BackendOptions.ProxyAuth = BackendOptions.ProxyAuth("alice", "s3cret")

  /** Generous on purpose. An idle client terminates in single-digit milliseconds; this is a hang detector, not a race
    * the test is trying to win.
    */
  private val TerminationLimit: FiniteDuration = 10.seconds
