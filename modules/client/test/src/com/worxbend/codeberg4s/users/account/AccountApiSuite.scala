package com.worxbend.codeberg4s.users.account

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

/** The `BackendStub` harness the five account API suites share: nothing in any of them opens a socket.
  *
  * Five suites over one pipeline would otherwise carry five copies of the same twelve helpers, and a copy that quietly
  * forgot to release the timer would leak a thread per suite until someone noticed. Building the pipeline here, once,
  * also means every suite asserts against the same configuration — the same base URI, the same prompt retry policy — so
  * a difference between two suites is a difference in the endpoint and never in the harness.
  *
  * The subject of every suite is the wiring: which URI is dialled, which query parameters and which body are sent,
  * which calls may be repeated, and what each rail does with a failure. Decoding itself is asserted in `modules/codec`,
  * so the payloads are small hand-written bodies chosen to exercise a seam.
  *
  * '''No golden fixture backs this group.''' Every payload in these suites was written from `spec/swagger.v1.json`;
  * every endpoint under `/user` requires a token and the golden harvest was anonymous.
  */
abstract class AccountApiSuite extends FunSuite:

  protected given ExecutionContext = munitExecutionContext

  /** The instance every suite dials, and the prefix every asserted path starts with. */
  protected val Instance: BaseUri = orFail(BaseUri.from("https://forge.example/api/v1"))

  /** The `/user` root every path in this group is built on. */
  protected val Root: String = "https://forge.example/api/v1/user"

  /** A backend that answers `status` and `body` to anything. */
  protected def responding(status: Int, body: String): BackendStub[Future] =
    responding(status, body, Nil)

  /** A backend that answers `status`, `body` and `headers` to anything. */
  protected def responding(status: Int, body: String, headers: List[Header]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status), headers))

  /** A backend that answers `first` once and `rest` from then on — how a retry is made observable. */
  protected def cycling(first: Response[StubBody], rest: Response[StubBody]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(first, rest)

  /** A `503` followed by a success, for asserting whether a call is repeated. */
  protected def failingThenSucceeding(status: Int, body: String): BackendStub[Future] =
    cycling(ResponseStub.adjust("", StatusCode(503)), ResponseStub.adjust(body, StatusCode(status)))

  /** The dialled URI without its query string, written with `indexOf` because universal equality is banned. */
  protected def pathOf(backend: RecordingBackend): String =
    val uri   = dialled(backend)
    val query = uri.indexOf('?'.toInt)

    if query < 0 then uri else uri.take(query)

  /** The dialled query parameters, in the order they were sent. */
  protected def queryOf(backend: RecordingBackend): List[(String, String)] =
    firstRequest(backend).uri.params.toSeq.toList

  /** The dialled HTTP method. */
  protected def methodOf(backend: RecordingBackend): String =
    firstRequest(backend).method.method

  /** The request body as sent, with sttp's own rendering prefix stripped. */
  protected def bodyOf(backend: RecordingBackend): String =
    firstRequest(backend).body.show.stripPrefix("string: ")

  /** How many requests reached the backend, which is how a retry decision is asserted. */
  protected def attemptsOn(backend: RecordingBackend): Int =
    backend.allInteractions.size

  /** A pagination window, built from values that are validated rather than assumed. */
  protected def window(page: Int, size: Int): PageParams =
    PageParams(orFail(PageNumber.from(page)), orFail(PageSize.from(size)))

  /** Builds the pipeline this group's API classes sit on, and releases the timer whatever the outcome.
    *
    * `use` is a context function over [[com.worxbend.codeberg4s.core.Exec]] because every API class in this package
    * needs one to construct, and the instance is created here rather than by each suite — that is the whole point of
    * sharing the harness. A plain function would leave the `given` out of scope at the one place it is needed.
    */
  protected def onPipeline[A](backend: Backend[Future])(
      use: Exec[Future] ?=> ApiPipeline[Future] => Future[A]
  ): Future[A] =
    given Exec[Future] = FutureExec()

    val config = CodebergConfig(Auth.Anonymous).copy(baseUri = Instance, retry = AccountApiSuite.PromptRetry)
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
  protected def assertRailsAgree[A](raised: Throwable, typed: Either[CodebergError, A]): Unit =
    (raised, typed) match
      case (CodebergException(convenience), Left(materialised)) =>
        assertEquals(summary(materialised), summary(convenience))
      case (convenience, materialised)                          =>
        fail(s"the rails disagreed: $convenience versus $materialised")

  /** The three things about an API failure a caller can act on: which operation, which status, which message. */
  protected def summary(error: CodebergError): (String, Int, Option[String]) =
    error match
      case CodebergError.Api(ctx, status, body) => (ctx.operation, status, body.message)
      case other                                => fail(s"expected an Api failure, got ${other.describe}")

  /** The operation id a typed-rail failure carries, which is what an alert would name. */
  protected def operationOf[A](result: Either[CodebergError, A]): String =
    result match
      case Left(CodebergError.Api(ctx, _, _)) => ctx.operation
      case other                              => fail(s"expected an Api failure, got $other")

  /** The JSON path a decoding failure blames. */
  protected def decodingPathOf[A](result: Either[CodebergError, A]): String =
    result match
      case Left(CodebergError.DecodingFailed(_, _, path, _)) => path.render
      case other                                             => fail(s"expected a decoding failure, got $other")

  protected def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

  private def dialled(backend: RecordingBackend): String =
    firstRequest(backend).uri.toString

  private def firstRequest(backend: RecordingBackend): sttp.client4.GenericRequest[?, ?] =
    backend.allInteractions.headOption match
      case Some((request, _)) => request
      case None               => fail("no request reached the backend")

object AccountApiSuite:

  /** Retries promptly and predictably: the default policy would make the retry tests take a quarter of a second. */
  val PromptRetry: RetryPolicy = RetryPolicy(
    maxAttempts       = 3,
    baseDelay         = 1.milli,
    maxDelay          = 5.millis,
    jitter            = Jitter.None,
    respectRetryAfter = false,
  )

  /** The `401` body an endpoint under `/user` answers to an anonymous caller, as `golden/error/401-token-required.json`
    * captured it.
    */
  val UnauthorizedBody: String =
    """{"message":"token is required","url":"https://codeberg.org/api/swagger"}"""

  /** A `403` body, as Forgejo words a missing token scope. */
  val ForbiddenBody: String =
    """{"message":"token does not have at least one of required scope(s): [write:user]"}"""

  /** A `404` body for an object of this group the account does not own. */
  val NotFoundBody: String =
    """{"message":"not found","url":"https://codeberg.org/api/swagger","errors":["does not exist"]}"""
