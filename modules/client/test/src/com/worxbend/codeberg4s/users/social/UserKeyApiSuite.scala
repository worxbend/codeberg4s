package com.worxbend.codeberg4s.users.social

import com.worxbend.codeberg4s.ClientSuiteHarness
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.users.Username

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend

import munit.FunSuite

import scala.concurrent.Future

/** [[UserKeyApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The subject is the wiring — which URI is dialled, which body is sent, which calls may be repeated, and what each
  * rail does with a failure. Decoding is asserted in `modules/codec`, so the payloads here are small hand-written
  * bodies chosen to exercise a seam.
  *
  * '''No golden fixture backs this group.''' Every payload below was written from `spec/swagger.v1.json`; see the class
  * note on [[UserKeyApi]].
  */
final class UserKeyApiSuite extends FunSuite with ClientSuiteHarness:

  private val Handle: Username = orFail(Username.from("earl-warren"))

  private val Ssh: SshKeyId = orFail(SshKeyId.from(12L))

  private val Gpg: GpgKeyId = orFail(GpgKeyId.from(7L))

  // --- SSH keys -------------------------------------------------------------

  test("registering an SSH key POSTs the rendered options"):
    val backend = RecordingBackend(responding(201, UserKeyApiSuite.PublicKeyBody))
    val command = orFail(CreateSshKey.of("laptop", UserKeyApiSuite.KeyMaterial)).readOnly

    onApi(backend): api =>
      api.createKey(command).map: key =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Root/user/keys")
        assertEquals(
          bodyOf(backend),
          s"""{"title":"laptop","key":"${UserKeyApiSuite.KeyMaterial}","read_only":true}""",
        )
        assertEquals(key.id, 12L)
        assertEquals(key.title, Some("laptop"))

  test("registering an SSH key is never retried, because this library repeats no POST"):
    val backend = RecordingBackend(cycling(stub(503, ""), stub(201, UserKeyApiSuite.PublicKeyBody)))
    val command = orFail(CreateSshKey.of("laptop", UserKeyApiSuite.KeyMaterial))

    onApi(backend): api =>
      api.attempt
        .createKey(command)
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on a POST must not be retried into a success, got $outcome")
          assertEquals(attemptsOn(backend), 1, "the POST was retried")

  test("reading one SSH key addresses it by its row id"):
    val backend = RecordingBackend(responding(200, UserKeyApiSuite.PublicKeyBody))

    onApi(backend): api =>
      api.key(Ssh).map: key =>
        assertEquals(pathOf(backend), s"$Root/user/keys/12")
        assertEquals(key.id, 12L)

  test("deleting an SSH key is a DELETE, and is retried because the id is never reused"):
    val backend = RecordingBackend(cycling(stub(503, ""), stub(204, "")))

    onApi(backend): api =>
      api.deleteKey(Ssh).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Root/user/keys/12")
        assertEquals(attemptsOn(backend), 2, "the DELETE was not retried")

  // --- GPG keys -------------------------------------------------------------

  test("the signed-in GPG listing dials /user/gpg_keys and pages it"):
    val backend = RecordingBackend(responding(200, UserKeyApiSuite.GpgKeyListBody))

    onApi(backend): api =>
      api.gpgKeys(window(2, 25)).map: page =>
        assertEquals(pathOf(backend), s"$Root/user/gpg_keys")
        assertEquals(queryOf(backend), List("page" -> "2", "limit" -> "25"))
        assertEquals(page.items.map(_.id.value), Vector(7L))
        assertEquals(page.items.flatMap(_.keyId).map(_.value), Vector("3AA5C34371567BD2"))

  test("another account's GPG listing names the account in the path"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .gpgKeysOf(Handle, PageParams.First)
        .map(_ => assertEquals(pathOf(backend), s"$Root/users/earl-warren/gpg_keys"))

  test("reading one GPG key addresses it by its row id, not by its OpenPGP id"):
    val backend = RecordingBackend(responding(200, UserKeyApiSuite.GpgKeyBody))

    onApi(backend): api =>
      api.gpgKey(Gpg).map: key =>
        assertEquals(pathOf(backend), s"$Root/user/gpg_keys/7")
        assertEquals(key.id.value, 7L)
        assertEquals(key.isVerified, false)

  test("registering a GPG key POSTs the armored block and omits an absent signature"):
    val backend = RecordingBackend(responding(201, UserKeyApiSuite.GpgKeyBody))

    onApi(backend): api =>
      api.createGpgKey(orFail(CreateGpgKey.of("BLOCK"))).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Root/user/gpg_keys")
        assertEquals(bodyOf(backend), """{"armored_public_key":"BLOCK"}""")

  test("deleting a GPG key is a DELETE, and is retried because the id is never reused"):
    val backend = RecordingBackend(cycling(stub(503, ""), stub(204, "")))

    onApi(backend): api =>
      api.deleteGpgKey(Gpg).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Root/user/gpg_keys/7")
        assertEquals(attemptsOn(backend), 2, "the DELETE was not retried")

  // --- the verification handshake -------------------------------------------

  test("the verification token is read as plain text, not parsed as JSON"):
    val backend = RecordingBackend(responding(200, "d3adb33f\n"))

    onApi(backend): api =>
      api.verificationToken().map: token =>
        assertEquals(pathOf(backend), s"$Root/user/gpg_key_token")
        assertEquals(queryOf(backend), Nil)
        assertEquals(token.value, "d3adb33f")

  test("a blank verification token is a decoding failure, not a token-shaped emptiness"):
    onApi(responding(200, "   ")): api =>
      api.attempt.verificationToken().map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("verifying a key POSTs the claim the token produced, and is never retried"):
    val backend = RecordingBackend(cycling(stub(503, ""), stub(201, UserKeyApiSuite.VerifiedGpgKeyBody)))
    val token   = orFail(GpgKeyToken.from("d3adb33f"))
    val claim   = token.signedWith(orFail(OpenPgpKeyId.from("3AA5C34371567BD2")), orFail(ArmoredSignature.from("SIG")))

    onApi(backend): api =>
      api.attempt
        .verifyGpgKey(claim)
        .map: outcome =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), s"$Root/user/gpg_key_verify")
          assertEquals(bodyOf(backend), """{"key_id":"3AA5C34371567BD2","armored_signature":"SIG"}""")
          assert(outcome.isLeft, s"a 503 on a POST must not be retried into a success, got $outcome")
          assertEquals(attemptsOn(backend), 1, "the POST was retried")

  test("a successful verification returns the key marked verified"):
    onApi(responding(201, UserKeyApiSuite.VerifiedGpgKeyBody)): api =>
      val token = orFail(GpgKeyToken.from("d3adb33f"))
      val claim = token.signedWith(orFail(OpenPgpKeyId.from("3AA5C34371567BD2")), orFail(ArmoredSignature.from("SIG")))

      api.verifyGpgKey(claim).map(key => assertEquals(key.isVerified, true))

  // --- failures -------------------------------------------------------------

  test("a 401 fails the convenience rail with a CodebergException carrying the Api failure"):
    onApi(responding(401, UserKeyApiSuite.UnauthorizedBody)): api =>
      api.gpgKeys(PageParams.First).failed.map:
        case CodebergException(error) =>
          assertEquals(summary(error), (UserKeyApi.GpgKeysOperation, 401, Some("token is required")))
        case other                    => fail(s"expected a CodebergException, got $other")

  test("a 401 reaches the typed rail as a Left reporting the very same failure"):
    onApi(responding(401, UserKeyApiSuite.UnauthorizedBody)): api =>
      for
        raised <- api.gpgKeys(PageParams.First).failed
        typed  <- api.attempt.gpgKeys(PageParams.First)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a unit-returning delete as well"):
    onApi(responding(404, UserKeyApiSuite.NotFoundBody)): api =>
      for
        raised <- api.deleteGpgKey(Gpg).failed
        typed  <- api.attempt.deleteGpgKey(Gpg)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on the plain-text endpoint too"):
    onApi(responding(403, UserKeyApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.verificationToken().failed
        typed  <- api.attempt.verificationToken()
      yield assertRailsAgree(raised, typed)

  test("a 422 is an Api failure, which is how a signature that did not verify arrives"):
    onApi(responding(422, UserKeyApiSuite.ValidationBody)): api =>
      api.attempt.createGpgKey(orFail(CreateGpgKey.of("BLOCK"))).map:
        case Left(CodebergError.Api(_, status, _)) => assertEquals(status, 422)
        case other                                 => fail(s"expected an Api failure, got $other")

  test("a 200 whose payload does not fit the model becomes DecodingFailed, never an escaping codec exception"):
    onApi(responding(200, """{"key_id": "3AA5C34371567BD2"}""")): api =>
      api.attempt.gpgKey(Gpg).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.id")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a failure carries the operation id of the endpoint it came from, so an alert can name it"):
    onApi(responding(403, UserKeyApiSuite.ForbiddenBody)): api =>
      api.attempt.deleteKey(Ssh).map(outcome => assertEquals(operationOf(outcome), UserKeyApi.DeleteKeyOperation))

  // --- harness --------------------------------------------------------------

  /** Builds the API under test on a pipeline over `backend`, releasing the timer whatever happens. */
  private def onApi[A](backend: Backend[Future])(use: UserKeyApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(UserKeyApi(pipeline)))

/** The response bodies this suite stubs, hand-written from `spec/swagger.v1.json`; see the class note. */
object UserKeyApiSuite:

  private val KeyMaterial: String = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5 laptop"

  private val PublicKeyBody: String =
    s"""{"id": 12, "key": "$KeyMaterial", "title": "laptop", "read_only": true, "verified": false}"""

  private val GpgKeyBody: String =
    """{"id": 7, "key_id": "3AA5C34371567BD2", "can_sign": true, "verified": false, "emails": null}"""

  private val VerifiedGpgKeyBody: String =
    """{"id": 7, "key_id": "3AA5C34371567BD2", "can_sign": true, "verified": true}"""

  private val GpgKeyListBody: String = s"[$GpgKeyBody]"

  private val UnauthorizedBody: String = """{"message":"token is required","url":"https://codeberg.org/api/swagger"}"""

  private val ForbiddenBody: String =
    """{"message":"token does not have at least one of required scope(s): [write:user]"}"""

  private val NotFoundBody: String =
    """{"message":"GPGKey does not exist","url":"https://codeberg.org/api/swagger"}"""

  private val ValidationBody: String =
    """{"message":"CreateGPGKey","url":"https://codeberg.org/api/swagger","errors":["invalid key"]}"""
