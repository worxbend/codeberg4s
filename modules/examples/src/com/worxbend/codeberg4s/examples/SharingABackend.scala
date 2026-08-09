package com.worxbend.codeberg4s.examples

import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.syntax.discard
import com.worxbend.codeberg4s.transport.SttpHttpPort

import sttp.client4.Backend

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

/** One HTTP connection pool, two clients, and an explicit answer to "who closes what".
  *
  * ==Running it==
  *
  * {{{
  * ./mill modules.examples.runMain com.worxbend.codeberg4s.examples.SharingABackend
  * }}}
  *
  * ==Environment==
  *
  * None. It talks anonymously to codeberg.org through a backend this program creates.
  *
  * ==The two constructors differ only in ownership==
  *
  *   - `CodebergClient(config)` creates a JDK-HTTP-client sttp backend, configured with `config.connectTimeout`, and
  *     '''owns''' it. `close()` shuts the backend down along with the scheduler thread. Use this unless the application
  *     already has a backend.
  *   - `CodebergClient.usingBackend(config, backend)` sends on a backend the '''caller''' owns. `close()` releases the
  *     scheduler thread and leaves the backend alone — it may well be shared with the rest of the application, and
  *     closing it here would break that application.
  *
  * So the teardown order below is: every client that was built on the backend, then the backend. Reversing it sends
  * requests down a pool that is already shutting down.
  *
  * ==`connectTimeout` moves with ownership==
  *
  * sttp models the connect timeout as a property of the backend, not of a request, so
  * [[com.worxbend.codeberg4s.CodebergConfig.connectTimeout]] is '''ignored''' by `usingBackend` — configure it on the
  * backend, as the `defaultBackend` call below does. [[com.worxbend.codeberg4s.CodebergConfig.readTimeout]] is applied
  * per request and is honoured either way.
  *
  * ==Why share one==
  *
  * Two reasons, and they are the only two. Talking to several instances — a self-hosted forge and codeberg.org, say —
  * needs one client per instance because a client's base URI and credentials are fixed at construction, but there is no
  * reason for each to own its own connection pool. And a test can pass an `sttp.client4.testing.BackendStub` here and
  * drive the whole library without a socket.
  */
object SharingABackend:

  /** The API root of an optional second instance, for example `https://forge.example/api/v1`. */
  val BaseUriVariable: String = "CODEBERG_BASE_URI"

  private val AwaitLimit: FiniteDuration = 2.minutes

  private val ConnectTimeout: FiniteDuration = 10.seconds

  def main(args: Array[String]): Unit =
    given executionContext: ExecutionContext = ExecutionContext.global

    // This program creates the backend, so this program closes it. The connect
    // timeout is passed here because usingBackend cannot apply the one in the
    // config — see the class comment.
    //
    // SttpHttpPort.defaultBackend rather than sttp's own HttpClientFutureBackend
    // because closing the latter does not release the JDK HTTP client under it,
    // so the pool this program is careful to close would outlive it anyway.
    val backend: Backend[Future] =
      SttpHttpPort.defaultBackend(ConnectTimeout, executionContext)

    // Two clients, one pool. They differ in configuration, not in transport.
    val codeberg: CodebergClient         = CodebergClient.usingBackend(CodebergConfig(Auth.Anonymous), backend)
    val mirrored: Option[CodebergClient] = selfHosted.map: uri =>
      CodebergClient.usingBackend(CodebergConfig(Auth.Anonymous).copy(baseUri = uri), backend)

    try
      ExampleConsole.heading("codeberg.org, through the shared backend")
      ExampleConsole.line(s"  version ${Await.result(codeberg.version.get(), AwaitLimit).raw}")

      mirrored match
        case None         =>
          ExampleConsole.line("")
          ExampleConsole.line(s"  set $BaseUriVariable to point a second client at a self-hosted instance")
        case Some(client) =>
          ExampleConsole.heading("the self-hosted instance, through the same backend")
          ExampleConsole.line(s"  version ${Await.result(client.version.get(), AwaitLimit).raw}")
    finally
      // Clients first: each releases its own scheduler thread and, because
      // neither of them owns the backend, leaves the pool running.
      codeberg.close()
      mirrored.foreach(client => client.close())

      // Then the thing this program owns. Closing a backend returns a Future
      // because sttp models it as an effect; the shutdown it starts does not
      // block, nothing here observes the Future, and `discard` says so at the
      // call site rather than letting -Wvalue-discard be switched off.
      backend.close().discard

  /** An optional second instance to talk to, so the example has a reason to share a pool.
    *
    * An absent variable and an unparseable one are different answers: the first is "no second client, carry on", the
    * second is a mistake worth naming. `BaseUri.from` reports it on the `"baseUri"` field.
    */
  private def selfHosted: Option[BaseUri] =
    sys.env.get(BaseUriVariable) match
      case None      => None
      case Some(raw) =>
        BaseUri.from(raw) match
          case Left(problem) =>
            ExampleConsole.line(s"ignoring $BaseUriVariable — invalid ${problem.field}: ${problem.message}")
            None
          case Right(uri)    => Some(uri)
