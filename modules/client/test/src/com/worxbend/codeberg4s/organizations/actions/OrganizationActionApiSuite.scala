package com.worxbend.codeberg4s.organizations.actions

import com.worxbend.codeberg4s.ClientSuiteHarness
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.organizations.OrgName
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.repositories.actions.CreateVariable
import com.worxbend.codeberg4s.repositories.actions.RegisterRunner
import com.worxbend.codeberg4s.repositories.actions.RunnerId
import com.worxbend.codeberg4s.repositories.actions.RunnerLabel
import com.worxbend.codeberg4s.repositories.actions.RunnerStatus
import com.worxbend.codeberg4s.repositories.actions.RunnerVisibility
import com.worxbend.codeberg4s.repositories.actions.SecretName
import com.worxbend.codeberg4s.repositories.actions.SecretValue
import com.worxbend.codeberg4s.repositories.actions.UpdateVariable
import com.worxbend.codeberg4s.repositories.actions.VariableName

import sttp.client4.Backend
import sttp.client4.testing.BackendStub
import sttp.client4.testing.RecordingBackend
import sttp.client4.testing.ResponseStub
import sttp.model.Header
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.Future

/** [[OrganizationActionApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The subject is the wiring — which URI is dialled, which query parameters and which body are sent, which calls may be
  * repeated, and whether the two rails agree about a failure. Decoding itself is asserted in `modules/codec` against
  * the repository Actions DTOs this group reuses, so the payloads here are the smallest bodies that exercise a seam.
  *
  * '''No golden fixture backs this group.''' Every payload below was written from `spec/swagger.v1.json`; the Actions
  * endpoints all require a token and the golden harvest was anonymous.
  *
  * '''The retry assertions are the point of several of these tests.''' This group deliberately differs from
  * `RepositoryActionApi` on two `DELETE`s, and a difference that is only written in a Scaladoc is a difference that
  * drifts — so it is pinned here by counting interactions against a backend that fails once and then succeeds.
  */
final class OrganizationActionApiSuite extends FunSuite with ClientSuiteHarness:

  private val Org: OrgName = orFail(OrgName.from("forgejo"))

  private val Runner: RunnerId = RunnerId.of(37L)

  private val Secret: SecretName = orFail(SecretName.from("DEPLOY_KEY"))

  private val Variable: VariableName = orFail(VariableName.from("ENVIRONMENT"))

  private val Endpoint: String = s"$Root/orgs/forgejo/actions"

  // --- runners --------------------------------------------------------------

  test("orgs.actions.runners.list dials the organisation's runners and always states the visibility it wants"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api.runners(Org, RunnerVisibility.OwnedOnly, window(2, 25)).map: _ =>
        assertEquals(pathOf(backend), s"$Endpoint/runners")
        assertEquals(queryOf(backend).sorted, List("limit" -> "25", "page" -> "2", "visible" -> "false"))

  test("orgs.actions.runners.list pages from the Link header and not from how many runners came back"):
    val backend = RecordingBackend(
      responding(200, OrganizationActionApiSuite.RunnerListBody, OrganizationActionApiSuite.PagedHeaders)
    )

    onApi(backend): api =>
      api.runners(Org, RunnerVisibility.AllVisible, window(1, 30)).map: page =>
        assertEquals(page.size, 1)
        assertEquals(page.totalCount, Some(97))
        assertEquals(page.nextPage, Some(orFail(PageNumber.from(2))))
        assertEquals(page.isLast, false)

  test("the single-runner read dials one runner and decodes its status"):
    val backend = RecordingBackend(responding(200, OrganizationActionApiSuite.RunnerBody))

    onApi(backend): api =>
      api.runner(Org, Runner).map: runner =>
        assertEquals(pathOf(backend), s"$Endpoint/runners/37")
        assertEquals(runner.status, Some(RunnerStatus.Idle))

  test("orgs.actions.runners.register POSTs the option body and is never repeated"):
    val backend = RecordingBackend(
      cycling(503, "", 201, OrganizationActionApiSuite.RegisteredBody)
    )

    onApi(backend): api =>
      api.attempt.registerRunner(Org, orFail(RegisterRunner.named("build-box-3")).ephemeral).map: outcome =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Endpoint/runners")
        assertEquals(Json.parse(bodyOf(backend)).toOption.flatMap(_.field("name")).flatMap(_.strOpt), Some("build-box-3"))
        assertEquals(Json.parse(bodyOf(backend)).toOption.flatMap(_.field("ephemeral")).flatMap(_.boolOpt), Some(true))
        assertEquals(backend.allInteractions.size, 1, "a POST was repeated")
        assert(outcome.isLeft, "the 503 should have reached the caller")

  test("orgs.actions.runners.register hands back a token that renders as a mask"):
    onApi(responding(201, OrganizationActionApiSuite.RegisteredBody)): api =>
      api.registerRunner(Org, orFail(RegisterRunner.named("build-box-3"))).map: registered =>
        assertEquals(registered.token.reveal, "QWERTY123")
        assertEquals(registered.token.toString, "***")

  test("orgs.actions.runners.delete is repeated, because a runner id is never reused"):
    val backend = RecordingBackend(cycling(503, "", 204, ""))

    onApi(backend): api =>
      api.deleteRunner(Org, Runner).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Endpoint/runners/37")
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  test("orgs.actions.runners.registrationToken dials the deprecated endpoint and masks what it returns"):
    val backend = RecordingBackend(responding(200, """{"token": "REG-TOKEN"}"""))

    onApi(backend): api =>
      api.runnerRegistrationToken(Org).map: token =>
        assertEquals(pathOf(backend), s"$Endpoint/runners/registration-token")
        assertEquals(token.reveal, "REG-TOKEN")
        assertEquals(token.toString, "***")

  test("orgs.actions.runners.jobs.search comma-joins the labels into the one parameter Forgejo declares"):
    val backend = RecordingBackend(responding(200, OrganizationActionApiSuite.JobListBody))

    onApi(backend): api =>
      val labels = Vector(orFail(RunnerLabel.from("docker")), orFail(RunnerLabel.from("self-hosted")))

      api.searchRunnerJobs(Org, labels).map: jobs =>
        assertEquals(pathOf(backend), s"$Endpoint/runners/jobs")
        assertEquals(queryOf(backend), List("labels" -> "docker,self-hosted"))
        assertEquals(jobs.size, 1)

  test("orgs.actions.runners.jobs.search with no labels asks for every job rather than for none"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api.searchRunnerJobs(Org, Vector.empty).map(_ => assertEquals(queryOf(backend), List.empty[(String, String)]))

  // --- secrets --------------------------------------------------------------

  test("orgs.actions.secrets.list pages the organisation's secrets and reports no values"):
    val backend = RecordingBackend(responding(200, OrganizationActionApiSuite.SecretListBody))

    onApi(backend): api =>
      api.secrets(Org, window(1, 50)).map: page =>
        assertEquals(pathOf(backend), s"$Endpoint/secrets")
        assertEquals(queryOf(backend).sorted, List("limit" -> "50", "page" -> "1"))
        assertEquals(page.items.map(_.name.value), Vector("DEPLOY_KEY"))

  test("orgs.actions.secrets.set PUTs the material under 'data' and is repeated, because it sets a stated value"):
    val backend = RecordingBackend(cycling(503, "", 204, ""))

    onApi(backend): api =>
      api.setSecret(Org, Secret, orFail(SecretValue.from("s3cr3t"))).map: _ =>
        assertEquals(methodOf(backend), "PUT")
        assertEquals(pathOf(backend), s"$Endpoint/secrets/DEPLOY_KEY")
        assertEquals(Json.parse(bodyOf(backend)).toOption.flatMap(_.field("data")).flatMap(_.strOpt), Some("s3cr3t"))
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  test("orgs.actions.secrets.delete is NOT repeated, unlike the repository call of the same name"):
    val backend = RecordingBackend(cycling(503, "", 204, ""))

    onApi(backend): api =>
      api.attempt.deleteSecret(Org, Secret).map: outcome =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Endpoint/secrets/DEPLOY_KEY")
        assertEquals(backend.allInteractions.size, 1, "a delete addressed by a reusable name was retried")
        assert(outcome.isLeft, "the 503 should have reached the caller")

  // --- variables ------------------------------------------------------------

  test("orgs.actions.variables.list pages the organisation's variables, values and all"):
    val backend = RecordingBackend(responding(200, OrganizationActionApiSuite.VariableListBody))

    onApi(backend): api =>
      api.variables(Org, window(3, 10)).map: page =>
        assertEquals(pathOf(backend), s"$Endpoint/variables")
        assertEquals(queryOf(backend).sorted, List("limit" -> "10", "page" -> "3"))
        assertEquals(page.items.map(_.value), Vector("staging"))

  test("the single-variable read dials one variable by name"):
    val backend = RecordingBackend(responding(200, OrganizationActionApiSuite.VariableBody))

    onApi(backend): api =>
      api.variable(Org, Variable).map: variable =>
        assertEquals(pathOf(backend), s"$Endpoint/variables/ENVIRONMENT")
        assertEquals(variable.name.value, "ENVIRONMENT")

  test("orgs.actions.variables.create POSTs only the value and is never repeated"):
    val backend = RecordingBackend(cycling(503, "", 201, ""))

    onApi(backend): api =>
      api.attempt.createVariable(Org, Variable, CreateVariable.of("staging")).map: outcome =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Endpoint/variables/ENVIRONMENT")
        assertEquals(Json.parse(bodyOf(backend)).toOption.map(_.keys.toList), Some(List("value")))
        assertEquals(backend.allInteractions.size, 1, "a POST was repeated")
        assert(outcome.isLeft, "the 503 should have reached the caller")

  test("orgs.actions.variables.update without a rename is repeated"):
    val backend = RecordingBackend(cycling(503, "", 204, ""))

    onApi(backend): api =>
      api.updateVariable(Org, Variable, UpdateVariable.of("production")).map: _ =>
        assertEquals(methodOf(backend), "PUT")
        assertEquals(Json.parse(bodyOf(backend)).toOption.flatMap(_.field("value")).flatMap(_.strOpt), Some("production"))
        assertEquals(backend.allInteractions.size, 2, "the 503 was not retried")

  test("orgs.actions.variables.update carrying a rename is not repeated, because the old name stops existing"):
    val backend = RecordingBackend(cycling(503, "", 204, ""))

    onApi(backend): api =>
      val command = UpdateVariable.of("production").movedTo(orFail(VariableName.from("STAGE")))

      api.attempt.updateVariable(Org, Variable, command).map: outcome =>
        assertEquals(Json.parse(bodyOf(backend)).toOption.flatMap(_.field("name")).flatMap(_.strOpt), Some("STAGE"))
        assertEquals(backend.allInteractions.size, 1, "a renaming PUT was repeated")
        assert(outcome.isLeft, "the 503 should have reached the caller")

  test("orgs.actions.variables.delete is NOT repeated, for the reason the secret delete is not"):
    val backend = RecordingBackend(cycling(503, "", 204, ""))

    onApi(backend): api =>
      api.attempt.deleteVariable(Org, Variable).map: outcome =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(backend.allInteractions.size, 1, "a delete addressed by a reusable name was retried")
        assert(outcome.isLeft, "the 503 should have reached the caller")

  // --- both rails -----------------------------------------------------------

  test("a 404 fails the convenience rail with a CodebergException carrying the Api failure"):
    onApi(responding(404, OrganizationActionApiSuite.NotFoundBody)): api =>
      api.secrets(Org, window(1, 30)).failed.map:
        case CodebergException(error) =>
          assertEquals(summary(error), (OrganizationActionApi.ListSecretsOperation, 404, Some("GetOrgSecrets")))
        case other                    => fail(s"expected a CodebergException, got $other")

  test("a 404 reaches the typed rail as a Left reporting the very same failure"):
    onApi(responding(404, OrganizationActionApiSuite.NotFoundBody)): api =>
      for
        raised <- api.secrets(Org, window(1, 30)).failed
        typed  <- api.attempt.secrets(Org, window(1, 30))
      yield (raised, typed) match
        case (CodebergException(convenience), Left(materialised)) =>
          assertEquals(summary(materialised), summary(convenience))
        case (convenience, materialised)                          =>
          fail(s"the rails disagreed: $convenience versus $materialised")

  test("a 400 on a rejected secret name reaches both rails as the same Api failure"):
    onApi(responding(400, OrganizationActionApiSuite.BadRequestBody)): api =>
      val value = orFail(SecretValue.from("s3cr3t"))

      for
        raised <- api.setSecret(Org, Secret, value).failed
        typed  <- api.attempt.setSecret(Org, Secret, value)
      yield (raised, typed) match
        case (CodebergException(convenience), Left(materialised)) =>
          assertEquals(summary(materialised), summary(convenience))
          assertEquals(summary(materialised)._2, 400)
        case (convenience, materialised)                          =>
          fail(s"the rails disagreed: $convenience versus $materialised")

  test("a 200 whose variable payload names nothing becomes DecodingFailed, never an exception"):
    onApi(responding(200, """{"data": "staging"}""")): api =>
      api.attempt.variable(Org, Variable).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.name")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a bad element of a runner listing is reported at its own index"):
    onApi(responding(200, """[{"id": 37}, {"name": "no id here"}]""")): api =>
      api.attempt.runners(Org, RunnerVisibility.AllVisible, window(1, 30)).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$[1].id")
        case other                                             => fail(s"expected a decoding failure, got $other")

  // --- fixtures -------------------------------------------------------------

  /** Builds the API under test on a pipeline over `backend`, releasing the timer whatever happens. */
  private def onApi[A](backend: Backend[Future])(use: OrganizationActionApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(OrganizationActionApi(pipeline)))

  /** A backend that answers the first pair once and the second from then on — how a retry is made observable. */
  private def cycling(firstStatus: Int, firstBody: String, thenStatus: Int, thenBody: String): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(
      ResponseStub.adjust(firstBody, StatusCode(firstStatus)),
      ResponseStub.adjust(thenBody, StatusCode(thenStatus)),
    )

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour.
  *
  * All of them are hand-written from `spec/swagger.v1.json`; no organisation Actions endpoint has a golden capture.
  */
object OrganizationActionApiSuite:

  private val RunnerBody: String =
    """{"id": 37, "uuid": "abc", "name": "build-box-3", "status": "idle", "labels": ["docker"], "ephemeral": false}"""

  private val RunnerListBody: String = s"[$RunnerBody]"

  private val RegisteredBody: String = """{"id": 37, "uuid": "abc", "token": "QWERTY123"}"""

  private val JobListBody: String =
    """[{"id": 55, "run_id": 4711, "name": "build", "status": "waiting", "needs": null, "runs_on": null}]"""

  private val SecretListBody: String = """[{"name": "DEPLOY_KEY", "created_at": "2026-07-30T21:14:15+02:00"}]"""

  private val VariableBody: String = """{"name": "ENVIRONMENT", "data": "staging", "owner_id": 7, "repo_id": 0}"""

  private val VariableListBody: String = s"[$VariableBody]"

  /** The paging headers `docs/HAZARDS.md` §5 captured verbatim, with the host and path swapped for this endpoint's. */
  private val PagedHeaders: List[Header] =
    List(
      Header("X-Total-Count", "97"),
      Header(
        "Link",
        "<https://forge.example/api/v1/orgs/forgejo/actions/runners?limit=30&page=2>; rel=\"next\"," +
          "<https://forge.example/api/v1/orgs/forgejo/actions/runners?limit=30&page=4>; rel=\"last\"",
      ),
    )

  private val NotFoundBody: String =
    """{"message":"GetOrgSecrets","url":"https://codeberg.org/api/swagger","errors":["org does not exist"]}"""

  private val BadRequestBody: String =
    """{"message":"secret name is invalid","url":"https://codeberg.org/api/swagger"}"""
