package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.paging.PageParams

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend
import sttp.model.Header

import scala.concurrent.Future

/** [[UserApplicationApi]] over a `BackendStub`; see [[AccountApiSuite]] for the harness and for the evidence note.
  *
  * Half of this suite is about the client secret: that a creation carries it, that a read does not, and — the tests the
  * group's brief singles out — that it cannot reach a [[com.worxbend.codeberg4s.CodebergError]] or a `toString` on the
  * way past. The other half is the ordinary wiring and the two retry decisions, one of which (the update) is the only
  * `PATCH` in this lane that is '''not''' repeated.
  */
final class UserApplicationApiSuite extends AccountApiSuite:

  private val Application: OAuth2ApplicationId = orFail(OAuth2ApplicationId.from(7L))

  private val ApplicationsRoot: String = s"$Root/applications/oauth2"

  private def definition: OAuth2ApplicationDefinition =
    orFail(OAuth2ApplicationDefinition.named("deploy-bot")).redirectingTo("https://ci.example/cb").confidential

  test("the listing targets the account's applications and pages"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api.list(window(2, 25)).map: _ =>
        assertEquals(pathOf(backend), ApplicationsRoot)
        assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "25"))

  test("the listing ends where rel=next says it ends, not where a short page suggests"):
    onApi(responding(200, UserApplicationApiSuite.ListBody, UserApplicationApiSuite.PagedHeaders)): api =>
      api.list(window(1, 30)).map: page =>
        assertEquals(page.items.length, 1)
        assertEquals(page.totalCount, Some(4))
        assertEquals(page.nextPage.map(_.value), Some(2))
        assertEquals(page.isLast, false)

  test("a page past the end is an empty page, not a failure"):
    onApi(responding(200, "[]")): api =>
      api.list(PageParams.First).map: page =>
        assertEquals(page.items, Vector.empty[OAuth2Application])
        assertEquals(page.isLast, true)

  test("a listed application carries no client secret, which is the API's behaviour and not a gap"):
    onApi(responding(200, UserApplicationApiSuite.ListBody)): api =>
      api.list(PageParams.First).map(page => assertEquals(page.items.map(_.carriesSecret), Vector(false)))

  test("a single-application read addresses it by id and still carries no secret"):
    val backend = RecordingBackend(responding(200, UserApplicationApiSuite.ReadBody))

    onApi(backend): api =>
      api.get(Application).map: application =>
        assertEquals(pathOf(backend), s"$ApplicationsRoot/7")
        assertEquals(application.carriesSecret, false)
        assertEquals(application.name, Some("deploy-bot"))

  test("creating an application POSTs all three properties and answers the one copy of the secret"):
    val backend = RecordingBackend(responding(201, UserApplicationApiSuite.CreatedBody))

    onApi(backend): api =>
      api.create(definition).map: application =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), ApplicationsRoot)
        assertEquals(
          bodyOf(backend),
          """{"name":"deploy-bot","redirect_uris":["https://ci.example/cb"],"confidential_client":true}""",
        )
        assertEquals(application.carriesSecret, true)
        assertEquals(application.clientSecret.map(_.reveal), Some("gto_51f0c9a2"))

  test("the client secret masks itself in the value handed back, so it cannot be logged by accident"):
    onApi(responding(201, UserApplicationApiSuite.CreatedBody)): api =>
      api.create(definition).map: application =>
        assertEquals(application.clientSecret.map(_.toString), Some(ClientSecret.Redacted))
        assert(!application.toString.contains("gto_"), s"the generated toString leaked it: $application")

  test("creating an application is never retried, because a repeat mints a second unusable credential"):
    val backend = RecordingBackend(failingThenSucceeding(201, UserApplicationApiSuite.CreatedBody))

    onApi(backend): api =>
      api.attempt.create(definition).map(_ => assertEquals(attemptsOn(backend), 1, "the POST was retried"))

  test("updating an application PATCHes the whole definition, because the API gives it the create model"):
    val backend = RecordingBackend(responding(200, UserApplicationApiSuite.ReadBody))

    onApi(backend): api =>
      api.update(Application, definition).map: _ =>
        assertEquals(methodOf(backend), "PATCH")
        assertEquals(pathOf(backend), s"$ApplicationsRoot/7")
        assertEquals(
          bodyOf(backend),
          """{"name":"deploy-bot","redirect_uris":["https://ci.example/cb"],"confidential_client":true}""",
        )

  test("updating an application is never retried, because a repeat may re-issue and invalidate a credential"):
    val backend = RecordingBackend(failingThenSucceeding(200, UserApplicationApiSuite.ReadBody))

    onApi(backend): api =>
      api.attempt
        .update(Application, definition)
        .map(_ => assertEquals(attemptsOn(backend), 1, "the PATCH was retried"))

  test("deleting an application is a DELETE by id that reads no body"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.delete(Application).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$ApplicationsRoot/7")

  test("deleting an application is retried, because a row id is never reused"):
    val backend = RecordingBackend(failingThenSucceeding(204, ""))

    onApi(backend): api =>
      api.delete(Application).map(_ => assertEquals(attemptsOn(backend), 2))

  // --- failures -------------------------------------------------------------

  test("a 403 fails the convenience rail with a CodebergException carrying the Api failure"):
    onApi(responding(403, AccountApiSuite.ForbiddenBody)): api =>
      api.get(Application).failed.map: failure =>
        assertEquals(summary(unwrap(failure))._1, UserApplicationApi.GetOperation)
        assertEquals(summary(unwrap(failure))._2, 403)

  test("a 403 reaches the typed rail as a Left reporting the very same failure"):
    onApi(responding(403, AccountApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.get(Application).failed
        typed  <- api.attempt.get(Application)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on the creation as well, so the choice of rail is only a choice of style"):
    onApi(responding(400, AccountApiSuite.NotFoundBody)): api =>
      for
        raised <- api.create(definition).failed
        typed  <- api.attempt.create(definition)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a unit-returning delete as well"):
    onApi(responding(404, AccountApiSuite.NotFoundBody)): api =>
      for
        raised <- api.delete(Application).failed
        typed  <- api.attempt.delete(Application)
      yield assertRailsAgree(raised, typed)

  test("a 200 whose payload has no id becomes DecodingFailed at the id's own path"):
    onApi(responding(200, """{"name":"no id"}""")): api =>
      api.attempt.get(Application).map(outcome => assertEquals(decodingPathOf(outcome), "$.id"))

  test("a bad element of a listing reports its own position"):
    onApi(responding(200, """[{"id":1},{"name":"no id"}]""")): api =>
      api.attempt.list(PageParams.First).map(outcome => assertEquals(decodingPathOf(outcome), "$[1].id"))

  test("a successful result never leaks the secret through any rendering path"):
    onApi(responding(201, UserApplicationApiSuite.CreatedBody)): api =>
      api.create(definition).map: application =>
        assert(!application.toString.contains("gto_"), "the generated toString leaked the secret")
        assert(!s"$application".contains("gto_"), "interpolation leaked the secret")
        assert(!application.clientSecret.toString.contains("gto_"), "the Option's toString leaked the secret")

  test("a body that failed to decode is snippetted verbatim, credential included — the pipeline's contract, pinned"):
    onApi(responding(201, UserApplicationApiSuite.SecretWithoutIdBody)): api =>
      api.attempt.create(definition).map: outcome =>
        assertEquals(decodingPathOf(outcome), "$.id")
        assert(
          describe(outcome).contains("gto_"),
          "the snippet no longer carries the body; if that is deliberate, ClientSecret's Scaladoc must be updated",
        )

  private def describe[A](outcome: Either[CodebergError, A]): String =
    outcome match
      case Left(error) => error.describe
      case Right(_)    => fail("expected a failure")

  private def unwrap(failure: Throwable): CodebergError =
    failure match
      case CodebergException(error) => error
      case other                    => fail(s"expected a CodebergException, got $other")

  private def onApi[A](backend: Backend[Future])(use: UserApplicationApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(UserApplicationApi(pipeline)))

/** The response bodies this suite stubs, all hand-written from `spec/swagger.v1.json`. */
object UserApplicationApiSuite:

  private val ReadBody: String =
    """{"id": 7, "name": "deploy-bot", "client_id": "2fd3a1c0", "redirect_uris": ["https://ci.example/cb"],
      | "confidential_client": true, "created": "2026-07-30T19:14:15+02:00"}""".stripMargin

  private val CreatedBody: String =
    """{"id": 7, "name": "deploy-bot", "client_id": "2fd3a1c0", "client_secret": "gto_51f0c9a2",
      | "redirect_uris": ["https://ci.example/cb"], "confidential_client": true,
      | "created": "2026-07-30T19:14:15+02:00"}""".stripMargin

  private val ListBody: String = s"[$ReadBody]"

  /** A payload that carries a secret and no id: the only way a client secret could get near a decoding failure. */
  private val SecretWithoutIdBody: String = """{"name": "deploy-bot", "client_secret": "gto_51f0c9a2"}"""

  /** The paging headers `docs/HAZARDS.md` §5 captured verbatim, with the host and path swapped for this endpoint's. */
  private val PagedHeaders: List[Header] =
    List(
      Header("X-Total-Count", "4"),
      Header(
        "Link",
        "<https://forge.example/api/v1/user/applications/oauth2?limit=30&page=2>; rel=\"next\"," +
          "<https://forge.example/api/v1/user/applications/oauth2?limit=30&page=2>; rel=\"last\"",
      ),
    )
