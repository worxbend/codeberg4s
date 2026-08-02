package com.worxbend.codeberg4s.users.social

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.auth.ApiToken
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName
import com.worxbend.codeberg4s.repositories.RepoSlug
import com.worxbend.codeberg4s.users.Username

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend

import munit.FunSuite

import scala.concurrent.Future

/** [[UserTokenApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * Half of this suite is the ordinary wiring — path, body, paging, both rails. The other half exists because
  * [[UserTokenApi.create]] is the only call in codeberg4s whose success carries a working credential, and because the
  * defences around that are claims this suite has to check rather than repeat: the material must not reach a
  * `toString`, a [[com.worxbend.codeberg4s.CallContext]] or a [[com.worxbend.codeberg4s.CodebergError]], and the
  * listing must not be able to produce one at all.
  *
  * '''No golden fixture backs this group.''' Every payload below was written from `spec/swagger.v1.json`; see the class
  * note on [[UserTokenApi]].
  */
final class UserTokenApiSuite extends FunSuite with SocialApiHarness:

  private val Handle: Username = orFail(Username.from("earl-warren"))

  private val ById: AccessTokenRef = AccessTokenRef.ById(orFail(AccessTokenId.from(42L)))

  private val ByName: AccessTokenRef = AccessTokenRef.ByName(orFail(AccessTokenName.from("ci")))

  // --- listing --------------------------------------------------------------

  test("the token listing dials the account's tokens and pages them"):
    val backend = RecordingBackend(responding(200, UserTokenApiSuite.ListBody))

    onApi(backend): api =>
      api.list(Handle, window(2, 25)).map: page =>
        assertEquals(pathOf(backend), s"$Root/users/earl-warren/tokens")
        assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "25"))
        assertEquals(page.items.map(_.id.value), Vector(42L))
        assertEquals(page.items.flatMap(_.name).map(_.value), Vector("ci"))
        assertEquals(page.items.flatMap(_.lastEight), Vector("edential"))

  test("the listing model cannot carry a credential even when the instance sends one"):
    onStub(responding(200, s"[${UserTokenApiSuite.CreatedBody}]")): api =>
      api.list(Handle, PageParams.First).map: page =>
        assert(
          !page.items.toString.contains(UserTokenApiSuite.Material),
          s"the credential reached the listing model: ${page.items}",
        )

  // --- creation -------------------------------------------------------------

  test("minting a token POSTs the rendered options and hands back the credential once"):
    val backend = RecordingBackend(responding(201, UserTokenApiSuite.CreatedBody))
    val command = orFail(CreateAccessToken.named("ci"))
      .granting(TokenScope.Read(TokenCategory.Repository))
      .limitedTo(slug)

    onApi(backend): api =>
      api.create(Handle, command).map: created =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Root/users/earl-warren/tokens")
        assertEquals(
          bodyOf(backend),
          """{"name":"ci","scopes":["read:repository"],"repositories":[{"owner":"forgejo","name":"forgejo"}]}""",
        )
        assertEquals(created.token.reveal, UserTokenApiSuite.Material)
        assertEquals(created.details.id.value, 42L)

  test("a minted token never renders its material, from any of the three rendering paths"):
    onStub(responding(201, UserTokenApiSuite.CreatedBody)): api =>
      api.create(Handle, orFail(CreateAccessToken.named("ci"))).map: created =>
        assert(!created.toString.contains(UserTokenApiSuite.Material), s"the token reached toString: $created")
        assert(!s"$created".contains(UserTokenApiSuite.Material), "the token reached string interpolation")
        assertEquals(created.token.toString, ApiToken.Redacted)

  test("the request that mints a token carries no credential, because the instance generates it"):
    val backend = RecordingBackend(responding(201, UserTokenApiSuite.CreatedBody))

    onApi(backend): api =>
      api
        .create(Handle, orFail(CreateAccessToken.named("ci")))
        .map: _ =>
          assert(
            !bodyOf(backend).contains(UserTokenApiSuite.Material),
            s"the request body carried a credential: ${bodyOf(backend)}",
          )

  test("minting a token is never retried, because a repeat mints a credential nobody can revoke"):
    val backend = RecordingBackend(cycling(stub(503, ""), stub(201, UserTokenApiSuite.CreatedBody)))

    onApi(backend): api =>
      api.attempt
        .create(Handle, orFail(CreateAccessToken.named("ci")))
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a POST must not be retried into a success, got $outcome")
          assertEquals(attemptsOn(backend), 1, "the POST was retried")

  test("a failed creation puts nothing token-shaped into the error a caller would log"):
    onStub(responding(403, UserTokenApiSuite.ForbiddenBody)): api =>
      api.attempt
        .create(Handle, orFail(CreateAccessToken.named("ci")))
        .map:
          case Left(error) =>
            assert(!error.describe.contains(UserTokenApiSuite.Material), s"a credential reached: ${error.describe}")
            assert(!error.toString.contains(UserTokenApiSuite.Material), "a credential reached the error's toString")
          case Right(_)    => fail("expected a 403 to fail")

  test("a creation whose payload carried a credential but no id fails without echoing the body"):
    onStub(responding(201, UserTokenApiSuite.CreatedWithoutIdBody)): api =>
      api.attempt.create(Handle, orFail(CreateAccessToken.named("ci"))).map:
        case Left(error @ CodebergError.DecodingFailed(_, snippet, path, _)) =>
          assertEquals(path.render, "$.id")
          assertEquals(snippet, UserTokenApi.RedactedBody)
          assert(
            !error.describe.contains(UserTokenApiSuite.Material),
            s"the body excerpt leaked the credential into describe: ${error.describe}",
          )
        case other                                                           =>
          fail(s"expected a decoding failure, got $other")

  test("the excerpt is emptied on the convenience rail too, not only on the typed one"):
    onStub(responding(201, UserTokenApiSuite.CreatedWithoutIdBody)): api =>
      api.create(Handle, orFail(CreateAccessToken.named("ci"))).failed.map:
        case CodebergException(error) =>
          assert(!error.describe.contains(UserTokenApiSuite.Material), s"the credential reached: ${error.describe}")
        case other                    => fail(s"expected a CodebergException, got $other")

  test("every other call keeps the body excerpt, because only the token endpoint's body is a credential"):
    onStub(responding(200, UserTokenApiSuite.MalformedListBody)): api =>
      api.attempt.list(Handle, PageParams.First).map:
        case Left(CodebergError.DecodingFailed(_, snippet, _, _)) =>
          assertEquals(snippet, UserTokenApiSuite.MalformedListBody)
        case other                                                => fail(s"expected a decoding failure, got $other")

  // --- revocation -----------------------------------------------------------

  test("revoking by id addresses the row, and is retried because the row is never reused"):
    val backend = RecordingBackend(cycling(stub(503, ""), stub(204, "")))

    onApi(backend): api =>
      api.delete(Handle, ById).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Root/users/earl-warren/tokens/42")
        assertEquals(attemptsOn(backend), 2, "a delete by id was not retried")

  test("revoking by name is NOT retried, because a name can be recreated between two attempts"):
    val backend = RecordingBackend(cycling(stub(503, ""), stub(204, "")))

    onApi(backend): api =>
      api.attempt
        .delete(Handle, ByName)
        .map: outcome =>
          assertEquals(pathOf(backend), s"$Root/users/earl-warren/tokens/ci")
          assert(outcome.isLeft, s"a delete by name must not be retried into a success, got $outcome")
          assertEquals(attemptsOn(backend), 1, "a delete by name was retried")

  test("the two eligibilities are chosen from the reference, not from the endpoint"):
    assertEquals(UserTokenApi.deleteEligibility(ById).allows(HttpMethod.Delete), true)
    assertEquals(UserTokenApi.deleteEligibility(ByName).allows(HttpMethod.Delete), false)

  // --- failures -------------------------------------------------------------

  test("a 403 fails the convenience rail with a CodebergException carrying the Api failure"):
    onStub(responding(403, UserTokenApiSuite.ForbiddenBody)): api =>
      api.list(Handle, PageParams.First).failed.map:
        case CodebergException(error) =>
          assertEquals(summary(error), (UserTokenApi.ListOperation, 403, Some(UserTokenApiSuite.ForbiddenText)))
        case other                    => fail(s"expected a CodebergException, got $other")

  test("a 403 reaches the typed rail as a Left reporting the very same failure"):
    onStub(responding(403, UserTokenApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.list(Handle, PageParams.First).failed
        typed  <- api.attempt.list(Handle, PageParams.First)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on the creation as well, credential or no credential"):
    onStub(responding(403, UserTokenApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.create(Handle, orFail(CreateAccessToken.named("ci"))).failed
        typed  <- api.attempt.create(Handle, orFail(CreateAccessToken.named("ci")))
      yield assertRailsAgree(raised, typed)

  test("both rails agree on the revocation as well"):
    onStub(responding(404, UserTokenApiSuite.NotFoundBody)): api =>
      for
        raised <- api.delete(Handle, ById).failed
        typed  <- api.attempt.delete(Handle, ById)
      yield assertRailsAgree(raised, typed)

  test("a 400 is an Api failure too — Forgejo uses it for validation on this endpoint"):
    onStub(responding(400, UserTokenApiSuite.ValidationBody)): api =>
      api.attempt.create(Handle, orFail(CreateAccessToken.named("ci"))).map:
        case Left(CodebergError.Api(_, status, _)) => assertEquals(status, 400)
        case other                                 => fail(s"expected an Api failure, got $other")

  test("a failure carries the operation id of the endpoint it came from, so an alert can name it"):
    onStub(responding(403, UserTokenApiSuite.ForbiddenBody)): api =>
      api.attempt
        .delete(Handle, ById)
        .map(outcome => assertEquals(operationOf(outcome), UserTokenApi.DeleteOperation))

  // --- harness --------------------------------------------------------------

  private def slug: RepoSlug =
    RepoSlug(orFail(Owner.from("forgejo")), orFail(RepoName.from("forgejo")))

  private def onApi[A](backend: Backend[Future])(use: UserTokenApi => Future[A]): Future[A] =
    onBackend(backend)(UserTokenApi(_))(use)

  private def onStub[A](backend: Backend[Future])(use: UserTokenApi => Future[A]): Future[A] =
    onApi(backend)(use)

/** The response bodies this suite stubs, hand-written from `spec/swagger.v1.json`; see the class note. */
object UserTokenApiSuite:

  /** The plaintext credential every redaction assertion in this suite hunts for. */
  private val Material: String = "gto_thisisarealtoken"

  private val ListBody: String =
    """[{"id": 42, "name": "ci", "scopes": ["read:repository"], "sha1": "", "token_last_eight": "edential"}]"""

  private val CreatedBody: String =
    s"""{"id": 42, "name": "ci", "scopes": ["read:repository"], "sha1": "$Material",
       | "token_last_eight": "alltoken"}""".stripMargin

  private val CreatedWithoutIdBody: String =
    s"""{"name": "ci", "sha1": "$Material", "token_last_eight": "alltoken"}"""

  /** A listing element with no `id`, so the failure carries an excerpt that is safe to keep. */
  private val MalformedListBody: String = """[{"name": "ci"}]"""

  private val ForbiddenText: String = "token does not have at least one of required scope(s): [write:user]"

  private val ForbiddenBody: String = s"""{"message":"$ForbiddenText"}"""

  private val NotFoundBody: String =
    """{"message":"access token does not exist","url":"https://codeberg.org/api/swagger"}"""

  private val ValidationBody: String =
    """{"message":"CreateAccessToken","url":"https://codeberg.org/api/swagger","errors":["name is required"]}"""
