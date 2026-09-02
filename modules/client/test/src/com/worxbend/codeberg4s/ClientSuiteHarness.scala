package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.client.FutureExec
import com.worxbend.codeberg4s.client.FutureTimer
import com.worxbend.codeberg4s.codec.ApiErrorBodyCodec
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.Telemetry
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.retry.Jitter
import com.worxbend.codeberg4s.retry.RetryPolicy
import com.worxbend.codeberg4s.transport.SttpHttpPort

import sttp.client4.Backend
import sttp.client4.GenericRequest
import sttp.client4.Response
import sttp.client4.testing.BackendStub
import sttp.client4.testing.RecordingBackend
import sttp.client4.testing.ResponseStub
import sttp.client4.testing.StubBody
import sttp.model.Header
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/** The stub backend, the pipeline and the request assertions every API suite in this module shares.
  *
  * Each API class is exercised the same way: answer it from a [[sttp.client4.testing.BackendStub]], record what it
  * dialled, and ask the same handful of questions of the recording — which method, which path, which query parameters,
  * which body, how many attempts, and whether the two rails (the convenience one that raises and the `attempt` one that
  * returns an `Either`) describe a failure identically. Written once here rather than once per suite, because a copy of
  * that sixty-line preamble is how one suite quietly stops asserting the query string while the others still do.
  *
  * '''Nothing here opens a socket.''' The subject of every suite mixing this in is the wiring, never the network.
  *
  * A suite mixes it in and adds only what is specific to its own surface — its fixtures, its response bodies, and a
  * one-line `onApi` that builds its API class on the pipeline [[onPipeline]] hands it.
  */
trait ClientSuiteHarness:
  self: FunSuite =>

  /** The execution context every suite's futures run on — munit's own, so a hung assertion fails the test rather than
    * the JVM.
    */
  given executionContext: ExecutionContext = munitExecutionContext

  /** The effect the APIs are built with. Exposed rather than kept inside [[onPipeline]] because a suite constructs its
    * own API instance at the call site, where the context parameter has to be resolvable.
    */
  given exec: Exec[Future] = FutureExec()

  /** The instance every suite pretends to talk to. */
  val Instance: BaseUri = orFail(BaseUri.from("https://forge.example/api/v1"))

  /** The prefix every asserted path starts with — [[Instance]] as the plain string an assertion interpolates. */
  val Root: String = "https://forge.example/api/v1"

  /** How sttp renders a request that carries no body at all, which is what [[bodyOf]] answers for one.
    *
    * Spelled out rather than left as a literal in a test, because "empty" reads like an empty string and is not one: it
    * is `NoBody.show`, and asserting `""` instead would fail while looking correct.
    */
  val NoBody: String = "empty"

  /** A backend answering every request with `status` and `body`. */
  def responding(status: Int, body: String): BackendStub[Future] =
    responding(status, body, Nil)

  /** A backend answering every request with `status`, `body` and `headers`. */
  def responding(status: Int, body: String, headers: List[Header]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status), headers))

  /** A backend answering every request with `status` and a body that is bytes rather than text.
    *
    * The archive endpoints need this: their body is a ZIP, and a stub that could only answer a `String` would prove
    * nothing about bytes surviving the trip.
    */
  def respondingBytes(status: Int, body: Array[Byte]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status)))

  /** A backend that answers one `503` and then `status` with `body` — how a retry is made observable. */
  def flakyThen(status: Int, body: String): BackendStub[Future] =
    cycling(stub(503, ""), stub(status, body))

  /** A backend that answers `first` once and `rest` from then on. */
  def cycling(first: Response[StubBody], rest: Response[StubBody]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(first, rest)

  /** A single canned response with a status and a body, for [[cycling]]. */
  def stub(status: Int, body: String): Response[StubBody] =
    ResponseStub.adjust(body, StatusCode(status))

  /** The URI the first recorded request dialled, query string and all. */
  def dialled(backend: RecordingBackend): String =
    firstRequest(backend).uri.toString

  /** The dialled URI without its query string. Written with `indexOf` rather than a character comparison because
    * `.scalafix.conf` bans universal equality outright.
    */
  def pathOf(backend: RecordingBackend): String =
    val uri   = dialled(backend)
    val query = uri.indexOf('?')

    if query < 0 then uri else uri.take(query)

  /** The query parameters of the first recorded request, in the order they were sent. */
  def queryOf(backend: RecordingBackend): List[(String, String)] =
    firstRequest(backend).uri.params.toSeq.toList

  /** The HTTP method of the first recorded request. */
  def methodOf(backend: RecordingBackend): String =
    firstRequest(backend).method.method

  /** The body of the first recorded request, as sttp renders it for display. */
  def bodyOf(backend: RecordingBackend): String =
    firstRequest(backend).body.show.stripPrefix("string: ")

  /** The `Content-Type` the first recorded request declared, if it declared one. */
  def contentTypeOf(backend: RecordingBackend): Option[String] =
    firstRequest(backend).header("Content-Type")

  /** How many requests reached `backend` — one more than the number of retries. */
  def attemptsOn(backend: RecordingBackend): Int =
    backend.allInteractions.size

  /** The first request recorded by `backend`, for an assertion no named accessor above covers. */
  def firstRequest(backend: RecordingBackend): GenericRequest[?, ?] =
    backend.allInteractions.headOption match
      case Some((request, _)) => request
      case None               => fail("no request reached the backend")

  /** A pagination window of `size` items starting at page `page`. */
  def window(page: Int, size: Int): PageParams =
    PageParams(orFail(PageNumber.from(page)), orFail(PageSize.from(size)))

  /** Builds the pipeline an API sits on over `backend`, and releases the timer whatever the outcome. */
  def onPipeline[A](backend: Backend[Future])(use: ApiPipeline[Future] => Future[A]): Future[A] =
    val config = CodebergConfig(Auth.Anonymous).copy(baseUri = Instance, retry = ClientSuiteHarness.PromptRetry)
    val timer  = FutureTimer()

    val pipeline = ApiPipeline[Future](
      SttpHttpPort(backend, config),
      config,
      timer,
      Telemetry.noOp[Future],
      ApiErrorBodyCodec.parse,
    )

    use(pipeline).transform: outcome =>
      timer.close()
      outcome

  /** Asserts that the convenience rail's raised failure and the typed rail's `Left` describe the same thing. */
  def assertRailsAgree[A](raised: Throwable, typed: Either[CodebergError, A]): Unit =
    (raised, typed) match
      case (CodebergException(convenience), Left(materialised)) =>
        assertEquals(summary(materialised), summary(convenience))
      case (convenience, materialised)                          =>
        fail(s"the rails disagreed: $convenience versus $materialised")

  /** The operation, status and message of an `Api` failure, which is what "the same failure" means here. */
  def summary(error: CodebergError): (String, Int, Option[String]) =
    error match
      case CodebergError.Api(ctx, status, body, _) => (ctx.operation, status, body.message)
      case other                                   => fail(s"expected an Api failure, got ${other.describe}")

  /** The operation id a failed call carried, so a test can assert an alert could name it. */
  def operationOf[A](result: Either[CodebergError, A]): String =
    result match
      case Left(CodebergError.Api(ctx, _, _, _)) => ctx.operation
      case other                                 => fail(s"expected an Api failure, got $other")

  /** The per-field messages a failed call carried back from the forge. */
  def detailsOf[A](result: Either[CodebergError, A]): List[String] =
    result match
      case Left(CodebergError.Api(_, _, body, _)) => body.errors
      case other                                  => fail(s"expected an Api failure, got $other")

  /** Unwraps a smart constructor in a fixture, failing the test rather than the call under test. */
  def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

/** The retry policy every suite mixing [[ClientSuiteHarness]] in runs under. */
object ClientSuiteHarness:

  /** Retries promptly and predictably: the default policy would make the retry tests take a quarter of a second. */
  val PromptRetry: RetryPolicy = RetryPolicy(
    maxAttempts       = 3,
    baseDelay         = 1.milli,
    maxDelay          = 5.millis,
    jitter            = Jitter.None,
    respectRetryAfter = false,
  )
