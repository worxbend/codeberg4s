package com.worxbend.codeberg4s.notifications

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

import java.time.Instant

/** [[NotificationApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The subject is the wiring — which URI is dialled, which query parameters are sent, what each rail does with a
  * failure, what the paging headers are allowed to decide, and whether the retry claims this group makes are real.
  * Decoding itself is asserted against the synthetic capture in `modules/codec`, so the payloads here are small
  * hand-written bodies chosen to exercise a seam.
  *
  * Every body in this file is invented, as is every body this group has: `GET /notifications` answers `401` without a
  * token, so no capture exists. See [[NotificationThread]].
  */
final class NotificationApiSuite extends FunSuite:

  private given ExecutionContext = munitExecutionContext

  private val Handle: Owner = orFail(Owner.from("Codeberg"))

  private val Name: RepoName = orFail(RepoName.from("Community"))

  private val Thread: NotificationThreadId = orFail(NotificationThreadId.from(4821L))

  private val Instance: BaseUri = orFail(BaseUri.from("https://forge.example/api/v1"))

  // --- reads ----------------------------------------------------------------

  test("notifications.list targets /notifications on the configured instance"):
    val backend = RecordingBackend(responding(200, "[]"))

    onBackend(backend): api =>
      api
        .list(NotificationQuery.Empty, PageParams.First)
        .map(_ => assertEquals(pathOf(backend), "https://forge.example/api/v1/notifications"))

  test("notifications.list sends page and limit together, because limit alone is silently ignored"):
    val backend = RecordingBackend(responding(200, "[]"))

    onBackend(backend): api =>
      api
        .list(NotificationQuery.Empty, window(2, 25))
        .map(_ => assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "25")))

  test("notifications.list sends only the filters the caller set, and repeats the multi-valued ones"):
    val backend = RecordingBackend(responding(200, "[]"))
    val query   = NotificationQuery.Empty.includingRead
      .withStatuses(Vector(NotificationStatus.Unread, NotificationStatus.Pinned))
      .withSubjects(Vector(NotificationSubjectFilter.Pull))
      .updatedSince(Instant.parse("2026-07-01T00:00:00Z"))

    onBackend(backend): api =>
      api
        .list(query, PageParams.First)
        .map: _ =>
          assertEquals(
            queryOf(backend),
            List(
              "all"          -> "true",
              "status-types" -> "unread",
              "status-types" -> "pinned",
              "subject-type" -> "pull",
              "since"        -> "2026-07-01T00:00:00Z",
              "page"         -> "1",
              "limit"        -> "30",
            ),
          )

  test("notifications.list maps the instance's payload to domain threads"):
    onStub(responding(200, NotificationApiSuite.ThreadListBody)): api =>
      api.list(NotificationQuery.Empty, PageParams.First).map: page =>
        assertEquals(page.size, 1)
        assertEquals(page.items.head.id.value, 4821L)
        assertEquals(page.items.head.isUnread, true)
        assertEquals(page.items.head.subject.map(_.subjectType), Some(NotificationSubjectType.Issue))

  test("notifications.list ends where rel=next says it ends, not where a short page suggests"):
    onStub(responding(200, NotificationApiSuite.ThreadListBody, NotificationApiSuite.PagedHeaders)): api =>
      api.list(NotificationQuery.Empty, window(1, 30)).map: page =>
        assertEquals(page.totalCount, Some(74))
        assertEquals(page.nextPage.map(_.value), Some(2))
        assertEquals(page.isLast, false)

  test("a page whose response carries no Link header reports itself as the last one, whatever the total says"):
    onStub(responding(200, NotificationApiSuite.ThreadListBody, List(Header("X-Total-Count", "74")))): api =>
      api.list(NotificationQuery.Empty, PageParams.First).map: page =>
        assertEquals(page.totalCount, Some(74))
        assertEquals(page.isLast, true)
        assertEquals(page.nextPage, None)

  test("a page past the end is an empty page, not a failure — Forgejo answers 200 with []"):
    onStub(responding(200, "[]")): api =>
      api.list(NotificationQuery.Empty, PageParams.First).map: page =>
        assertEquals(page.items, Vector.empty[NotificationThread])
        assertEquals(page.isLast, true)

  test("notifications.new reads the count, not a boolean"):
    onStub(responding(200, """{"new":17}""")): api =>
      api.unreadCount().map: unread =>
        assertEquals(unread.value, 17L)
        assertEquals(unread.hasUnread, true)

  test("notifications.new targets /notifications/new and sends no parameters"):
    val backend = RecordingBackend(responding(200, """{"new":0}"""))

    onBackend(backend): api =>
      api
        .unreadCount()
        .map: _ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/notifications/new")
          assertEquals(queryOf(backend), Nil)

  test("a single-thread read addresses a thread by id"):
    val backend = RecordingBackend(responding(200, NotificationApiSuite.ThreadBody))

    onBackend(backend): api =>
      api.getThread(Thread).map: thread =>
        assertEquals(pathOf(backend), "https://forge.example/api/v1/notifications/threads/4821")
        assertEquals(thread.id.value, 4821L)
        assertEquals(thread.subject.flatMap(_.title), Some("A synthetic issue"))

  test("notifications.repository.list targets the repository's notifications, not the repository's issues"):
    val backend = RecordingBackend(responding(200, "[]"))

    onBackend(backend): api =>
      api
        .listRepository(Handle, Name, NotificationQuery.Empty, PageParams.First)
        .map: _ =>
          assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/notifications")
          assertEquals(queryOf(backend), List("page" -> "1", "limit" -> "30"))

  // --- writes ---------------------------------------------------------------

  test("notifications.read PUTs to /notifications with no parameters and no body"):
    val backend = RecordingBackend(responding(205, ""))

    onBackend(backend): api =>
      api
        .markAllRead()
        .map: _ =>
          assertEquals(methodOf(backend), "PUT")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/notifications")
          assertEquals(queryOf(backend), Nil)
          assertEquals(bodyOf(backend), "empty")

  test("a 205 with an empty body is a success, since the mark-read body is deliberately ignored"):
    onStub(responding(205, "")): api =>
      api.attempt.markAllRead().map(outcome => assertEquals(outcome, Right(())))

  test("a 205 that does carry the changed threads is a success too, and still decodes nothing"):
    onStub(responding(205, NotificationApiSuite.ThreadListBody)): api =>
      api.attempt.markAllRead().map(outcome => assertEquals(outcome, Right(())))

  test("notifications.threads.read PATCHes the thread and sends no to-status"):
    val backend = RecordingBackend(responding(205, ""))

    onBackend(backend): api =>
      api
        .markThreadRead(Thread)
        .map: _ =>
          assertEquals(methodOf(backend), "PATCH")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/notifications/threads/4821")
          assertEquals(queryOf(backend), Nil)

  test("notifications.repository.read PUTs to the repository's notifications"):
    val backend = RecordingBackend(responding(205, ""))

    onBackend(backend): api =>
      api
        .markRepositoryRead(Handle, Name)
        .map: _ =>
          assertEquals(methodOf(backend), "PUT")
          assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/Codeberg/Community/notifications")

  // --- retries --------------------------------------------------------------

  test("a mark-all-read is retried after a 503, because repeating it marks nothing twice"):
    val backend = RecordingBackend(
      BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(
        ResponseStub.adjust("", StatusCode(503)),
        ResponseStub.adjust("", StatusCode(205)),
      )
    )

    onBackend(backend): api =>
      api
        .markAllRead()
        .map(_ => assertEquals(backend.allInteractions.size, 2, "the PUT was not retried"))

  test("a mark-thread-read PATCH is retried too, unlike every other PATCH in this library"):
    val backend = RecordingBackend(
      BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(
        ResponseStub.adjust("", StatusCode(503)),
        ResponseStub.adjust("", StatusCode(205)),
      )
    )

    onBackend(backend): api =>
      api
        .markThreadRead(Thread)
        .map(_ => assertEquals(backend.allInteractions.size, 2, "the PATCH was not retried"))

  test("a read is retried as well, so the eligibility difference is only about mutating calls"):
    val backend = RecordingBackend(
      BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(
        ResponseStub.adjust("", StatusCode(503)),
        ResponseStub.adjust(NotificationApiSuite.ThreadBody, StatusCode(200)),
      )
    )

    onBackend(backend): api =>
      api.getThread(Thread).map: thread =>
        assertEquals(thread.id.value, 4821L)
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  // --- failures -------------------------------------------------------------

  test("a 401 fails the convenience rail with a CodebergException carrying the Api failure"):
    onStub(responding(401, NotificationApiSuite.UnauthorizedBody)): api =>
      api.list(NotificationQuery.Empty, PageParams.First).failed.map:
        case CodebergException(error) =>
          assertEquals(summary(error), (NotificationApi.ListOperation, 401, Some("token is required")))
        case other                    => fail(s"expected a CodebergException, got $other")

  test("a 401 reaches the typed rail as a Left reporting the very same failure"):
    onStub(responding(401, NotificationApiSuite.UnauthorizedBody)): api =>
      for
        raised <- api.list(NotificationQuery.Empty, PageParams.First).failed
        typed  <- api.attempt.list(NotificationQuery.Empty, PageParams.First)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a single-thread failure as well, so the choice of rail is only a choice of style"):
    onStub(responding(404, NotificationApiSuite.NotFoundBody)): api =>
      for
        raised <- api.getThread(Thread).failed
        typed  <- api.attempt.getThread(Thread)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a mark-read failure, which is the rail a Unit-returning call is easiest to lose"):
    onStub(responding(403, NotificationApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.markThreadRead(Thread).failed
        typed  <- api.attempt.markThreadRead(Thread)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a repository-listing failure"):
    onStub(responding(404, NotificationApiSuite.NotFoundBody)): api =>
      for
        raised <- api.listRepository(Handle, Name, NotificationQuery.Empty, PageParams.First).failed
        typed  <- api.attempt.listRepository(Handle, Name, NotificationQuery.Empty, PageParams.First)
      yield assertRailsAgree(raised, typed)

  test("a 400 is an Api failure too — Forgejo uses it for validation alongside 422"):
    onStub(responding(400, NotificationApiSuite.ValidationBody)): api =>
      api.attempt.list(NotificationQuery.Empty, PageParams.First).map:
        case Left(CodebergError.Api(_, status, _)) => assertEquals(status, 400)
        case other                                 => fail(s"expected an Api failure, got $other")

  test("a 401 carries the operation id, so an alert can name the endpoint"):
    onStub(responding(401, NotificationApiSuite.UnauthorizedBody)): api =>
      api.attempt.unreadCount().map: outcome =>
        assertEquals(operation(outcome), NotificationApi.UnreadCountOperation)

  test("a 200 whose payload does not fit the model becomes DecodingFailed, never an escaping codec exception"):
    onStub(responding(200, """{"unread":true}""")): api =>
      api.attempt.getThread(Thread).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.id")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a bad element of a list body reports its position, all the way through the pipeline"):
    onStub(responding(200, """[{"id":1},{"subject":{"title":"t"}}]""")): api =>
      api.attempt.list(NotificationQuery.Empty, PageParams.First).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[1].id")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a count body without the new key is where 'the spec was wrong' shows up"):
    onStub(responding(200, """{}""")): api =>
      api.attempt.unreadCount().map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.new")
        case other                                             => fail(s"expected a decoding failure, got $other")

  // --- assertions -----------------------------------------------------------

  private def assertRailsAgree[A](raised: Throwable, typed: Either[CodebergError, A]): Unit =
    (raised, typed) match
      case (CodebergException(convenience), Left(materialised)) =>
        assertEquals(summary(materialised), summary(convenience))
      case (convenience, materialised)                          =>
        fail(s"the rails disagreed: $convenience versus $materialised")

  private def summary(error: CodebergError): (String, Int, Option[String]) =
    error match
      case CodebergError.Api(ctx, status, body) => (ctx.operation, status, body.message)
      case other                                => fail(s"expected an Api failure, got ${other.describe}")

  private def operation[A](result: Either[CodebergError, A]): String =
    result match
      case Left(CodebergError.Api(ctx, _, _)) => ctx.operation
      case other                              => fail(s"expected an Api failure, got $other")

  // --- harness --------------------------------------------------------------

  private def responding(status: Int, body: String): BackendStub[Future] =
    responding(status, body, Nil)

  private def responding(status: Int, body: String, headers: List[Header]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status), headers))

  private def dialled(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.toString
      case None               => fail("no request reached the backend")

  /** The dialled URI without its query string. Written with `indexOf` rather than a character comparison because
    * `.scalafix.conf` bans universal equality outright.
    */
  private def pathOf(backend: RecordingBackend): String =
    val uri   = dialled(backend)
    val query = uri.indexOf('?')

    if query < 0 then uri else uri.take(query)

  private def queryOf(backend: RecordingBackend): List[(String, String)] =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.params.toSeq.toList
      case None               => fail("no request reached the backend")

  private def methodOf(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.method.method
      case None               => fail("no request reached the backend")

  private def bodyOf(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.body.show
      case None               => fail("no request reached the backend")

  private def window(page: Int, size: Int): PageParams =
    PageParams(orFail(PageNumber.from(page)), orFail(PageSize.from(size)))

  private def onStub[A](backend: Backend[Future])(use: NotificationApi => Future[A]): Future[A] =
    onBackend(backend)(use)

  /** Builds the pipeline this group's API sits on, and releases the timer whatever the outcome. */
  private def onBackend[A](backend: Backend[Future])(use: NotificationApi => Future[A]): Future[A] =
    given Exec[Future] = FutureExec()

    val config = CodebergConfig(Auth.Anonymous).copy(baseUri = Instance, retry = NotificationApiSuite.PromptRetry)
    val timer  = FutureTimer()

    val pipeline = ApiPipeline[Future](
      SttpHttpPort(backend, config),
      config,
      timer,
      Telemetry.noOp[Future],
      ApiErrorBodyCodec.parse,
    )

    use(NotificationApi(pipeline)).transform: outcome =>
      timer.close()
      outcome

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour.
  *
  * All of them are invented. `golden/notification/list-synthetic.json` is the only notification payload in the
  * repository and it is itself hand-authored, so there is no capture to reduce.
  */
object NotificationApiSuite:

  private val ThreadBody: String =
    """{
      |  "id": 4821,
      |  "unread": true,
      |  "pinned": false,
      |  "updated_at": "2026-08-02T12:00:00+02:00",
      |  "url": "https://forge.example/api/v1/notifications/threads/4821",
      |  "subject": {
      |    "type": "Issue",
      |    "title": "A synthetic issue",
      |    "state": "open",
      |    "url": "https://forge.example/api/v1/repos/Codeberg/Community/issues/2966",
      |    "latest_comment_url": ""
      |  },
      |  "repository": null
      |}""".stripMargin

  private val ThreadListBody: String = s"[$ThreadBody]"

  /** Paging headers shaped like the ones `docs/HAZARDS.md` §5 captured on a real listing, with the host swapped for the
    * stub's. Whether this endpoint sends a `Link` header at all is unverified; the point of the test is what the client
    * does when it arrives.
    */
  private val PagedHeaders: List[Header] =
    List(
      Header("X-Total-Count", "74"),
      Header(
        "Link",
        "<https://forge.example/api/v1/notifications?limit=30&page=2>; rel=\"next\"," +
          "<https://forge.example/api/v1/notifications?limit=30&page=3>; rel=\"last\"",
      ),
    )

  /** The 401 `docs/HAZARDS.md` §4 captured verbatim from `GET /user`: no `errors` array at all. */
  private val UnauthorizedBody: String =
    """{"message":"token is required","url":"https://codeberg.org/api/swagger"}"""

  private val NotFoundBody: String =
    """{"message":"GetNotification","url":"https://codeberg.org/api/swagger","errors":["thread does not exist"]}"""

  private val ForbiddenBody: String =
    """{"message":"token does not have at least one of required scope(s)","url":"https://codeberg.org/api/swagger"}"""

  private val ValidationBody: String =
    """{"message":"parsing time \"notadate\"","url":"https://codeberg.org/api/swagger"}"""

  /** Retries promptly and predictably: the default policy would make the retry tests take a quarter of a second. */
  private val PromptRetry: RetryPolicy = RetryPolicy(
    maxAttempts       = 3,
    baseDelay         = 1.milli,
    maxDelay          = 5.millis,
    jitter            = Jitter.None,
    respectRetryAfter = false,
  )
