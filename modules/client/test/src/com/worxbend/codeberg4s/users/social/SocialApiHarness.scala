package com.worxbend.codeberg4s.users.social

import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.ValidationError
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

/** The stub backend, the pipeline and the assertions the three suites of this group share.
  *
  * Three API classes sit on one pipeline and one retry policy, and each of them needs the same six questions asked of a
  * recorded request — which method, which path, which query, which body, how many attempts, and do the two rails agree.
  * Writing that once is what stops the three suites from drifting into three slightly different notions of "the request
  * that was sent"; the alternative was copying sixty lines of harness three times, which is how one copy quietly stops
  * asserting the query string.
  *
  * Nothing here opens a socket. [[onBackend]] builds the whole pipeline over a [[sttp.client4.testing.BackendStub]] and
  * releases the timer whatever the outcome.
  */
trait SocialApiHarness:
  self: FunSuite =>

  /** The pool munit already runs the suite's futures on; declared here so the three suites do not each declare one. */
  given executionContext: ExecutionContext = munitExecutionContext

  /** The effect instance every API class in this group is constructed with. */
  given exec: Exec[Future] = FutureExec()

  /** The instance every suite in this group pretends to talk to. */
  val Instance: BaseUri = orFail(BaseUri.from("https://forge.example/api/v1"))

  /** The prefix every asserted path starts with. */
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

  /** A backend that answers `first` once and `rest` from then on — how a retry is made observable. */
  def cycling(first: Response[StubBody], rest: Response[StubBody]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(first, rest)

  /** A response with a status and a body, for [[cycling]]. */
  def stub(status: Int, body: String): Response[StubBody] =
    ResponseStub.adjust(body, StatusCode(status))

  /** The dialled URI without its query string, written with `indexOf` because universal equality is banned. */
  def pathOf(backend: RecordingBackend): String =
    val uri   = dialled(backend)
    val query = uri.indexOf('?')

    if query < 0 then uri else uri.take(query)

  /** The query parameters of the first request that reached `backend`, in wire order. */
  def queryOf(backend: RecordingBackend): List[(String, String)] =
    firstRequest(backend).uri.params.toSeq.toList

  /** The method of the first request that reached `backend`. */
  def methodOf(backend: RecordingBackend): String =
    firstRequest(backend).method.method

  /** The body of the first request that reached `backend`, as the string it was rendered to. */
  def bodyOf(backend: RecordingBackend): String =
    firstRequest(backend).body.show.stripPrefix("string: ")

  /** How many requests reached `backend` — one more than zero retries. */
  def attemptsOn(backend: RecordingBackend): Int =
    backend.allInteractions.size

  /** A window of `size` items starting at page `page`. */
  def window(page: Int, size: Int): PageParams =
    PageParams(orFail(PageNumber.from(page)), orFail(PageSize.from(size)))

  /** Builds `api` on a pipeline over `backend`, and releases the timer whatever the outcome. */
  def onBackend[A, B](backend: Backend[Future])(build: ApiPipeline[Future] => B)(use: B => Future[A]): Future[A] =
    val config = CodebergConfig(Auth.Anonymous)
      .copy(baseUri = Instance, retry = SocialApiHarness.PromptRetry)
    val timer  = FutureTimer()

    val pipeline = ApiPipeline[Future](
      SttpHttpPort(backend, config),
      config,
      timer,
      Telemetry.noOp[Future],
      ApiErrorBodyCodec.parse,
    )

    use(build(pipeline)).transform: outcome =>
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
      case CodebergError.Api(ctx, status, body) => (ctx.operation, status, body.message)
      case other                                => fail(s"expected an Api failure, got ${other.describe}")

  /** The operation id a failed call carried, so a test can assert an alert could name it. */
  def operationOf[A](result: Either[CodebergError, A]): String =
    result match
      case Left(CodebergError.Api(ctx, _, _)) => ctx.operation
      case other                              => fail(s"expected an Api failure, got $other")

  /** Unwraps a smart constructor in a fixture, failing the test rather than the call under test. */
  def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

  private def dialled(backend: RecordingBackend): String =
    firstRequest(backend).uri.toString

  private def firstRequest(backend: RecordingBackend): sttp.client4.GenericRequest[?, ?] =
    backend.allInteractions.headOption match
      case Some((request, _)) => request
      case None               => fail("no request reached the backend")

/** The retry policy the group's suites run under. */
object SocialApiHarness:

  /** Retries promptly and predictably: the default policy would make the retry tests take a quarter of a second. */
  val PromptRetry: RetryPolicy = RetryPolicy(
    maxAttempts       = 3,
    baseDelay         = 1.milli,
    maxDelay          = 5.millis,
    jitter            = Jitter.None,
    respectRetryAfter = false,
  )
