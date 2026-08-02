package com.worxbend.codeberg4s.issues

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
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName
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

/** The stub backend, the pipeline and the assertions the issue group's suites share.
  *
  * [[IssueApiSuite]] predates this trait and keeps its own copies; nothing here changes what that suite does. Every
  * suite added with the rest of the issue surface mixes this in instead, so that eight suites cannot drift into eight
  * different ideas of what "the dialled path" means.
  *
  * '''Nothing here opens a socket.''' The subject of every suite that uses it is the wiring — which URI is dialled,
  * which query parameters and which body are sent, what each rail does with a failure — never the network.
  */
trait IssueLaneHarness:
  self: FunSuite =>

  /** The execution context every suite's futures run on — munit's own, so a hung assertion fails the test rather than
    * the JVM.
    */
  given ExecutionContext = munitExecutionContext

  /** The effect the APIs are built with. Exposed rather than kept inside [[onPipeline]] because a suite constructs its
    * own API instance at the call site, where the context parameter has to be resolvable.
    */
  given Exec[Future] = FutureExec()

  /** The instance every suite pretends to talk to. */
  val Instance: BaseUri = orFail(BaseUri.from("https://forge.example/api/v1"))

  /** The repository owner every suite uses. */
  val Handle: Owner = orFail(Owner.from("Codeberg"))

  /** The repository every suite uses. */
  val Name: RepoName = orFail(RepoName.from("Community"))

  /** The issue every suite addresses, matching `golden/issue/single.json`. */
  val Number: IssueNumber = orFail(IssueNumber.from(2966L))

  /** The comment every suite addresses, matching the first element of `golden/issue/comments-list.json`. */
  val CommentRef: CommentId = orFail(CommentId.from(20366420L))

  /** A backend that answers every request with `status` and `body`. */
  def responding(status: Int, body: String): BackendStub[Future] =
    responding(status, body, Nil)

  /** A backend that answers every request with `status`, `body` and `headers`. */
  def responding(status: Int, body: String, headers: List[Header]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status), headers))

  /** A backend that answers a `503` first and then `body`, for asserting whether a call was repeated. */
  def flakyThen(status: Int, body: String): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(
      ResponseStub.adjust("", StatusCode(503)),
      ResponseStub.adjust(body, StatusCode(status)),
    )

  /** The URI the first recorded request dialled, query string and all. */
  def dialled(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.toString
      case None               => fail("no request reached the backend")

  /** The dialled URI without its query string. Written with `indexOf` rather than a character comparison because
    * `.scalafix.conf` bans universal equality outright.
    */
  def pathOf(backend: RecordingBackend): String =
    val uri   = dialled(backend)
    val query = uri.indexOf('?')

    if query < 0 then uri else uri.take(query)

  /** The query parameters of the first recorded request, in the order they were sent. */
  def queryOf(backend: RecordingBackend): List[(String, String)] =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.params.toSeq.toList
      case None               => fail("no request reached the backend")

  /** The HTTP method of the first recorded request. */
  def methodOf(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.method.method
      case None               => fail("no request reached the backend")

  /** The body of the first recorded request, as sttp renders it for display. */
  def bodyOf(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.body.show.stripPrefix("string: ")
      case None               => fail("no request reached the backend")

  /** A pagination window, for the suites that assert on `page` and `limit`. */
  def window(page: Int, size: Int): PageParams =
    PageParams(orFail(PageNumber.from(page)), orFail(PageSize.from(size)))

  /** Builds the pipeline this group's APIs sit on, and releases the timer whatever the outcome. */
  def onPipeline[A](backend: Backend[Future])(use: ApiPipeline[Future] => Future[A]): Future[A] =
    val config = CodebergConfig(Auth.Anonymous).copy(baseUri = Instance, retry = IssueLaneHarness.PromptRetry)
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

  /** The operation id, status and message of an `Api` failure, which is what the two rails must agree on. */
  def summary(error: CodebergError): (String, Int, Option[String]) =
    error match
      case CodebergError.Api(ctx, status, body) => (ctx.operation, status, body.message)
      case other                                => fail(s"expected an Api failure, got ${other.describe}")

  /** Unwraps a smart constructor in a fixture, failing the suite rather than the call under test. */
  def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

/** The retry policy and the error bodies the issue group's suites share. */
object IssueLaneHarness:

  /** Retries promptly and predictably: the default policy would make every retry test take a quarter of a second. */
  val PromptRetry: RetryPolicy = RetryPolicy(
    maxAttempts       = 3,
    baseDelay         = 1.milli,
    maxDelay          = 5.millis,
    jitter            = Jitter.None,
    respectRetryAfter = false,
  )

  /** A `404` shaped like `golden/error/404-repo-not-found.json`: a Go symbol for a message, and the useful text in
    * `errors`.
    */
  val NotFoundBody: String =
    """{"message":"GetIssueByIndex","url":"https://codeberg.org/api/swagger","errors":["issue does not exist"]}"""
