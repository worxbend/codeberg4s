package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.auth.{ApiToken, Auth, Password}
import com.worxbend.codeberg4s.core.{ApiPipeline, Telemetry}
import com.worxbend.codeberg4s.organizations.OrgName
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.actions.{RegisterRunner, SecretName, SecretValue}
import com.worxbend.codeberg4s.repositories.admin.{CreatePushMirror, MigrateRepository, RemoteCredential}
import com.worxbend.codeberg4s.repositories.hooks.{CreateHook, EditHook, HookContentType, HookId, HookSecret, HookType}
import com.worxbend.codeberg4s.syntax.discard
import com.worxbend.codeberg4s.users.Username
import com.worxbend.codeberg4s.users.account.{OAuth2ApplicationDefinition, OAuth2ApplicationId}
import com.worxbend.codeberg4s.users.social.CreateAccessToken

import sttp.capabilities.Effect
import sttp.client4.testing.RecordingBackend
import sttp.client4.{Backend, GenericRequest, Response}
import sttp.model.Header
import sttp.monad.MonadError

import munit.FunSuite

import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** End-to-end diagnostic policy over synthetic backend responses; never opens a socket. */
final class DiagnosticEchoApiSuite extends FunSuite with ClientSuiteHarness:

  private val token        = "diagnostic-only-configured-token"
  private val password     = " diagnostic-only-pässwörd-雪 "
  private val username     = "diagnostic-üser"
  private val basicWire    = Base64.getEncoder.encodeToString(s"$username:$password".getBytes(StandardCharsets.UTF_8))
  private val basicAuth    = orFail(Auth.basic(username, orFail(Password.from(password))))
  private val issued       = "diagnostic-only-unconfigured-issued-secret"
  private val owner        = orFail(Owner.from("forgejo"))
  private val repo         = orFail(RepoName.from("forgejo"))
  private val org          = orFail(OrgName.from("forgejo"))
  private val handle       = orFail(Username.from("forgejo"))
  private val application  = orFail(OAuth2ApplicationId.from(7L))
  private val hookId       = orFail(HookId.from(42L))
  private val secretName   = orFail(SecretName.from("DEPLOY_KEY"))
  private val secretValue  = orFail(SecretValue.from(issued))
  private val runner       = orFail(RegisterRunner.named("diagnostic-runner"))
  private val definition   = orFail(OAuth2ApplicationDefinition.named("diagnostic-app"))
  private val tokenCommand = orFail(CreateAccessToken.named("diagnostic-token"))
  private val hookCommand  = CreateHook.to(HookType.Forgejo, "https://ci.example/hook", HookContentType.Json)
    .signedWith(orFail(HookSecret.from(issued)))
  private val hookEdit     = EditHook.Empty.signedWith(orFail(HookSecret.from(issued)))
  private val remote       = orFail(RemoteCredential.from(issued))
  private val migration    = MigrateRepository.from("https://remote.example/repo.git", repo).authenticatedWith(remote)
  private val migrationPassword = MigrateRepository.from("https://remote.example/repo.git", repo)
    .authenticatedAs("remote-user", remote).asMirror
  private val mirror = CreatePushMirror.to("https://remote.example/repo.git").authenticatedAs("remote-user", remote)

  private final case class Invocation(
      name: String,
      raised: CodebergClient => Future[Unit],
      typed: CodebergClient => Future[Either[CodebergError, Unit]],
  )

  private def invocation[A](name: String)(
      raised: CodebergClient => Future[A],
      typed: CodebergClient => Future[Either[CodebergError, A]],
  ): Invocation = Invocation(name, client => raised(client).map(_ => ()), client => typed(client).map(_.map(_ => ())))

  private val ordinary         = invocation("version")(_.version.get(), _.version.attempt.get())
  private val sensitiveReaders = Vector(
    invocation("token listing")(
      _.users.tokens.list(handle, PageParams.First),
      _.users.tokens.attempt.list(handle, PageParams.First)
    ),
    invocation("OAuth get")(_.users.applications.get(application), _.users.applications.attempt.get(application)),
    invocation("OAuth list")(
      _.users.applications.list(PageParams.First),
      _.users.applications.attempt.list(PageParams.First)
    ),
    invocation("repository hook get")(
      _.repos.hooks.get(owner, repo, hookId),
      _.repos.hooks.attempt.get(owner, repo, hookId)
    ),
    invocation("repository hook list")(
      _.repos.hooks.list(owner, repo, PageParams.First),
      _.repos.hooks.attempt.list(owner, repo, PageParams.First)
    ),
    invocation("organization hook get")(
      _.organizations.hooks.get(org, hookId),
      _.organizations.hooks.attempt.get(org, hookId)
    ),
    invocation("organization hook list")(
      _.organizations.hooks.list(org, PageParams.First),
      _.organizations.hooks.attempt.list(org, PageParams.First)
    ),
    invocation("user hook get")(_.users.hooks.get(hookId), _.users.hooks.attempt.get(hookId)),
    invocation("user hook list")(_.users.hooks.list(PageParams.First), _.users.hooks.attempt.list(PageParams.First)),
  )
  private val sensitiveWrites  = Vector(
    invocation("token create")(
      _.users.tokens.create(handle, tokenCommand),
      _.users.tokens.attempt.create(handle, tokenCommand)
    ),
    invocation("OAuth create")(_.users.applications.create(definition), _.users.applications.attempt.create(definition)),
    invocation("OAuth update")(
      _.users.applications.update(application, definition),
      _.users.applications.attempt.update(application, definition)
    ),
    invocation("repository runner issuance")(
      _.repos.actions.config.registerRunner(owner, repo, runner),
      _.repos.actions.config.attempt.registerRunner(owner, repo, runner)
    ),
    invocation("organization runner issuance")(
      _.organizations.actions.registerRunner(org, runner),
      _.organizations.actions.attempt.registerRunner(org, runner)
    ),
    invocation("user runner issuance")(
      _.users.actions.registerRunner(runner),
      _.users.actions.attempt.registerRunner(runner)
    ),
    invocation("repository secret unit write")(
      _.repos.actions.config.setSecret(owner, repo, secretName, secretValue),
      _.repos.actions.config.attempt.setSecret(owner, repo, secretName, secretValue)
    ),
    invocation("organization secret unit write")(
      _.organizations.actions.setSecret(org, secretName, secretValue),
      _.organizations.actions.attempt.setSecret(org, secretName, secretValue)
    ),
    invocation("user secret unit write")(
      _.users.actions.setSecret(secretName, secretValue),
      _.users.actions.attempt.setSecret(secretName, secretValue)
    ),
    invocation("repository hook create")(
      _.repos.hooks.create(owner, repo, hookCommand),
      _.repos.hooks.attempt.create(owner, repo, hookCommand)
    ),
    invocation("organization hook create")(
      _.organizations.hooks.create(org, hookCommand),
      _.organizations.hooks.attempt.create(org, hookCommand)
    ),
    invocation("user hook create")(_.users.hooks.create(hookCommand), _.users.hooks.attempt.create(hookCommand)),
    invocation("repository hook edit")(
      _.repos.hooks.edit(owner, repo, hookId, hookEdit),
      _.repos.hooks.attempt.edit(owner, repo, hookId, hookEdit)
    ),
    invocation("organization hook edit")(
      _.organizations.hooks.edit(org, hookId, hookEdit),
      _.organizations.hooks.attempt.edit(org, hookId, hookEdit)
    ),
    invocation("user hook edit")(_.users.hooks.edit(hookId, hookEdit), _.users.hooks.attempt.edit(hookId, hookEdit)),
    invocation("migration token")(_.repos.admin.migrate(migration), _.repos.admin.attempt.migrate(migration)),
    invocation("pull mirror password")(
      _.repos.admin.migrate(migrationPassword),
      _.repos.admin.attempt.migrate(migrationPassword)
    ),
    invocation("push mirror password")(
      _.repos.admin.mirrors.addPushMirror(owner, repo, mirror),
      _.repos.admin.mirrors.attempt.addPushMirror(owner, repo, mirror)
    ),
  )

  private final class Observed extends Telemetry[Future]:
    private val recorded = CopyOnWriteArrayList[(String, CallContext, Option[CodebergError])]()
    override def onRequest(ctx: CallContext): Future[Unit]                     = record("request", ctx, None)
    override def onResponse(ctx: CallContext, status: Int): Future[Unit]       = record(s"response $status", ctx, None)
    override def onError(ctx: CallContext, error: CodebergError): Future[Unit] = record("error", ctx, Some(error))

    private def record(kind: String, ctx: CallContext, error: Option[CodebergError]): Future[Unit] =
      recorded.add((kind, ctx, error)).discard
      Future.unit
    def events: Vector[(String, CallContext, Option[CodebergError])] = recorded.asScala.toVector

  private def withClient[A](backend: Backend[Future], auth: Auth, observed: Observed)(
      use: CodebergClient => Future[A]
  ): Future[A] =
    val config = CodebergConfig(auth).copy(baseUri = Instance, retry = ClientSuiteHarness.PromptRetry)
    val client = CodebergClient.usingBackend(config, backend, observed)
    use(client).transform: outcome =>
      client.close()
      outcome

  private def clean(text: String, secrets: Vector[String]): Unit =
    secrets.foreach(secret => assert(!text.contains(secret), "synthetic secret survived a diagnostic"))

  private def cleanError(error: CodebergError, secrets: Vector[String]): Unit =
    clean(error.toString, secrets)
    clean(error.describe, secrets)

  private def cleanEvents(observed: Observed, secrets: Vector[String]): Unit =
    observed.events.foreach: (_, ctx, error) =>
      clean(ctx.toString, secrets)
      error.foreach(value => cleanError(value, secrets))

  private def unwrap(thrown: Throwable): CodebergError = thrown match
    case CodebergException(error) => error
    case other                    => fail(s"expected CodebergException, got ${other.getClass.getName}")

  private def left(result: Either[CodebergError, Unit]): CodebergError = result match
    case Left(error) => error
    case Right(_)    => fail("expected the typed rail to fail")

  private def both(client: CodebergClient, call: Invocation)(check: CodebergError => Unit): Future[Unit] =
    for
      raised <- call.raised(client).failed
      typed  <- call.typed(client)
    yield
      val convenience  = unwrap(raised)
      val materialized = left(typed)
      check(convenience)
      check(materialized)
      assertEquals(materialized.productPrefix, convenience.productPrefix)
      clean(
        Option(raised.getMessage).getOrElse("") + raised.toString,
        Vector(token, password, password.trim, basicWire, issued)
      )

  private def assertApi403(error: CodebergError): Unit = error match
    case CodebergError.Api(_, 403, _, _) => ()
    case other                           => fail(s"expected Api(403), got $other")

  private def assertEvents(observed: Observed, response: Option[Int]): Unit =
    val expected = response.fold(Vector("request", "error", "error"))(status =>
      Vector("request", s"response $status", "error", "error")
    )
    assertEquals(observed.events.map(_._1), expected ++ expected)

  private def echoBody(echo: String): String =
    s"""{"message":"rejected $echo","errors":["invalid $echo"],"url":"https://docs.example/$echo"}"""

  Vector(
    ("token", Auth.Token(orFail(ApiToken.from(token))), Vector(token)),
    ("non-ASCII UTF-8 Basic", basicAuth, Vector(password, basicWire)),
  ).foreach: (label, auth, secrets) =>
    test(s"$label server fields and request id are sanitized on both public rails and every callback"):
      val observed = Observed()
      val echo     = secrets.mkString(" / ")
      withClient(responding(403, echoBody(echo), List(Header("X-Request-Id", s"request-$echo"))), auth, observed):
        client =>
          val outcome = both(client, ordinary): error =>
            assertApi403(error)
            cleanError(error, secrets)
            error match
              case CodebergError.Api(ctx, _, body, _) =>
                assert(ctx.requestId.exists(_.contains("request-")), "safe request-id text must remain useful")
                assert(body.message.exists(_.contains("rejected")), "safe server explanation must survive")
                assert(body.errors.exists(_.contains("invalid")), "safe validation explanation must survive")
                assert(body.url.exists(_.contains("docs.example")), "safe URL must survive")
              case _                                  => ()
          outcome.map: _ =>
            assertEvents(observed, Some(403))
            cleanEvents(observed, secrets)

    test(s"$label ordinary reader echoes are sanitized on both rails before telemetry"):
      val observed = Observed()
      val echo     = secrets.head
      val body     = s"""{"$echo":0,"$echo":1}"""
      withClient(responding(200, body), auth, observed): client =>
        val outcome = both(client, ordinary): error =>
          cleanError(error, secrets)
          error match
            case CodebergError.DecodingFailed(_, snippet, _, reason) =>
              assert(snippet.contains("0"), "ordinary non-sensitive excerpt was wholly suppressed")
              assert(reason.nonEmpty, "ordinary reader explanation was lost")
            case other                                               => fail(s"expected DecodingFailed, got $other")
        outcome.map: _ =>
          assertEvents(observed, Some(200))
          cleanEvents(observed, secrets)

    test(s"$label successful response request-id is sanitized before the onResponse callback"):
      val observed = Observed()
      withClient(
        responding(200, """{"version":"12.0.0"}""", List(Header("X-Request-Id", secrets.mkString("id-", " / ", " end")))),
        auth,
        observed
      ): client =>
        for
          raised <- client.version.get()
          typed  <- client.version.attempt.get()
        yield
          assertEquals(raised.raw, "12.0.0")
          assertEquals(typed.map(_.raw), Right("12.0.0"))
          assertEquals(observed.events.map(_._1), Vector("request", "response 200", "request", "response 200"))
          cleanEvents(observed, secrets)

    Vector(false, true).foreach: synchronous =>
      test(
        s"$label backend exception echo (${if synchronous then "synchronous" else "async"}) is sanitized on both rails"
      ):
        val observed = Observed()
        val backend  = EchoBackend(responding(200, "{}"), secrets.mkString("backend echo ", " / ", " end"), synchronous)
        withClient(backend, auth, observed): client =>
          val outcome = both(client, ordinary): error =>
            cleanError(error, secrets)
            error match
              case CodebergError.Transport(_, TransportCause.Unknown(detail)) =>
                assert(detail.contains("backend echo"), "ordinary backend reason must remain useful")
              case other => fail(s"expected Unknown transport diagnostic, got $other")
          outcome.map: _ =>
            assertEvents(observed, None)
            cleanEvents(observed, secrets)

  Vector(
    ("token", Auth.Token(orFail(ApiToken.from(token))), Vector(token)),
    ("UTF-8 Basic", basicAuth, Vector(password, basicWire))
  ).foreach: (label, auth, secrets) =>
    secrets.foreach: secret =>
      test(s"$label backend detail scrubs before the 200-character boundary (${secret.length} chars)"):
        val prefix   = "x".repeat(195)
        val observed = Observed()
        val backend  = EchoBackend(responding(200, "{}"), prefix + secret + " trailing data", false)
        withClient(backend, auth, observed): client =>
          val outcome = both(client, ordinary): error =>
            error match
              case CodebergError.Transport(_, TransportCause.Unknown(detail)) =>
                assert(detail.startsWith(prefix), "ordinary backend prefix must survive")
                assert(!detail.contains(secret.take(5)), "backend clipping leaked the credential prefix")
                assert(detail.length <= 203, "transport detail became unbounded")
              case other => fail(s"expected Unknown transport diagnostic, got $other")
          outcome.map: _ =>
            assertEvents(observed, None)
            observed.events.flatMap(_._3).foreach: error =>
              assert(!error.toString.contains(secret.take(5)), "telemetry leaked a clipped credential prefix")

  test("ordinary configured-token excerpt is scrubbed before truncation crosses the secret on both rails"):
    val prefix   = "x".repeat(CodebergError.MaxSnippetLength - 5)
    val body     = prefix + token + " trailing data"
    val observed = Observed()
    withClient(responding(200, body), Auth.Token(orFail(ApiToken.from(token))), observed): client =>
      val outcome = both(client, ordinary): error =>
        error match
          case CodebergError.DecodingFailed(_, snippet, _, _) =>
            assert(snippet.startsWith(prefix))
            assert(!snippet.contains(token.take(5)), "ordinary truncation leaked a credential prefix")
          case other                                          => fail(s"expected DecodingFailed, got $other")
      outcome.map: _ =>
        assertEvents(observed, Some(200))
        observed.events.flatMap(_._3).foreach:
          case CodebergError.DecodingFailed(_, snippet, _, _) =>
            assert(!snippet.contains(token.take(5)), "telemetry leaked a credential prefix")
          case other                                          => fail(s"expected decoder telemetry, got $other")

  (sensitiveReaders ++ sensitiveWrites).foreach: call =>
    test(s"${call.name} non-2xx withholds an unconfigured body secret on both rails before telemetry"):
      val observed = Observed()
      withClient(responding(403, echoBody(issued)), basicAuth, observed): client =>
        val outcome = both(client, call): error =>
          assertApi403(error)
          cleanError(error, Vector(issued))
        outcome.map: _ =>
          assertEvents(observed, Some(403))
          cleanEvents(observed, Vector(issued))

  sensitiveWrites.foreach: call =>
    test(s"${call.name} transport diagnostics withhold an unconfigured body secret on both rails"):
      val observed = Observed()
      withClient(EchoBackend(responding(200, "{}"), s"request body contained $issued", false), basicAuth, observed):
        client =>
          val outcome = both(client, call): error =>
            cleanError(error, Vector(issued))
            error match
              case CodebergError.Transport(_, TransportCause.Unknown(_)) => ()
              case other => fail(s"expected Unknown transport diagnostic, got $other")
          outcome.map: _ =>
            assertEvents(observed, None)
            cleanEvents(observed, Vector(issued))

  (sensitiveReaders ++ sensitiveWrites.filterNot(_.name.contains("secret unit write"))).foreach: call =>
    Vector(false, true).foreach: readerReason =>
      test(
        s"${call.name} malformed credential response withholds ${if readerReason then "reader reason" else "snippet"} on both rails"
      ):
        val objectBody = if readerReason then s"""{"$issued":0,"$issued":1}"""
        else s"""{"sha1":"$issued","client_secret":"$issued","config":{"secret":"$issued"}}"""
        val body = if call.name.endsWith("list") || call.name.endsWith("listing") then s"[$objectBody]" else objectBody
        val observed = Observed()
        withClient(responding(200, body), basicAuth, observed): client =>
          val outcome = both(client, call): error =>
            cleanError(error, Vector(issued))
            error match
              case CodebergError.DecodingFailed(_, snippet, path, reason) =>
                assertEquals(snippet, ApiPipeline.redactedSnippet(body.getBytes(StandardCharsets.UTF_8).length))
                assertEquals(path, JsonPath.Root)
                assert(!reason.contains("duplicate key"), "sensitive reader reason was not withheld")
              case other => fail(s"expected DecodingFailed, got $other")
          outcome.map: _ =>
            assertEvents(observed, Some(200))
            cleanEvents(observed, Vector(issued))

  private val headerPasswordMaterial = password.trim
  private val headerEchoes           = Vector(
    "raw whole"               -> password,
    "raw leading edge"        -> s"${password}ordinary-id-suffix",
    "raw trailing edge"       -> s"ordinary-id-prefix$password",
    "native fully trimmed"    -> headerPasswordMaterial,
    "native leading trimmed"  -> s"${password.stripLeading()}ordinary-id-suffix",
    "native trailing trimmed" -> s"ordinary-id-prefix${password.stripTrailing()}",
  )

  private val headerOutcomes = Vector(
    ("success", 200, """{"version":"12.0.0"}"""),
    ("status failure", 403, echoBody("ordinary reason")),
    ("decoder failure", 200, "{"),
    ("retry failure", 503, echoBody("ordinary reason")),
  )

  headerEchoes.foreach: (representation, header) =>
    headerOutcomes.foreach: (outcomeName, status, body) =>
      test(s"Basic $representation header hides password material on both rails for $outcomeName and every callback"):
        val observed = Observed()
        withClient(responding(status, body, List(Header("X-Request-Id", header))), basicAuth, observed): client =>
          val outcome = if outcomeName.equals("success") then
            for
              raised <- client.version.get()
              typed  <- client.version.attempt.get()
            yield
              assertEquals(raised.raw, "12.0.0")
              assertEquals(typed.map(_.raw), Right("12.0.0"))
          else
            both(client, ordinary): error =>
              cleanError(error, Vector(headerPasswordMaterial, basicWire))
              error match
                case CodebergError.Api(_, 403, _, _) if outcomeName.equals("status failure")           => ()
                case CodebergError.DecodingFailed(_, _, _, _) if outcomeName.equals("decoder failure") => ()
                case CodebergError.RetriesExhausted(_, 3, CodebergError.Api(_, 503, _, _))
                    if outcomeName.equals("retry failure") => ()
                case other => fail(s"expected $outcomeName category, got $other")
          outcome.map: _ =>
            cleanEvents(observed, Vector(headerPasswordMaterial, basicWire))
            if outcomeName.equals("success") then
              assertEquals(observed.events.map(_._1), Vector("request", "response 200", "request", "response 200"))
            else if outcomeName.equals("retry failure") then
              assertEquals(observed.events.count(_._1.equals("request")), 6)
              assertEquals(observed.events.count(_._1.equals("response 503")), 6)
              assertEquals(observed.events.count(_._1.equals("error")), 8)
            else assertEvents(observed, Some(status))
            if header.contains("ordinary-id-") then
              val responseIds = observed.events.filter(_._1.startsWith("response")).map(_._2.requestId)
              assert(responseIds.nonEmpty)
              assert(responseIds.forall(_.exists(_.contains("ordinary-id-"))))

  headerOutcomes.foreach: (outcomeName, status, body) =>
    test(s"Basic ordinary normalized request id survives both public rails for $outcomeName"):
      val observed = Observed()
      withClient(
        responding(status, body, List(Header("X-Request-Id", " \t ordinary-request-id-42 \t "))),
        basicAuth,
        observed,
      ): client =>
        val outcome = if outcomeName.equals("success") then
          for
            raised <- client.version.get()
            typed  <- client.version.attempt.get()
          yield
            assertEquals(raised.raw, "12.0.0")
            assertEquals(typed.map(_.raw), Right("12.0.0"))
        else both(client, ordinary)(_ => ())
        outcome.map: _ =>
          val responseIds = observed.events.filter(_._1.startsWith("response")).map(_._2.requestId)
          assert(responseIds.nonEmpty)
          assertEquals(responseIds.distinct, Vector(Some("ordinary-request-id-42")))
          val failureIds  = observed.events.filter(_._1.equals("error")).map(_._2.requestId)
          assertEquals(
            failureIds.distinct,
            if outcomeName.equals("success") then Vector.empty else Vector(Some("ordinary-request-id-42"))
          )

  Vector(false, true).foreach: synchronous =>
    test(
      s"Basic raw password-only backend failure scrubs before normalization (${if synchronous then "sync" else "async"})"
    ):
      val observed = Observed()
      withClient(EchoBackend(responding(200, "{}"), password, synchronous), basicAuth, observed): client =>
        both(client, ordinary)(error => cleanError(error, Vector(headerPasswordMaterial, basicWire))).map: _ =>
          assertEvents(observed, None)
          cleanEvents(observed, Vector(headerPasswordMaterial, basicWire))

  test("non-ASCII Basic redaction fixture is the actual UTF-8 Authorization wire value"):
    val backend  = RecordingBackend(responding(200, """{"version":"12.0.0"}"""))
    val observed = Observed()
    withClient(backend, basicAuth, observed): client =>
      client.version.get().map: _ =>
        assertEquals(firstRequest(backend).header("Authorization"), Some(s"Basic $basicWire"))

  test("synchronous backend body-secret echo on a sensitive unit write is withheld before telemetry"):
    val observed = Observed()
    val call     = invocation("user secret unit write")(
      _.users.actions.setSecret(secretName, secretValue),
      _.users.actions.attempt.setSecret(secretName, secretValue)
    )
    withClient(EchoBackend(responding(200, "{}"), s"request body contained $issued", true), basicAuth, observed):
      client =>
        val outcome = both(client, call): error =>
          cleanError(error, Vector(issued))
          error match
            case CodebergError.Transport(_, TransportCause.Unknown(_)) => ()
            case other => fail(s"expected Unknown transport diagnostic, got $other")
        outcome.map: _ =>
          assertEvents(observed, None)
          cleanEvents(observed, Vector(issued))

  test("ordinary retry exhaustion sanitizes every attempt and the final nested error on both rails"):
    val observed = Observed()
    withClient(
      responding(503, echoBody(token), List(Header("X-Request-Id", s"id-$token"))),
      Auth.Token(orFail(ApiToken.from(token))),
      observed
    ): client =>
      val outcome = both(client, ordinary): error =>
        cleanError(error, Vector(token))
        error match
          case CodebergError.RetriesExhausted(_, 3, CodebergError.Api(_, 503, _, _)) => ()
          case other => fail(s"expected nested retry failure, got $other")
      outcome.map: _ =>
        assertEquals(observed.events.count(_._1.equals("request")), 6)
        assertEquals(observed.events.count(_._1.equals("response 503")), 6)
        assertEquals(observed.events.count(_._1.equals("error")), 8)
        cleanEvents(observed, Vector(token))

  test("successful token issuance preserves explicit credential access on both public rails"):
    val body     = s"""{"id":42,"name":"ci","sha1":"$issued"}"""
    val observed = Observed()
    withClient(responding(201, body), basicAuth, observed): client =>
      for
        raised <- client.users.tokens.create(handle, tokenCommand)
        typed  <- client.users.tokens.attempt.create(handle, tokenCommand)
      yield
        assertEquals(raised.token.reveal, issued)
        assertEquals(typed.map(_.token.reveal), Right(issued))
        clean(raised.toString, Vector(issued))
        cleanEvents(observed, Vector(issued))
        assertEquals(observed.events.map(_._1), Vector("request", "response 201", "request", "response 201"))

  test("successful runner issuance preserves explicit token access on both public rails"):
    val body     = s"""{"id":37,"uuid":"abc","token":"$issued"}"""
    val observed = Observed()
    withClient(responding(201, body), basicAuth, observed): client =>
      for
        raised <- client.users.actions.registerRunner(runner)
        typed  <- client.users.actions.attempt.registerRunner(runner)
      yield
        assertEquals(raised.token.reveal, issued)
        assertEquals(typed.map(_.token.reveal), Right(issued))
        clean(raised.toString, Vector(issued))
        cleanEvents(observed, Vector(issued))

  test("successful secret unit writes ignore unexpected success bodies"):
    val observed = Observed()
    withClient(responding(204, issued), basicAuth, observed): client =>
      for
        raised <- client.users.actions.setSecret(secretName, secretValue)
        typed  <- client.users.actions.attempt.setSecret(secretName, secretValue)
      yield
        assertEquals(raised, ())
        assertEquals(typed, Right(()))
        cleanEvents(observed, Vector(issued))
        assertEquals(observed.events.map(_._1), Vector("request", "response 204", "request", "response 204"))

  test("successful OAuth issuance preserves explicit client-secret access"):
    val body     = s"""{"id":7,"name":"ci","client_secret":"$issued"}"""
    val observed = Observed()
    withClient(responding(201, body), basicAuth, observed): client =>
      client.users.applications.create(definition).map: created =>
        assertEquals(created.clientSecret.map(_.reveal), Some(issued))
        clean(created.toString, Vector(issued))
        cleanEvents(observed, Vector(issued))

  private final class EchoBackend(delegate: Backend[Future], echo: String, synchronous: Boolean)
      extends Backend[Future]:

    override def send[T](request: GenericRequest[T, Any & Effect[Future]]): Future[Response[T]] =
      request.discard
      if synchronous then throw IllegalStateException(echo)
      else Future.failed(IllegalStateException(echo))
    override def close(): Future[Unit]                                                          = Future.unit
    override def monad: MonadError[Future]                                                      = delegate.monad
