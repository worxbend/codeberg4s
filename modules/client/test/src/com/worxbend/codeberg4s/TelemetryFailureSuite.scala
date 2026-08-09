package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.client.GuardedTelemetry
import com.worxbend.codeberg4s.core.Telemetry

import sttp.client4.Backend
import sttp.client4.testing.BackendStub
import sttp.client4.testing.ResponseStub
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** The promise on [[CodebergClient.apply]]: a telemetry sink that breaks must not break the call it was watching.
  *
  * A sink is application code the library agreed to run on its own request path, and there are two ways for that code
  * to go wrong — it throws where it stands, or it hands back a `Future` that later fails. Neither is a
  * [[CodebergError]], so neither is something the caller of `repos.get` can act on; both used to reach that caller
  * anyway. Every test below asserts the same thing from a different angle: the response still arrives.
  */
final class TelemetryFailureSuite extends FunSuite:

  private given ExecutionContext = munitExecutionContext

  private val Instance: BaseUri = BaseUri.from("https://forge.example/api/v1") match
    case Right(value) => value
    case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

  test("a sink that throws inside onRequest does not fail the call"):
    onClient(TelemetryFailureSuite.throwing): client =>
      client.version.get().map(version => assertEquals(version.raw, TelemetryFailureSuite.Version))

  test("a sink that throws inside onRequest does not fail the typed rail either"):
    onClient(TelemetryFailureSuite.throwing): client =>
      client.version.attempt
        .get()
        .map(result => assertEquals(result.map(_.raw), Right(TelemetryFailureSuite.Version)))

  test("a sink whose onRequest returns a failed Future does not fail the call"):
    onClient(TelemetryFailureSuite.failing): client =>
      client.version.get().map(version => assertEquals(version.raw, TelemetryFailureSuite.Version))

  test("a sink whose onRequest returns a failed Future does not fail the typed rail either"):
    onClient(TelemetryFailureSuite.failing): client =>
      client.version.attempt
        .get()
        .map(result => assertEquals(result.map(_.raw), Right(TelemetryFailureSuite.Version)))

  test("a fatal error from a sink is not swallowed"):
    val guarded = GuardedTelemetry(TelemetryFailureSuite.fatal)

    guarded.onRequest(TelemetryFailureSuite.SomeCall).failed.map: thrown =>
      assertEquals(rootCause(thrown).getClass, classOf[OutOfMemoryError])

  test("an interrupt from a sink is not swallowed"):
    val guarded = GuardedTelemetry(TelemetryFailureSuite.interrupted)

    guarded.onRequest(TelemetryFailureSuite.SomeCall).failed.map: thrown =>
      assertEquals(rootCause(thrown).getClass, classOf[InterruptedException])

  // --- assertions -----------------------------------------------------------

  /** The throwable underneath however many `ExecutionException`s `Future` wrapped it in on the way here.
    *
    * Completing a promise with a fatal throwable boxes it, so the failure a test observes is the wrapper and the
    * identity worth asserting on is the thing inside.
    */
  private def rootCause(thrown: Throwable): Throwable =
    Option(thrown.getCause) match
      case Some(cause) => rootCause(cause)
      case None        => thrown

  // --- fixtures -------------------------------------------------------------

  /** Runs `use` against a client that answers `200` with a version payload and reports to `telemetry`. */
  private def onClient[A](telemetry: Telemetry[Future])(use: CodebergClient => Future[A]): Future[A] =
    val config = CodebergConfig(Auth.Anonymous).copy(baseUri = Instance)
    val client = CodebergClient.usingBackend(config, responding, telemetry)

    use(client).transform: outcome =>
      client.close()
      outcome

  private def responding: Backend[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest
      .thenRespond(ResponseStub.adjust(s"""{"version": "${TelemetryFailureSuite.Version}"}""", StatusCode(200)))

object TelemetryFailureSuite:

  private val Version: String = "12.0.1+gitea-1.22.0"

  /** What an ordinarily broken sink reports. Not a [[CodebergError]], so nothing downstream can turn it into one. */
  private def boom: RuntimeException = IllegalStateException("the telemetry sink is broken")

  /** A sink that does `request` when a call is announced and nothing at all afterwards.
    *
    * The four sinks below break the same callback and differ only in how they break it, so they are one definition with
    * four arguments rather than four near-identical anonymous classes. `request` is by-name because one of the four
    * throws rather than returning.
    */
  private def brokenSink(request: => Future[Unit]): Telemetry[Future] = new Telemetry[Future]:

    override def onRequest(ctx: CallContext): Future[Unit] = request

    override def onResponse(ctx: CallContext, status: Int): Future[Unit] = Future.unit

    override def onError(ctx: CallContext, error: CodebergError): Future[Unit] = Future.unit

  /** Throws where it stands, before any `Future` exists to carry the failure. */
  private def throwing: Telemetry[Future] = brokenSink(throw boom)

  /** Returns normally and fails afterwards. */
  private def failing: Telemetry[Future] = brokenSink(Future.failed(boom))

  /** Reports that the process is no longer sound. The guard must let this through untouched. */
  private def fatal: Telemetry[Future] = brokenSink(Future.failed(OutOfMemoryError("the heap is gone")))

  /** Reports that someone asked for cancellation. Swallowing that would turn a cancellation into a hang. */
  private def interrupted: Telemetry[Future] = brokenSink(Future.failed(InterruptedException("cancelled")))

  /** A context to hand a callback that is being tested on its own, away from a request. */
  private val SomeCall: CallContext = CallContext("version.get", HttpMethod.Get, "https://forge.example", None, 0L)
