package com.worxbend.codeberg4s.miscellaneous

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
import com.worxbend.codeberg4s.core.JitterSource
import com.worxbend.codeberg4s.core.Telemetry
import com.worxbend.codeberg4s.retry.Jitter
import com.worxbend.codeberg4s.retry.RetryPolicy
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

/** The instance-level group over a `BackendStub`: nothing here opens a socket.
  *
  * The subject is the wiring — what each operation dials, what it sends, what it does with a body that is not JSON, and
  * whether the two rails agree about a failure. Decoding itself is asserted in `modules/codec` against the golden
  * captures, so the payloads below are the smallest bodies that exercise a seam.
  */
final class MiscellaneousApiSuite extends FunSuite:

  private given ExecutionContext = munitExecutionContext

  private given JitterSource = JitterSource.Deterministic

  /** The API root every assertion about a dialled URI is written against. */
  private val Root: String = "https://forge.example/api/v1"

  private val Config: CodebergConfig =
    CodebergConfig(Auth.Anonymous)
      .copy(baseUri = orFail(BaseUri.from(Root)), retry = MiscellaneousApiSuite.PromptRetry)

  test("apiSettings reports the clamp that makes items.size an unusable end-of-pages test"):
    onBackend(responding(200, MiscellaneousApiSuite.ApiSettingsBody)): api =>
      api.apiSettings().map: settings =>
        assertEquals(settings.maxResponseItems, 50L)
        assertEquals(settings.defaultPagingNum, 30L)
        assertEquals(settings.gitTreesPerPage, Some(1000L))

  test("apiSettings targets /settings/api on the configured instance"):
    val backend = RecordingBackend(responding(200, MiscellaneousApiSuite.ApiSettingsBody))

    onBackend(backend): api =>
      api.apiSettings().map(_ => assertEquals(dialled(backend), s"$Root/settings/api"))

  test("repositorySettings targets /settings/repository and reports the disabled features"):
    val backend = RecordingBackend(responding(200, """{"forks_disabled":true}"""))

    onBackend(backend): api =>
      api.repositorySettings().map: settings =>
        assertEquals(dialled(backend), s"$Root/settings/repository")
        assertEquals(settings.forksDisabled, true)
        assertEquals(settings.lfsDisabled, false)

  test("attachmentSettings targets /settings/attachment and splits the allowed types"):
    val backend = RecordingBackend(responding(200, """{"enabled":true,"allowed_types":"image/png,.pdf"}"""))

    onBackend(backend): api =>
      api.attachmentSettings().map: settings =>
        assertEquals(dialled(backend), s"$Root/settings/attachment")
        assertEquals(settings.allowedTypes, Vector("image/png", ".pdf"))

  test("signingKey hands back an armored block that no JSON parser would have accepted"):
    val backend = RecordingBackend(responding(200, MiscellaneousApiSuite.ArmoredKey))

    onBackend(backend): api =>
      api.signingKey().map: key =>
        assertEquals(dialled(backend), s"$Root/signing-key.gpg")
        assertEquals(key, Some(SigningKey(MiscellaneousApiSuite.ArmoredKey)))

  test("an instance that signs nothing answers 200 with an empty body, which is None and not a failure"):
    onBackend(responding(200, "")): api =>
      api.signingKey().map(key => assertEquals(key, None))

  test("renderMarkdown POSTs the capitalised option body to /markdown"):
    val backend = RecordingBackend(responding(200, MiscellaneousApiSuite.RenderedHtml))

    onBackend(backend): api =>
      api.renderMarkdown(MarkdownRenderRequest.of("# Title")).map: _ =>
        assertEquals(dialled(backend), s"$Root/markdown")
        assertEquals(method(backend), "POST")
        assertEquals(ujson.read(sentBody(backend))("Text").str, "# Title")
        assertEquals(contentType(backend), Some("application/json"))

  test("renderMarkdown returns the HTML fragment verbatim, unparsed"):
    onBackend(responding(200, MiscellaneousApiSuite.RenderedHtml)): api =>
      api
        .renderMarkdown(MarkdownRenderRequest.of("# Title"))
        .map(html => assertEquals(html, RenderedMarkdown(MiscellaneousApiSuite.RenderedHtml)))

  test("renderMarkdownRaw sends the markdown itself as a plain-text body"):
    val backend = RecordingBackend(responding(200, MiscellaneousApiSuite.RenderedHtml))

    onBackend(backend): api =>
      api.renderMarkdownRaw("# Title").map: html =>
        assertEquals(dialled(backend), s"$Root/markdown/raw")
        assertEquals(sentBody(backend), "# Title")
        assertEquals(contentType(backend), Some("text/plain; charset=utf-8"))
        assertEquals(html, RenderedMarkdown(MiscellaneousApiSuite.RenderedHtml))

  test("rendering is retried after a 503 even though it is a POST, because it changes nothing"):
    val backend = RecordingBackend(
      BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(
        ResponseStub.adjust("", StatusCode(503)),
        ResponseStub.adjust(MiscellaneousApiSuite.RenderedHtml, StatusCode(200)),
      )
    )

    onBackend(backend): api =>
      api.renderMarkdown(MarkdownRenderRequest.of("# Title")).map: html =>
        assertEquals(html, RenderedMarkdown(MiscellaneousApiSuite.RenderedHtml))
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  test("a 404 fails the convenience rail with a CodebergException carrying the Api failure"):
    onBackend(responding(404, MiscellaneousApiSuite.NotFoundBody)): api =>
      api.apiSettings().failed.map:
        case CodebergException(error) => assertEquals(summary(error), MiscellaneousApiSuite.ExpectedNotFound)
        case other                    => fail(s"expected a CodebergException, got $other")

  test("a 404 reaches the typed rail as a Left reporting the very same failure"):
    onBackend(responding(404, MiscellaneousApiSuite.NotFoundBody)): api =>
      for
        raised <- api.apiSettings().failed
        typed  <- api.attempt.apiSettings()
      yield assertRailsAgree(raised, typed)

  test("a 422 on a rejected render reaches both rails as the same Api failure"):
    onBackend(responding(422, MiscellaneousApiSuite.ValidationBody)): api =>
      for
        raised <- api.renderMarkdown(MarkdownRenderRequest.of("# Title")).failed
        typed  <- api.attempt.renderMarkdown(MarkdownRenderRequest.of("# Title"))
      yield (raised, typed) match
        case (CodebergException(convenience), Left(materialised)) =>
          assertEquals(summary(materialised), summary(convenience))
          assertEquals(summary(materialised), MiscellaneousApiSuite.ExpectedValidation)
        case (convenience, materialised)                          =>
          fail(s"the rails disagreed: $convenience versus $materialised")

  test("a 200 whose settings payload names no limits becomes DecodingFailed, never an exception"):
    onBackend(responding(200, """{"unexpected":true}""")): api =>
      api.attempt.apiSettings().map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.max_response_items")
        case other                                             => fail(s"expected a decoding failure, got $other")

  // --- assertions -----------------------------------------------------------

  /** Both rails must report the same failure, so the choice between them is a choice of style and nothing else. */
  private def assertRailsAgree(raised: Throwable, typed: Either[CodebergError, ServerApiSettings]): Unit =
    (raised, typed) match
      case (CodebergException(convenience), Left(materialised)) =>
        assertEquals(summary(materialised), summary(convenience))
        assertEquals(summary(materialised), MiscellaneousApiSuite.ExpectedNotFound)
      case (convenience, materialised)                          =>
        fail(s"the rails disagreed: $convenience versus $materialised")

  /** An `Api` failure projected onto the parts that do not depend on wall-clock time, so two calls are comparable. */
  private def summary(error: CodebergError): (String, Int, Option[String]) =
    error match
      case CodebergError.Api(ctx, status, body) => (ctx.operation, status, body.message)
      case other                                => fail(s"expected an Api failure, got ${other.describe}")

  // --- fixtures -------------------------------------------------------------

  private def responding(status: Int, body: String): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status)))

  private def recorded(backend: RecordingBackend): sttp.client4.GenericRequest[?, ?] =
    backend.allInteractions.headOption match
      case Some((request, _)) => request
      case None               => fail("no request reached the backend")

  private def dialled(backend: RecordingBackend): String =
    recorded(backend).uri.toString

  private def method(backend: RecordingBackend): String =
    recorded(backend).method.method

  private def sentBody(backend: RecordingBackend): String =
    recorded(backend).body match
      case sttp.client4.StringBody(value, _, _) => value
      case other                                => fail(s"expected a string body, got $other")

  private def contentType(backend: RecordingBackend): Option[String] =
    recorded(backend).contentType

  /** Runs `use` against an anonymous client on `backend`, releasing the timer whatever the outcome. */
  private def onBackend[A](backend: Backend[Future])(use: MiscellaneousApi => Future[A]): Future[A] =
    given Exec[Future] = FutureExec()

    val timer = FutureTimer()

    val pipeline = ApiPipeline[Future](
      SttpHttpPort(backend, Config),
      Config,
      timer,
      Telemetry.noOp[Future],
      ApiErrorBodyCodec.parse,
    )

    use(MiscellaneousApi(pipeline)).transform: outcome =>
      timer.close()
      outcome

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour. */
object MiscellaneousApiSuite:

  /** `golden/misc/settings-api.json`, in one line. */
  private val ApiSettingsBody: String =
    """{"max_response_items":50,"default_paging_num":30,"default_git_trees_per_page":1000,
      |"default_max_blob_size":10485760}""".stripMargin.replace("\n", "")

  /** The shape of a `signing-key.gpg` body. Not a real key, and not JSON — which is the point. */
  private val ArmoredKey: String =
    """-----BEGIN PGP PUBLIC KEY BLOCK-----
      |
      |mDMEZQEAAAAAAA
      |=abcd
      |-----END PGP PUBLIC KEY BLOCK-----
      |""".stripMargin

  /** What `POST /markdown` answers: an HTML fragment, not a document and not JSON. */
  private val RenderedHtml: String = "<h1 id=\"user-content-title\">Title</h1>\n"

  /** `golden/error/404-repo-not-found.json`, in one line. */
  private val NotFoundBody: String =
    """{"message": "The target couldn't be found.", "url": "https://codeberg.org/api/swagger", "errors": []}"""

  /** The shape `docs/HAZARDS.md` §4 captured from a live 422: a raw Go error in `message` and no `errors` array. */
  private val ValidationBody: String =
    """{"message": "Unsupported render mode", "url": "https://codeberg.org/api/swagger"}"""

  private val ExpectedNotFound: (String, Int, Option[String]) =
    (MiscellaneousApi.ApiSettingsOperation, 404, Some("The target couldn't be found."))

  private val ExpectedValidation: (String, Int, Option[String]) =
    (MiscellaneousApi.RenderMarkdownOperation, 422, Some("Unsupported render mode"))

  /** Retries promptly and predictably: the default policy would make the retry test take a quarter of a second. */
  private val PromptRetry: RetryPolicy = RetryPolicy(
    maxAttempts       = 3,
    baseDelay         = 1.milli,
    maxDelay          = 5.millis,
    jitter            = Jitter.None,
    respectRetryAfter = false,
  )
