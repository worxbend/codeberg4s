package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.auth.ApiToken
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.repositories.RepositoryApi
import com.worxbend.codeberg4s.syntax.discard
import com.worxbend.codeberg4s.transport.SttpHttpPort

import sttp.client4.Backend
import sttp.client4.testing.BackendStub
import sttp.client4.testing.RecordingBackend
import sttp.client4.testing.ResponseStub
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.ScalaDurationOps

import java.util.concurrent.Executors

/** The published façade, end to end, over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The one exception is the JDK-HTTP-client test below, which builds a real client to watch it shut down. It sends no
  * request, so it opens no socket either.
  *
  * The subject here is the wiring, not the codecs — `modules/codec` already asserts decoding against the golden
  * captures. The payloads below are therefore small hand-written bodies chosen to exercise the seams: what a caller
  * gets back, what each rail does with a failure, whether a retry really re-sends, and what `close` owns.
  */
final class CodebergClientSuite extends FunSuite with ClientSuiteHarness:

  private val Handle: Owner = orFail(Owner.from("forgejo"))

  private val Name: RepoName = orFail(RepoName.from("forgejo"))

  test("version maps the instance's payload to a domain value"):
    onClient(responding(200, CodebergClientSuite.VersionBody)): client =>
      client.version.get().map(version => assertEquals(version, CodebergClientSuite.ExpectedVersion))

  test("the typed rail returns the same version as a Right"):
    onClient(responding(200, CodebergClientSuite.VersionBody)): client =>
      client.version.attempt.get().map(result => assertEquals(result, Right(CodebergClientSuite.ExpectedVersion)))

  test("repos.get maps the instance's payload to a domain repository"):
    onClient(responding(200, CodebergClientSuite.RepositoryBody)): client =>
      client.repos.get(Handle, Name).map: repository =>
        assertEquals(repository.id.value, 12345L)
        assertEquals(repository.slug.value, "forgejo/forgejo")
        assertEquals(repository.owner.login.value, "forgejo")
        assertEquals(repository.starsCount, 1234L)
        assertEquals(repository.topics.map(_.value), Vector("git", "forge"))

  test("repos.get targets /repos/{owner}/{repo} on the configured instance"):
    val backend = RecordingBackend(responding(200, CodebergClientSuite.RepositoryBody))

    onClientWith(backend, configFor(Auth.Anonymous)): client =>
      client.repos
        .get(Handle, Name)
        .map(_ => assertEquals(dialled(backend), "https://forge.example/api/v1/repos/forgejo/forgejo"))

  test("a 404 fails the convenience rail with a CodebergException carrying the Api failure"):
    onClient(responding(404, CodebergClientSuite.NotFoundBody)): client =>
      client.repos.get(Handle, Name).failed.map:
        case CodebergException(error) => assertEquals(summary(error), CodebergClientSuite.ExpectedNotFound)
        case other                    => fail(s"expected a CodebergException, got $other")

  test("a 404 reaches the typed rail as a Left reporting the very same failure"):
    onClient(responding(404, CodebergClientSuite.NotFoundBody)): client =>
      for
        raised <- client.repos.get(Handle, Name).failed
        typed  <- client.repos.attempt.get(Handle, Name)
      yield assertRailsAgree(raised, typed)

  test("a 503 is retried and the retry's payload is what the caller receives"):
    val backend = RecordingBackend(
      BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(
        ResponseStub.adjust("", StatusCode(503)),
        ResponseStub.adjust(CodebergClientSuite.RepositoryBody, StatusCode(200)),
      )
    )

    onClientWith(backend, configFor(Auth.Anonymous)): client =>
      client.repos.get(Handle, Name).map: repository =>
        assertEquals(repository.slug.value, "forgejo/forgejo")
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  test("a 200 whose payload does not fit the model becomes DecodingFailed, never an escaping codec exception"):
    onClient(responding(200, CodebergClientSuite.UnexpectedBody)): client =>
      client.repos.attempt.get(Handle, Name).map:
        case Left(CodebergError.DecodingFailed(_, snippet, path, _)) =>
          assertEquals(path.render, "$.id")
          assertEquals(snippet, CodebergClientSuite.UnexpectedBody)
        case other                                                   =>
          fail(s"expected a decoding failure, got $other")

  test("close releases a backend the client owns, however often it is called"):
    val backend = CountingBackend(responding(200, CodebergClientSuite.VersionBody))
    val client  = CodebergClient.owning(configFor(Auth.Anonymous), backend)

    client.close()
    client.close()

    assertEquals(backend.closes, 1)

  /** The stub above proves `close` was called; this proves the call reaches something.
    *
    * `CountingBackend` cannot tell a `close()` that released a connection pool from one that returned an
    * already-completed `Future` and did nothing, which is exactly what sttp's own backend used to do here. So this test
    * runs against a real JDK HTTP client and asks the client itself. No request is sent — the JDK starts the client's
    * selector thread when the client is built — so nothing here opens a socket.
    *
    * The execution context is this test's own rather than the suite's, because the assertion blocks the calling thread
    * and the client's callbacks must have a thread of their own to run on.
    */
  test("close terminates the JDK HTTP client behind a backend the client owns"):
    // munit continues on whichever thread completed the previous test's Future,
    // and the retry tests above are completed by the client's own scheduler
    // thread — which `CodebergClient.close` then interrupts, by design, to
    // abandon a retry that was waiting. So this body can start on a thread
    // whose interrupt flag is already set, and the blocking wait below would
    // throw InterruptedException before it waited at all. Clearing the flag
    // makes the test independent of which thread it was handed; nothing in this
    // suite is waiting to be interrupted.
    Thread.interrupted().discard

    val executor = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())

    try
      val http   = SttpHttpPort.defaultHttpClient(CodebergClientSuite.ConnectTimeout, executor)
      val client = CodebergClient.owning(configFor(Auth.Anonymous), SttpHttpPort.owning(http, executor))(using executor)

      assert(!http.isTerminated, "a client that was never closed already reports itself terminated")

      client.close()

      assert(
        http.awaitTermination(CodebergClientSuite.TerminationLimit.toJava),
        "close() left the JDK HTTP client running",
      )
    finally executor.shutdown()

  test("close leaves a backend the caller supplied open, because the caller owns it"):
    val backend = CountingBackend(responding(200, CodebergClientSuite.VersionBody))
    val client  = CodebergClient.usingBackend(configFor(Auth.Anonymous), backend)

    client.close()
    client.close()

    assertEquals(backend.closes, 0)

  test("firstPage is page one at the configured default page size"):
    val size   = orFail(PageSize.from(7))
    val config = configFor(Auth.Anonymous).copy(defaultPageSize = size)
    val client = CodebergClient.usingBackend(config, responding(200, CodebergClientSuite.VersionBody))

    try assertEquals(client.firstPage, PageParams(PageNumber.First, size))
    finally client.close()

  test("a configured token appears in nothing the caller can see about a failure"):
    val config = configFor(Auth.Token(orFail(ApiToken.from(CodebergClientSuite.Secret))))

    onClientWith(responding(404, CodebergClientSuite.NotFoundBody), config): client =>
      client.repos.get(Handle, Name).failed.map: thrown =>
        val rendered = List(thrown.getMessage, thrown.toString, thrown.getStackTrace.mkString(" ")).mkString(" | ")

        assert(!rendered.contains(CodebergClientSuite.Secret), rendered)

  // --- fixtures -------------------------------------------------------------

  private def configFor(auth: Auth): CodebergConfig =
    CodebergConfig(auth).copy(baseUri = Instance, retry = ClientSuiteHarness.PromptRetry)

  /** Runs `use` against an anonymous client on `backend`, closing the client whatever the outcome. */
  private def onClient[A](backend: Backend[Future])(use: CodebergClient => Future[A]): Future[A] =
    onClientWith(backend, configFor(Auth.Anonymous))(use)

  private def onClientWith[A](backend: Backend[Future], config: CodebergConfig)(
      use: CodebergClient => Future[A]
  ): Future[A] =
    val client = CodebergClient.usingBackend(config, backend)

    use(client).transform: outcome =>
      client.close()
      outcome

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour. */
object CodebergClientSuite:

  /** A token that must not turn up in any rendering of a failure. */
  private val Secret: String = "cb-0123456789abcdef-secret"

  private val ConnectTimeout: FiniteDuration = 3.seconds

  /** Generous on purpose: an idle JDK HTTP client terminates in single-digit milliseconds, so this is a hang detector
    * rather than a race the test is trying to win.
    */
  private val TerminationLimit: FiniteDuration = 10.seconds

  private val ExpectedVersion: ServerVersion = ServerVersion("16.0.0-dev-668-1bdb1938+gitea-1.22.0")

  private val VersionBody: String = s"""{"version": "${ExpectedVersion.raw}"}"""

  /** The shape `golden/repository/repo-single.json` has, reduced to the keys these tests assert on. */
  private val RepositoryBody: String =
    """{
      |  "id": 12345,
      |  "owner": {"id": 42, "login": "forgejo", "full_name": "Forgejo"},
      |  "name": "forgejo",
      |  "full_name": "forgejo/forgejo",
      |  "description": "Beyond coding. We forge.",
      |  "private": false,
      |  "fork": false,
      |  "parent": null,
      |  "stars_count": 1234,
      |  "topics": ["git", "forge"],
      |  "default_branch": "forgejo"
      |}""".stripMargin

  /** `golden/error/404-repo-not-found.json`, in one line. */
  private val NotFoundBody: String =
    """{"message": "The target couldn't be found.", "url": "https://codeberg.org/api/swagger", "errors": []}"""

  /** Well-formed JSON carrying none of the keys a repository cannot do without. */
  private val UnexpectedBody: String = """{"unexpected": true}"""

  private val ExpectedNotFound: (String, Int, Option[String]) =
    (RepositoryApi.GetOperation, 404, Some("The target couldn't be found."))
