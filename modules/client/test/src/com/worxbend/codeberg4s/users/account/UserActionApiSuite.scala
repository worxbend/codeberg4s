package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.actions.CreateVariable
import com.worxbend.codeberg4s.repositories.actions.RegisterRunner
import com.worxbend.codeberg4s.repositories.actions.RunnerId
import com.worxbend.codeberg4s.repositories.actions.RunnerLabel
import com.worxbend.codeberg4s.repositories.actions.RunnerRegistrationToken
import com.worxbend.codeberg4s.repositories.actions.RunnerVisibility
import com.worxbend.codeberg4s.repositories.actions.SecretName
import com.worxbend.codeberg4s.repositories.actions.SecretValue
import com.worxbend.codeberg4s.repositories.actions.UpdateVariable
import com.worxbend.codeberg4s.repositories.actions.VariableName

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend

import scala.concurrent.Future

/** [[UserActionApi]] over a `BackendStub`; see [[AccountApiSuite]] for the harness and for the evidence note.
  *
  * The point of most of these tests is that the account surface reaches `/user/actions/...` and not
  * `/repos/{owner}/{repo}/actions/...` while reading the very same models — so the reuse is checked rather than
  * assumed. The retry tests are the other half: this group makes a decision per endpoint, and each of those decisions
  * is observable by counting requests.
  */
final class UserActionApiSuite extends AccountApiSuite:

  private val Runner: RunnerId = RunnerId.of(37L)

  private val Secret: SecretName = orFail(SecretName.from("DEPLOY_KEY"))

  private val Variable: VariableName = orFail(VariableName.from("ENVIRONMENT"))

  private val ActionsRoot: String = s"$Endpoint/actions"

  // --- runners --------------------------------------------------------------

  test("the runner listing targets the account's runners, states its visibility and pages"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .listRunners(RunnerVisibility.AllVisible, window(2, 25))
        .map: _ =>
          assertEquals(pathOf(backend), s"$ActionsRoot/runners")
          assertEquals(queryOf(backend), List("visible" -> "true", "page" -> "2", "limit" -> "25"))

  test("visibility is always sent, so what a listing contains is a property of the request"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .listRunners(RunnerVisibility.OwnedOnly, PageParams.First)
        .map(_ => assertEquals(queryOf(backend).headOption, Some("visible" -> "false")))

  test("a single-runner read addresses the runner by id and reads the shared runner model"):
    val backend = RecordingBackend(responding(200, UserActionApiSuite.RunnerBody))

    onApi(backend): api =>
      api.runner(Runner).map: runner =>
        assertEquals(pathOf(backend), s"$ActionsRoot/runners/37")
        assertEquals(runner.id.value, "37")
        assertEquals(runner.name, Some("build-box-3"))

  test("registering a runner POSTs the register body and hands back a masked token"):
    val backend = RecordingBackend(responding(201, UserActionApiSuite.RegisteredBody))

    onApi(backend): api =>
      api.registerRunner(orFail(RegisterRunner.named("build-box-3")).ephemeral).map: registered =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$ActionsRoot/runners")
        assertEquals(bodyOf(backend), """{"name":"build-box-3","ephemeral":true}""")
        assertEquals(registered.token.reveal, "QWERTY123")
        assertEquals(registered.token.toString, RunnerRegistrationToken.Redacted)

  test("registering a runner is never retried, because a repeat registers a second one"):
    val backend = RecordingBackend(flakyThen(201, UserActionApiSuite.RegisteredBody))

    onApi(backend): api =>
      api.attempt
        .registerRunner(orFail(RegisterRunner.named("build-box-3")))
        .map(_ => assertEquals(attemptsOn(backend), 1, "the POST was retried"))

  test("deleting a runner is a DELETE by id that reads no body"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.deleteRunner(Runner).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$ActionsRoot/runners/37")

  test("deleting a runner is retried, because a row id is never reused"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.deleteRunner(Runner).map(_ => assertEquals(attemptsOn(backend), 2))

  test("the registration token is a GET that yields a credential which masks itself"):
    val backend = RecordingBackend(responding(200, """{"token":"QWERTY123"}"""))

    onApi(backend): api =>
      api.runnerRegistrationToken().map: token =>
        assertEquals(methodOf(backend), "GET")
        assertEquals(pathOf(backend), s"$ActionsRoot/runners/registration-token")
        assertEquals(token.reveal, "QWERTY123")
        assertEquals(token.toString, RunnerRegistrationToken.Redacted)

  test("the job search comma-joins its labels into the one parameter Forgejo declares"):
    val backend = RecordingBackend(responding(200, "[]"))
    val labels  = Vector(orFail(RunnerLabel.from("docker")), orFail(RunnerLabel.from("ubuntu-latest")))

    onApi(backend): api =>
      api.searchRunnerJobs(labels).map: _ =>
        assertEquals(pathOf(backend), s"$ActionsRoot/runners/jobs")
        assertEquals(queryOf(backend), List("labels" -> "docker,ubuntu-latest"))

  test("a job search with no labels sends no filter, which asks for every job"):
    val backend = RecordingBackend(responding(200, UserActionApiSuite.JobListBody))

    onApi(backend): api =>
      api.searchRunnerJobs(Vector.empty).map: jobs =>
        assertEquals(queryOf(backend), Nil)
        assertEquals(jobs.map(_.id.value), Vector(55L))

  // --- secrets --------------------------------------------------------------

  test("setting a secret PUTs the value under the account's own secret name"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.setSecret(Secret, orFail(SecretValue.from("hunter2"))).map: _ =>
        assertEquals(methodOf(backend), "PUT")
        assertEquals(pathOf(backend), s"$ActionsRoot/secrets/DEPLOY_KEY")
        assertEquals(bodyOf(backend), """{"data":"hunter2"}""")

  test("setting a secret is retried, because it is an assignment that ends in the requested state"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.setSecret(Secret, orFail(SecretValue.from("hunter2"))).map(_ => assertEquals(attemptsOn(backend), 2))

  test("deleting a secret is never retried, because the name is reusable and the act is destructive"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.attempt.deleteSecret(Secret).map(_ => assertEquals(attemptsOn(backend), 1, "the DELETE was retried"))

  test("deleting a secret addresses it by name"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.deleteSecret(Secret).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$ActionsRoot/secrets/DEPLOY_KEY")

  // --- variables ------------------------------------------------------------

  test("the variable listing targets the account's variables and pages"):
    val backend = RecordingBackend(responding(200, UserActionApiSuite.VariableListBody))

    onApi(backend): api =>
      api.listVariables(PageParams.First).map: page =>
        assertEquals(pathOf(backend), s"$ActionsRoot/variables")
        assertEquals(queryOf(backend), List("page" -> "1", "limit" -> "30"))
        assertEquals(page.items.map(_.value), Vector("staging"))

  test("a single-variable read addresses the variable by name"):
    val backend = RecordingBackend(responding(200, UserActionApiSuite.VariableBody))

    onApi(backend): api =>
      api.variable(Variable).map: variable =>
        assertEquals(pathOf(backend), s"$ActionsRoot/variables/ENVIRONMENT")
        assertEquals(variable.value, "staging")

  test("creating a variable POSTs its value and is never retried"):
    val backend = RecordingBackend(flakyThen(201, ""))

    onApi(backend): api =>
      api.attempt
        .createVariable(Variable, CreateVariable.of("staging"))
        .map(_ => assertEquals(attemptsOn(backend), 1, "the POST was retried"))

  test("creating a variable sends the value under the request spelling, not the response one"):
    val backend = RecordingBackend(responding(201, ""))

    onApi(backend): api =>
      api.createVariable(Variable, CreateVariable.of("staging")).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(bodyOf(backend), """{"value":"staging"}""")

  test("an update that only sets a value is retried, because it is an assignment"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.updateVariable(Variable, UpdateVariable.of("production")).map(_ => assertEquals(attemptsOn(backend), 2))

  test("an update that renames is never retried, because a repeat addresses a name that is gone"):
    val backend = RecordingBackend(flakyThen(204, ""))
    val command = UpdateVariable.of("production").movedTo(orFail(VariableName.from("STAGE")))

    onApi(backend): api =>
      api.attempt
        .updateVariable(Variable, command)
        .map(_ => assertEquals(attemptsOn(backend), 1, "the renaming PUT was retried"))

  test("the retry decision is a function of the command, and is stated where it can be read"):
    assertEquals(
      UserActionApi.updateVariableEligibility(UpdateVariable.of("production")).allows(methodPut),
      true,
    )
    assertEquals(
      UserActionApi
        .updateVariableEligibility(UpdateVariable.of("production").movedTo(orFail(VariableName.from("STAGE"))))
        .allows(methodPut),
      false,
    )

  test("deleting a variable is never retried, for the reason deleting a secret is not"):
    val backend = RecordingBackend(flakyThen(204, ""))

    onApi(backend): api =>
      api.attempt.deleteVariable(Variable).map(_ => assertEquals(attemptsOn(backend), 1, "the DELETE was retried"))

  // --- failures -------------------------------------------------------------

  test("a 401 fails the convenience rail with a CodebergException carrying the Api failure"):
    onApi(responding(401, AccountApiSuite.UnauthorizedBody)): api =>
      api.runner(Runner).failed.map: failure =>
        assertEquals(
          summary(unwrap(failure)),
          (UserActionApi.GetRunnerOperation, 401, Some("token is required")),
        )

  test("a 401 reaches the typed rail as a Left reporting the very same failure"):
    onApi(responding(401, AccountApiSuite.UnauthorizedBody)): api =>
      for
        raised <- api.runner(Runner).failed
        typed  <- api.attempt.runner(Runner)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a unit-returning write as well"):
    onApi(responding(403, AccountApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.deleteVariable(Variable).failed
        typed  <- api.attempt.deleteVariable(Variable)
      yield assertRailsAgree(raised, typed)

  test("a 200 whose payload does not fit the model becomes DecodingFailed, never an escaping codec exception"):
    onApi(responding(200, """{"name":"no id"}""")): api =>
      api.attempt.runner(Runner).map(outcome => assertEquals(decodingPathOf(outcome), "$.id"))

  test("a failure carries the operation id of the endpoint it came from, so an alert can name it"):
    onApi(responding(403, AccountApiSuite.ForbiddenBody)): api =>
      api.attempt
        .listVariables(PageParams.First)
        .map(outcome => assertEquals(operationOf(outcome), UserActionApi.ListVariablesOperation))

  /** The method the per-command retry decision is asked about; a `PUT` is unsafe, so only `AlwaysRetry` permits it. */
  private def methodPut: HttpMethod = HttpMethod.Put

  private def unwrap(failure: Throwable): CodebergError =
    failure match
      case CodebergException(error) => error
      case other                    => fail(s"expected a CodebergException, got $other")

  private def onApi[A](backend: Backend[Future])(use: UserActionApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(UserActionApi(pipeline)))

/** The response bodies this suite stubs, all hand-written from `spec/swagger.v1.json`. */
object UserActionApiSuite:

  private val RunnerBody: String =
    """{"id": 37, "uuid": "abc", "name": "build-box-3", "status": "idle", "labels": ["docker"], "ephemeral": false}"""

  private val RegisteredBody: String = """{"id": 37, "uuid": "abc", "token": "QWERTY123"}"""

  private val JobListBody: String =
    """[{"id": 55, "run_id": 4711, "name": "build", "status": "waiting", "needs": null, "runs_on": null}]"""

  private val VariableBody: String = """{"name": "ENVIRONMENT", "data": "staging", "owner_id": 31, "repo_id": 0}"""

  private val VariableListBody: String = s"[$VariableBody]"
