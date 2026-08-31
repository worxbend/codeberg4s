package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
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
import sttp.client4.testing.BackendStub
import sttp.client4.testing.RecordingBackend
import sttp.client4.testing.ResponseStub
import sttp.model.Header
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/** The stub-backend harness the four suites of this package share: nothing here opens a socket.
  *
  * Four API classes sit on one pipeline and one set of request-shape questions — which URI was dialled, which query
  * parameters and which body were sent, what each rail does with a failure, and whether a write was repeated. Writing
  * that harness once rather than four times is what `docs/LEDGER.md` asks for, and it also means a suite that forgot to
  * release the timer cannot exist.
  *
  * The subject of every suite is the wiring. Decoding itself is asserted in `modules/codec`, so the payloads here are
  * small hand-written bodies chosen to exercise a seam — and, as everywhere in this group, derived from
  * `spec/swagger.v1.json` rather than captured.
  */
trait HookApiSuite extends FunSuite:

  protected given ExecutionContext = munitExecutionContext

  /** The owner every suite addresses. */
  protected val Handle: Owner = orFail(Owner.from("Codeberg"))

  /** The repository every suite addresses. */
  protected val Name: RepoName = orFail(RepoName.from("Community"))

  /** The instance every suite dials. */
  protected val Instance: BaseUri = orFail(BaseUri.from("https://forge.example/api/v1"))

  /** The URI prefix every request in this package is expected to share. */
  protected val Repository: String = "https://forge.example/api/v1/repos/Codeberg/Community"

  /** A backend answering every request with one canned response. */
  protected def responding(status: Int, body: String): BackendStub[Future] =
    responding(status, body, Nil)

  /** A backend answering every request with one canned response and the given headers. */
  protected def responding(status: Int, body: String, headers: List[Header]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status), headers))

  /** A backend answering a retryable `503` first and then succeeding, for asserting retry eligibility. */
  protected def flakyThenOk(status: Int, body: String): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(
      ResponseStub.adjust("", StatusCode(503)),
      ResponseStub.adjust(body, StatusCode(status)),
    )

  /** The paging headers `docs/HAZARDS.md` §5 captured verbatim, with the host swapped for the stub's. */
  protected def pagedHeaders(total: Int, next: String): List[Header] =
    List(
      Header("X-Total-Count", total.toString),
      Header("Link", s"""<$next>; rel="next""""),
    )

  /** The URI the first request went to, query string included. */
  protected def dialled(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.toString
      case None               => fail("no request reached the backend")

  /** The dialled URI without its query string. Written with `indexOf` rather than a character comparison because
    * `.scalafix.conf` bans universal equality outright.
    */
  protected def pathOf(backend: RecordingBackend): String =
    val uri   = dialled(backend)
    val query = uri.indexOf('?')

    if query < 0 then uri else uri.take(query)

  /** The query parameters of the first request, in order. */
  protected def queryOf(backend: RecordingBackend): List[(String, String)] =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.params.toSeq.toList
      case None               => fail("no request reached the backend")

  /** The method of the first request. */
  protected def methodOf(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.method.method
      case None               => fail("no request reached the backend")

  /** The body of the first request, as text. */
  protected def bodyOf(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.body.show.stripPrefix("string: ")
      case None               => fail("no request reached the backend")

  /** Asserts that the convenience rail's raised failure and the typed rail's value describe the same thing. */
  protected def assertRailsAgree[A](raised: Throwable, typed: Either[CodebergError, A]): Unit =
    (raised, typed) match
      case (CodebergException(convenience), Left(materialised)) =>
        assertEquals(summary(materialised), summary(convenience))
      case (convenience, materialised)                          =>
        fail(s"the rails disagreed: $convenience versus $materialised")

  /** The operation, status and message of an `Api` failure, for comparing the two rails. */
  protected def summary(error: CodebergError): (String, Int, Option[String]) =
    error match
      case CodebergError.Api(ctx, status, body) => (ctx.operation, status, body.message)
      case other                                => fail(s"expected an Api failure, got ${other.describe}")

  /** A pagination window. */
  protected def window(number: Int, size: Int): PageParams =
    PageParams(orFail(PageNumber.from(number)), orFail(PageSize.from(size)))

  /** Builds the pipeline this package's API classes sit on, and releases the timer whatever the outcome.
    *
    * `build` receives the [[com.worxbend.codeberg4s.core.Exec]] explicitly rather than finding it as a `given`: the
    * instance is created here, so it does not exist at the point where a suite writes its lambda.
    */
  protected def onPipeline[A, T](backend: Backend[Future], build: (ApiPipeline[Future], Exec[Future]) => T)(
      use: T => Future[A]
  ): Future[A] =
    val exec: Exec[Future] = FutureExec()

    val config = CodebergConfig(Auth.Anonymous).copy(baseUri = Instance, retry = HookApiSuite.PromptRetry)
    val timer  = FutureTimer()

    val pipeline = ApiPipeline[Future](
      SttpHttpPort(backend, config),
      config,
      timer,
      Telemetry.noOp[Future](using exec),
      ApiErrorBodyCodec.parse,
    )(using exec)

    use(build(pipeline, exec)).transform: outcome =>
      timer.close()
      outcome

  /** Unwraps a fixture built through a smart constructor, failing the suite rather than the assertion. */
  protected def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

/** Values the harness shares with every suite that mixes it in. */
object HookApiSuite:

  /** A 404 shaped like `golden/error/404-repo-not-found.json`: a Go symbol for a message, and the useful text in
    * `errors`.
    */
  val NotFoundBody: String =
    """{"message":"GetRepositoryByOwnerAndName","url":"https://codeberg.org/api/swagger","errors":["repo not found"]}"""

  /** Retries promptly and predictably: the default policy would make the retry tests take a quarter of a second. */
  val PromptRetry: RetryPolicy = RetryPolicy(
    maxAttempts       = 3,
    baseDelay         = 1.milli,
    maxDelay          = 5.millis,
    jitter            = Jitter.None,
    respectRetryAfter = false,
  )
