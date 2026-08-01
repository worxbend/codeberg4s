package com.worxbend.codeberg4s.it

import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.auth.ApiToken

import sttp.client4.DefaultSyncBackend
import sttp.client4.Request
import sttp.client4.Response
import sttp.client4.SyncBackend
import sttp.client4.asStringAlways
import sttp.client4.basicRequest
import sttp.model.HeaderNames
import sttp.model.MediaType
import sttp.model.Uri

/** The few calls that put a freshly started Forgejo into a state worth testing against.
  *
  * Everything here is deliberately outside [[com.worxbend.codeberg4s.CodebergClient]]. Issuing an access token and
  * creating a repository are not endpoints this library publishes, so they cannot be driven through the client — and
  * driving setup through the code under test would make a broken client look like a broken fixture. These two calls
  * therefore go out over a bare sttp backend, and the client is only ever used for the assertions.
  *
  * '''Error contract.''' Every method returns `Either[String, A]` and neither throws nor logs. A `Left` means the
  * instance could not be prepared and carries a short reason: a transport failure, a non-2xx status together with a
  * bounded snippet of the response body, a body that was not JSON, or a value the library's own smart constructors
  * rejected. Nothing here retries — a container on loopback that answers a 5xx to its own setup is broken, and hiding
  * that behind a retry would only move the failure into an assertion later on.
  *
  * '''Security contract.''' The admin password and the issued token are sent and never rendered. Reasons quote the
  * response body, never the request, so a credential cannot reach a test report through this object. The
  * [[com.worxbend.codeberg4s.auth.ApiToken]] returned by [[issueToken]] redacts itself in `toString`, exactly as it
  * does everywhere else in the library.
  */
object ForgejoBootstrap:

  /** The instance administrator a suite creates before it can ask for anything.
    *
    * A plain case class rather than a redacting type on purpose: this is the fixture's own throwaway credential for a
    * container that lives for the length of one suite, and the value has to be readable to be usable. It is never sent
    * to a real instance — the live suite is read-only and anonymous unless the operator supplies their own token.
    *
    * @param username
    *   the login, which is also the [[com.worxbend.codeberg4s.repositories.Owner]] of everything the suite creates
    * @param password
    *   the password used for the one basic-authenticated call that issues a token
    * @param email
    *   the address Forgejo requires; use a `.invalid` domain so it can never resolve
    */
  final case class AdminAccount(username: String, password: String, email: String)

  /** How much of an unexpected response body a reason may quote. */
  val SnippetLength: Int = 256

  /** Issues a personal access token for `admin` over `POST /users/{username}/tokens`.
    *
    * This is the one basic-authenticated call in the module. Forgejo has required an explicit scope list since 1.20, so
    * the request asks for `all`; a token without scopes is accepted and then fails every later call with a 403, which
    * is a confusing way to discover a typo in the fixture.
    *
    * '''Failures.''' `Left` when the request could not be sent, when the instance answered a non-2xx status — which is
    * what a wrong password looks like — when the body was not a JSON object, or when the object carried no `sha1`
    * string. The token material itself never appears in any of those reasons.
    *
    * @param baseUri
    *   the API root of the instance, `…/api/v1`
    * @param admin
    *   the account the token is issued for and authenticated as
    * @param tokenName
    *   the token's name, which Forgejo requires to be unique per user
    */
  def issueToken(baseUri: BaseUri, admin: AdminAccount, tokenName: String): Either[String, ApiToken] =
    val payload = ujson.Obj(
      "name"   -> ujson.Str(tokenName),
      "scopes" -> ujson.Arr(ujson.Str("all")),
    )
    for
      target   <- endpoint(baseUri, List("users", admin.username, "tokens"))
      request   = basicRequest
                    .post(target)
                    .auth
                    .basic(admin.username, admin.password)
                    .contentType(MediaType.ApplicationJson)
                    .body(ujson.write(payload))
                    .response(asStringAlways)
      response <- send(request)
      _        <- expectSuccess(response, "issuing an access token")
      material <- stringField(response.body, "sha1")
      token    <- ApiToken.from(material).left.map(Reasons.invalid)
    yield token

  /** Creates a repository owned by the token's user over `POST /user/repos`.
    *
    * The repository is initialised, because an empty Forgejo repository has no default branch and several of the
    * client's repository fields then arrive absent, which would make an assertion about them a test of Forgejo's empty
    * state rather than of this library.
    *
    * '''Failures.''' `Left` when the request could not be sent, or when the instance answered a non-2xx status — a
    * repeated name answers `409`, which is the usual reason a rerun against a reused container fails here.
    *
    * @param baseUri
    *   the API root of the instance, `…/api/v1`
    * @param token
    *   the token to authenticate with, sent as `Authorization: token …` and never rendered
    * @param name
    *   the repository name, which must not already exist for this owner
    * @param defaultBranch
    *   the branch the initial commit lands on
    */
  def createRepository(
      baseUri: BaseUri,
      token: ApiToken,
      name: String,
      defaultBranch: String,
  ): Either[String, Unit] =
    val payload = ujson.Obj(
      "name"           -> ujson.Str(name),
      "auto_init"      -> ujson.Bool(true),
      "default_branch" -> ujson.Str(defaultBranch),
      "private"        -> ujson.Bool(false),
    )
    for
      target   <- endpoint(baseUri, List("user", "repos"))
      request   = basicRequest
                    .post(target)
                    .header(HeaderNames.Authorization, s"token ${token.reveal}")
                    .contentType(MediaType.ApplicationJson)
                    .body(ujson.write(payload))
                    .response(asStringAlways)
      response <- send(request)
      _        <- expectSuccess(response, s"creating the repository `$name`")
    yield ()

  /** Joins path segments onto the configured API root.
    *
    * [[com.worxbend.codeberg4s.BaseUri]] has already validated the scheme and the authority, so a `Left` here means
    * sttp rejected something the domain type allowed, which is a defect rather than an environment problem.
    */
  private def endpoint(baseUri: BaseUri, segments: List[String]): Either[String, Uri] =
    Uri
      .parse(baseUri.value)
      .map(root => root.addPath(segments))
      .left
      .map(reason => s"the base URI is not a URI sttp accepts: $reason")

  /** Sends one request on a backend owned by this call and closed before it returns.
    *
    * A backend per request is wasteful and deliberate: bootstrap is two requests long, and a connection pool that
    * outlived them would be one more thing for a suite to own and forget to close.
    */
  private def send[A](request: Request[A]): Either[String, Response[A]] =
    val backend: SyncBackend = DefaultSyncBackend()
    try Reasons.attempting("sending the bootstrap request")(request.send(backend))
    finally backend.close()

  /** Accepts any 2xx and turns anything else into a reason carrying a bounded snippet of the body. */
  private def expectSuccess(response: Response[String], what: String): Either[String, Unit] =
    if response.code.isSuccess then Right(())
    else Left(s"$what answered ${response.code.code}: ${response.body.take(SnippetLength)}")

  /** Reads one string field out of a JSON object body, failing rather than guessing when it is absent. */
  private def stringField(body: String, name: String): Either[String, String] =
    for
      json   <- Reasons.attempting("parsing the response body as JSON")(ujson.read(body))
      fields <- json.objOpt.toRight("the response body was not a JSON object")
      value  <- fields.get(name).flatMap(entry => entry.strOpt).toRight(s"the response body carried no string `$name`")
    yield value
