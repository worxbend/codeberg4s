package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.ClientSuiteHarness
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams

import sttp.client4.Backend
import sttp.client4.testing.RecordingBackend
import sttp.client4.testing.ResponseStub
import sttp.model.Header
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.Future

/** [[RepositoryActionApi]] over a `BackendStub`: nothing in this suite opens a socket.
  *
  * The subject is the wiring — which URI is dialled, which query parameters and which body are sent, which calls may be
  * repeated, and what each rail does with a failure. Decoding itself is asserted in `modules/codec`, so the payloads
  * here are small hand-written bodies chosen to exercise a seam.
  *
  * '''No golden fixture backs this group.''' Every payload below was written from `spec/swagger.v1.json`; see the class
  * note on [[RepositoryActionApi]].
  */
final class RepositoryActionApiSuite extends FunSuite with ClientSuiteHarness:

  /** The prefix every asserted path starts with: the actions surface of the repository every path in this suite hangs
    * off.
    */
  private val Endpoint: String = s"$Root/repos/forgejo/forgejo/actions"

  private val Handle: Owner = orFail(Owner.from("forgejo"))

  private val Name: RepoName = orFail(RepoName.from("forgejo"))

  private val Run: RunId = orFail(RunId.from(4711L))

  private val Job: JobId = orFail(JobId.from(55L))

  private val Artifact: ArtifactId = orFail(ArtifactId.from(881L))

  private val Runner: RunnerId = RunnerId.of(37L)

  private val Secret: SecretName = orFail(SecretName.from("DEPLOY_KEY"))

  private val Variable: VariableName = orFail(VariableName.from("ENVIRONMENT"))

  private val Workflow: WorkflowFileName = orFail(WorkflowFileName.from("build.yml"))

  // --- artifacts ------------------------------------------------------------

  test("actions.artifacts.list targets the repository's artifacts and pages them"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .listArtifacts(Handle, Name, ArtifactQuery.Empty.named("coverage"), window(2, 25))
        .map: _ =>
          assertEquals(pathOf(backend), s"$Endpoint/artifacts")
          assertEquals(queryOf(backend), List("name" -> "coverage", "page" -> "2", "limit" -> "25"))

  test("actions.artifacts.list ends where rel=next says it ends, not where a short page suggests"):
    onApi(responding(200, RepositoryActionApiSuite.ArtifactListBody, RepositoryActionApiSuite.PagedHeaders)): api =>
      api.listArtifacts(Handle, Name, ArtifactQuery.Empty, window(1, 30)).map: page =>
        assertEquals(page.size, 1)
        assertEquals(page.totalCount, Some(97))
        assertEquals(page.nextPage.map(_.value), Some(2))
        assertEquals(page.isLast, false)

  test("a page past the end is an empty page, not a failure"):
    onApi(responding(200, "[]")): api =>
      api.listArtifacts(Handle, Name, ArtifactQuery.Empty, PageParams.First).map: page =>
        assertEquals(page.items, Vector.empty[ActionArtifact])
        assertEquals(page.isLast, true)

  test("a single-artifact read addresses the artifact by id"):
    val backend = RecordingBackend(responding(200, RepositoryActionApiSuite.ArtifactBody))

    onApi(backend): api =>
      api.artifact(Handle, Name, Artifact).map: artifact =>
        assertEquals(pathOf(backend), s"$Endpoint/artifacts/881")
        assertEquals(artifact.id.value, 881L)
        assertEquals(artifact.name, Some("coverage"))

  test("actions.artifacts.delete is a DELETE that reads no body"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.deleteArtifact(Handle, Name, Artifact).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Endpoint/artifacts/881")

  test("a delete by id is retried, because deleting a named resource twice leaves the same state"):
    val backend =
      RecordingBackend(cycling(ResponseStub.adjust("", StatusCode(503)), ResponseStub.adjust("", StatusCode(204))))

    onApi(backend): api =>
      api.deleteArtifact(Handle, Name, Artifact).map(_ => assertEquals(backend.allInteractions.size, 2))

  // --- runs -----------------------------------------------------------------

  test("actions.runs.list unwraps the workflow_runs envelope, which no other listing here uses"):
    val backend = RecordingBackend(responding(200, RepositoryActionApiSuite.RunListBody))

    onApi(backend): api =>
      api.listRuns(Handle, Name, ActionRunQuery.Empty, PageParams.First).map: page =>
        assertEquals(pathOf(backend), s"$Endpoint/runs")
        assertEquals(page.items.map(_.id.value), Vector(4711L))
        assertEquals(page.items.flatMap(_.status), Vector(ActionStatus.Failure))

  test("actions.runs.list sends array filters as repeated keys and paging last"):
    val backend = RecordingBackend(responding(200, """{"workflow_runs":[]}"""))
    val query   = ActionRunQuery.Empty.triggeredBy("push").withStatus(ActionStatus.Failure).onRef("refs/heads/main")

    onApi(backend): api =>
      api
        .listRuns(Handle, Name, query, PageParams.First)
        .map: _ =>
          assertEquals(
            queryOf(backend),
            List(
              "event"  -> "push",
              "status" -> "failure",
              "ref"    -> "refs/heads/main",
              "page"   -> "1",
              "limit"  -> "30",
            ),
          )

  test("a bad entry of the run envelope reports its position under workflow_runs"):
    onApi(responding(200, """{"workflow_runs":[{"id":1},{"title":"no id"}]}""")): api =>
      api.attempt.listRuns(Handle, Name, ActionRunQuery.Empty, PageParams.First).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) =>
          assertEquals(path.render, "$.workflow_runs[1].id")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a single-run read addresses the run by id"):
    val backend = RecordingBackend(responding(200, RepositoryActionApiSuite.RunBody))

    onApi(backend): api =>
      api.run(Handle, Name, Run).map: run =>
        assertEquals(pathOf(backend), s"$Endpoint/runs/4711")
        assertEquals(run.id.value, 4711L)

  test("actions.runs.cancel POSTs to the run's cancel endpoint with no body"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.cancelRun(Handle, Name, Run).map: _ =>
        assertEquals(methodOf(backend), "POST")
        assertEquals(pathOf(backend), s"$Endpoint/runs/4711/cancel")

  test("actions.runs.cancel is never retried, because this library repeats no POST"):
    val backend =
      RecordingBackend(cycling(ResponseStub.adjust("", StatusCode(503)), ResponseStub.adjust("", StatusCode(204))))

    onApi(backend): api =>
      api.attempt
        .cancelRun(Handle, Name, Run)
        .map: outcome =>
          assert(outcome.isLeft, s"a 503 on cancel must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "the POST was retried")

  test("actions.runs.artifacts.list scopes the artifact listing to one run"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .listRunArtifacts(Handle, Name, Run, ArtifactQuery.Empty, PageParams.First)
        .map(_ => assertEquals(pathOf(backend), s"$Endpoint/runs/4711/artifacts"))

  test("actions.runs.jobs.list is a bare array and takes no paging parameters"):
    val backend = RecordingBackend(responding(200, RepositoryActionApiSuite.JobListBody))

    onApi(backend): api =>
      api.listRunJobs(Handle, Name, Run).map: jobs =>
        assertEquals(pathOf(backend), s"$Endpoint/runs/4711/jobs")
        assertEquals(queryOf(backend), Nil)
        assertEquals(jobs.map(_.id.value), Vector(55L))

  // --- jobs -----------------------------------------------------------------

  test("actions.jobs.logs returns the body verbatim, without parsing it as JSON"):
    val backend = RecordingBackend(responding(200, RepositoryActionApiSuite.LogBody))

    onApi(backend): api =>
      api.jobLogs(Handle, Name, Job, None).map: log =>
        assertEquals(pathOf(backend), s"$Endpoint/jobs/55/logs")
        assertEquals(queryOf(backend), Nil)
        assertEquals(log, RepositoryActionApiSuite.LogBody)

  test("a 206 partial log is a success, because every 2xx is"):
    onApi(responding(206, "partial")): api =>
      api.jobLogs(Handle, Name, Job, None).map(log => assertEquals(log, "partial"))

  test("a named attempt is sent as a query parameter"):
    val backend = RecordingBackend(responding(200, "x"))

    onApi(backend): api =>
      api
        .jobLogs(Handle, Name, Job, Some(orFail(JobAttempt.from(3L))))
        .map(_ => assertEquals(queryOf(backend), List("attempt" -> "3")))

  // --- runners --------------------------------------------------------------

  test("actions.runners.list always states the visibility it wants"):
    val backend = RecordingBackend(responding(200, "[]"))

    onApi(backend): api =>
      api
        .listRunners(Handle, Name, RunnerVisibility.AllVisible, PageParams.First)
        .map: _ =>
          assertEquals(pathOf(backend), s"$Endpoint/runners")
          assertEquals(queryOf(backend), List("visible" -> "true", "page" -> "1", "limit" -> "30"))

  test("a single-runner read addresses the runner by its string id"):
    val backend = RecordingBackend(responding(200, RepositoryActionApiSuite.RunnerBody))

    onApi(backend): api =>
      api.runner(Handle, Name, Runner).map: runner =>
        assertEquals(pathOf(backend), s"$Endpoint/runners/37")
        assertEquals(runner.status, Some(RunnerStatus.Idle))

  test("actions.runners.register POSTs the rendered options and returns a masked token"):
    val backend = RecordingBackend(responding(201, RepositoryActionApiSuite.RegisteredBody))

    onApi(backend): api =>
      api
        .registerRunner(Handle, Name, orFail(RegisterRunner.named("build-box-3")).ephemeral)
        .map: registered =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), s"$Endpoint/runners")
          assertEquals(bodyOf(backend), """{"name":"build-box-3","ephemeral":true}""")
          assertEquals(registered.token.reveal, "QWERTY123")
          assertEquals(registered.token.toString, RunnerRegistrationToken.Redacted)

  test("actions.runners.register is never retried, because a repeat registers a second runner"):
    val backend = RecordingBackend(
      cycling(
        ResponseStub.adjust("", StatusCode(503)),
        ResponseStub.adjust(RepositoryActionApiSuite.RegisteredBody, StatusCode(201)),
      )
    )

    onApi(backend): api =>
      api.attempt
        .registerRunner(Handle, Name, orFail(RegisterRunner.named("build-box-3")))
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the POST was retried"))

  test("a registration response that does not decode reports no part of the credential"):
    val truncated = RepositoryActionApiSuite.RegisteredBody.dropRight(1)

    onApi(RecordingBackend(responding(201, truncated))): api =>
      api.attempt.registerRunner(Handle, Name, orFail(RegisterRunner.named("build-box-3"))).map:
        case Left(error @ CodebergError.DecodingFailed(_, snippet, _, _)) =>
          assertEquals(snippet, ApiPipeline.redactedSnippet(truncated.length))
          assert(!error.describe.contains("QWERTY123"), s"the body excerpt carried the token: ${error.describe}")
        case other                                                        =>
          fail(s"expected a decoding failure, got $other")

  test("actions.runners.delete is a DELETE on the runner"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.deleteRunner(Handle, Name, Runner).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Endpoint/runners/37")

  test("actions.runners.registrationToken reads the token endpoint and masks what it returns"):
    val backend = RecordingBackend(responding(200, """{"token":"QWERTY123"}"""))

    onApi(backend): api =>
      api.runnerRegistrationToken(Handle, Name).map: token =>
        assertEquals(pathOf(backend), s"$Endpoint/runners/registration-token")
        assertEquals(token.reveal, "QWERTY123")
        assertEquals(s"$token", RunnerRegistrationToken.Redacted)

  test("actions.runners.jobs.search comma-joins its labels into one parameter"):
    val backend = RecordingBackend(responding(200, "[]"))
    val labels  = Vector(orFail(RunnerLabel.from("ubuntu-latest")), orFail(RunnerLabel.from("docker")))

    onApi(backend): api =>
      api.searchRunnerJobs(Handle, Name, labels).map: _ =>
        assertEquals(pathOf(backend), s"$Endpoint/runners/jobs")
        assertEquals(queryOf(backend), List("labels" -> "ubuntu-latest,docker"))

  // --- tasks ----------------------------------------------------------------

  test("actions.tasks.list unwraps the same envelope the run listing uses"):
    val backend = RecordingBackend(responding(200, RepositoryActionApiSuite.TaskListBody))

    onApi(backend): api =>
      api
        .listTasks(Handle, Name, ActionTaskQuery.Empty.withStatus(ActionStatus.Success), PageParams.First)
        .map: page =>
          assertEquals(pathOf(backend), s"$Endpoint/tasks")
          assertEquals(queryOf(backend), List("status" -> "success", "page" -> "1", "limit" -> "30"))
          assertEquals(page.items.map(_.id.value), Vector(903L))

  // --- secrets --------------------------------------------------------------

  test("actions.secrets.list returns names and timestamps, and has nowhere to put a value"):
    val backend = RecordingBackend(responding(200, RepositoryActionApiSuite.SecretListBody))

    onApi(backend): api =>
      api.listSecrets(Handle, Name, PageParams.First).map: page =>
        assertEquals(pathOf(backend), s"$Endpoint/secrets")
        assertEquals(page.items.map(_.name.value), Vector("DEPLOY_KEY"))

  test("actions.secrets.set PUTs the material under the key the spec names"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api
        .setSecret(Handle, Name, Secret, orFail(SecretValue.from("hunter2")))
        .map: _ =>
          assertEquals(methodOf(backend), "PUT")
          assertEquals(pathOf(backend), s"$Endpoint/secrets/DEPLOY_KEY")
          assertEquals(bodyOf(backend), """{"data":"hunter2"}""")

  test("actions.secrets.set is retried, because setting a named secret twice leaves the same state"):
    val backend =
      RecordingBackend(cycling(ResponseStub.adjust("", StatusCode(503)), ResponseStub.adjust("", StatusCode(201))))

    onApi(backend): api =>
      api
        .setSecret(Handle, Name, Secret, orFail(SecretValue.from("hunter2")))
        .map(_ => assertEquals(backend.allInteractions.size, 2, "the PUT was not retried"))

  test("a failed secret write never carries the material into the error a caller would log"):
    onApi(responding(403, RepositoryActionApiSuite.ForbiddenBody)): api =>
      api.attempt
        .setSecret(Handle, Name, Secret, orFail(SecretValue.from("hunter2")))
        .map:
          case Left(error) =>
            assert(!error.describe.contains("hunter2"), s"the secret reached the error: ${error.describe}")
            assert(!error.toString.contains("hunter2"), "the secret reached the error's toString")
          case Right(_)    => fail("expected a 403 to fail")

  test("actions.secrets.delete addresses the secret by name"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.deleteSecret(Handle, Name, Secret).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Endpoint/secrets/DEPLOY_KEY")

  // --- variables ------------------------------------------------------------

  test("actions.variables.list returns values, unlike the secret listing"):
    val backend = RecordingBackend(responding(200, RepositoryActionApiSuite.VariableListBody))

    onApi(backend): api =>
      api.listVariables(Handle, Name, PageParams.First).map: page =>
        assertEquals(pathOf(backend), s"$Endpoint/variables")
        assertEquals(page.items.map(_.value), Vector("staging"))

  test("a single-variable read addresses the variable by name"):
    val backend = RecordingBackend(responding(200, RepositoryActionApiSuite.VariableBody))

    onApi(backend): api =>
      api.variable(Handle, Name, Variable).map: variable =>
        assertEquals(pathOf(backend), s"$Endpoint/variables/ENVIRONMENT")
        assertEquals(variable.value, "staging")

  test("actions.variables.create POSTs the value under the key the request model names"):
    val backend = RecordingBackend(responding(201, ""))

    onApi(backend): api =>
      api
        .createVariable(Handle, Name, Variable, CreateVariable.of("staging"))
        .map: _ =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), s"$Endpoint/variables/ENVIRONMENT")
          assertEquals(bodyOf(backend), """{"value":"staging"}""")

  test("actions.variables.update PUTs the value, and is retried when it is not a rename"):
    val backend =
      RecordingBackend(cycling(ResponseStub.adjust("", StatusCode(503)), ResponseStub.adjust("", StatusCode(204))))

    onApi(backend): api =>
      api
        .updateVariable(Handle, Name, Variable, UpdateVariable.of("production"))
        .map: _ =>
          assertEquals(methodOf(backend), "PUT")
          assertEquals(bodyOf(backend), """{"value":"production"}""")
          assertEquals(backend.allInteractions.size, 2, "a non-renaming update was not retried")

  test("an update that renames is not retried, because the repeat would address a name that has moved"):
    val backend =
      RecordingBackend(cycling(ResponseStub.adjust("", StatusCode(503)), ResponseStub.adjust("", StatusCode(204))))
    val command = UpdateVariable.of("production").movedTo(orFail(VariableName.from("STAGE")))

    onApi(backend): api =>
      api.attempt
        .updateVariable(Handle, Name, Variable, command)
        .map: outcome =>
          assert(outcome.isLeft, s"a renaming update must not be retried into a success, got $outcome")
          assertEquals(backend.allInteractions.size, 1, "a renaming update was retried")
          assertEquals(bodyOf(backend), """{"value":"production","name":"STAGE"}""")

  test("the two eligibilities are chosen from the command, not from the endpoint"):
    val plain   = RepositoryActionApi.updateVariableEligibility(UpdateVariable.of("x"))
    val renamed = RepositoryActionApi.updateVariableEligibility(UpdateVariable.of("x").movedTo(Variable))

    assertEquals(plain.allows(HttpMethod.Put), true)
    assertEquals(renamed.allows(HttpMethod.Put), false)

  test("actions.variables.delete addresses the variable by name"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api.deleteVariable(Handle, Name, Variable).map: _ =>
        assertEquals(methodOf(backend), "DELETE")
        assertEquals(pathOf(backend), s"$Endpoint/variables/ENVIRONMENT")

  // --- workflows ------------------------------------------------------------

  test("actions.workflows.dispatch POSTs the ref to the workflow's dispatches endpoint"):
    val backend = RecordingBackend(responding(204, ""))

    onApi(backend): api =>
      api
        .dispatchWorkflow(Handle, Name, Workflow, orFail(DispatchWorkflow.on("refs/heads/main")))
        .map: outcome =>
          assertEquals(methodOf(backend), "POST")
          assertEquals(pathOf(backend), s"$Endpoint/workflows/build.yml/dispatches")
          assertEquals(bodyOf(backend), """{"ref":"refs/heads/main"}""")
          assertEquals(outcome, None)

  test("a dispatch that asked for run info gets a described run back"):
    val command = orFail(DispatchWorkflow.on("main")).withInput("environment", "staging").returningRunInfo

    onApi(responding(201, RepositoryActionApiSuite.DispatchBody)): api =>
      api.dispatchWorkflow(Handle, Name, Workflow, command).map: outcome =>
        assertEquals(outcome.flatMap(_.id).map(_.value), Some(4711L))
        assertEquals(outcome.map(_.jobs), Some(Vector("build")))

  test("actions.workflows.dispatch is never retried, because a repeat starts a second run"):
    val backend =
      RecordingBackend(cycling(ResponseStub.adjust("", StatusCode(503)), ResponseStub.adjust("", StatusCode(204))))

    onApi(backend): api =>
      api.attempt
        .dispatchWorkflow(Handle, Name, Workflow, orFail(DispatchWorkflow.on("main")))
        .map(_ => assertEquals(backend.allInteractions.size, 1, "the POST was retried"))

  // --- failures -------------------------------------------------------------

  test("a 404 fails the convenience rail with a CodebergException carrying the Api failure"):
    onApi(responding(404, RepositoryActionApiSuite.NotFoundBody)): api =>
      api.run(Handle, Name, Run).failed.map:
        case CodebergException(error) =>
          assertEquals(summary(error), (RepositoryActionApi.GetRunOperation, 404, Some("GetActionRun")))
        case other                    => fail(s"expected a CodebergException, got $other")

  test("a 404 reaches the typed rail as a Left reporting the very same failure"):
    onApi(responding(404, RepositoryActionApiSuite.NotFoundBody)): api =>
      for
        raised <- api.run(Handle, Name, Run).failed
        typed  <- api.attempt.run(Handle, Name, Run)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a secret listing failure as well, so the choice of rail is only a choice of style"):
    onApi(responding(403, RepositoryActionApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.listSecrets(Handle, Name, PageParams.First).failed
        typed  <- api.attempt.listSecrets(Handle, Name, PageParams.First)
      yield assertRailsAgree(raised, typed)

  test("both rails agree on a unit-returning write as well"):
    onApi(responding(403, RepositoryActionApiSuite.ForbiddenBody)): api =>
      for
        raised <- api.deleteVariable(Handle, Name, Variable).failed
        typed  <- api.attempt.deleteVariable(Handle, Name, Variable)
      yield assertRailsAgree(raised, typed)

  test("a 400 is an Api failure too — Forgejo uses it for validation alongside 422"):
    onApi(responding(400, RepositoryActionApiSuite.ValidationBody)): api =>
      api.attempt.listRuns(Handle, Name, ActionRunQuery.Empty, PageParams.First).map:
        case Left(CodebergError.Api(_, status, _, _)) => assertEquals(status, 400)
        case other                                    => fail(s"expected an Api failure, got $other")

  test("a 200 whose payload does not fit the model becomes DecodingFailed, never an escaping codec exception"):
    onApi(responding(200, """{"title":"no id"}""")): api =>
      api.attempt.run(Handle, Name, Run).map:
        case Left(CodebergError.DecodingFailed(_, _, path, _)) => assertEquals(path.render, "$.id")
        case other                                             => fail(s"expected a decoding failure, got $other")

  test("a failure carries the operation id of the endpoint it came from, so an alert can name it"):
    onApi(responding(403, RepositoryActionApiSuite.ForbiddenBody)): api =>
      api.attempt.dispatchWorkflow(Handle, Name, Workflow, orFail(DispatchWorkflow.on("main"))).map: outcome =>
        assertEquals(operationOf(outcome), RepositoryActionApi.DispatchWorkflowOperation)

  // --- harness --------------------------------------------------------------

  /** Builds the API under test on a pipeline over `backend`, releasing the timer whatever happens. */
  private def onApi[A](backend: Backend[Future])(use: RepositoryActionApi => Future[A]): Future[A] =
    onPipeline(backend)(pipeline => use(RepositoryActionApi(pipeline)))

/** The response bodies this suite stubs, kept out of the test bodies so each test reads as one behaviour.
  *
  * All of them are hand-written from `spec/swagger.v1.json`; no Actions endpoint has a golden capture.
  */
object RepositoryActionApiSuite:

  private val ArtifactBody: String =
    """{"id": 881, "name": "coverage", "size_in_bytes": 2048, "expired": false, "run_id": 4711}"""

  private val ArtifactListBody: String = s"[$ArtifactBody]"

  private val RunBody: String =
    """{"id": 4711, "index_in_repo": 42, "status": "failure", "workflow_id": "build.yml", "event": "push"}"""

  private val RunListBody: String = s"""{"total_count": 1, "workflow_runs": [$RunBody]}"""

  private val JobListBody: String =
    """[{"id": 55, "run_id": 4711, "name": "build", "status": "running", "needs": null, "runs_on": null}]"""

  private val TaskListBody: String =
    """{"total_count": 1, "workflow_runs": [{"id": 903, "status": "success", "workflow_id": "build.yml"}]}"""

  private val RunnerBody: String =
    """{"id": 37, "uuid": "abc", "name": "build-box-3", "status": "idle", "labels": ["docker"], "ephemeral": false}"""

  private val RegisteredBody: String = """{"id": 37, "uuid": "abc", "token": "QWERTY123"}"""

  private val SecretListBody: String = """[{"name": "DEPLOY_KEY", "created_at": "2026-07-30T21:14:15+02:00"}]"""

  private val VariableBody: String = """{"name": "ENVIRONMENT", "data": "staging", "owner_id": 0, "repo_id": 12}"""

  private val VariableListBody: String = s"[$VariableBody]"

  private val DispatchBody: String = """{"id": 4711, "run_number": 42, "jobs": ["build"]}"""

  private val LogBody: String = "2026-07-30T21:14:20Z build | hello\n2026-07-30T21:14:21Z build | done\n"

  /** The paging headers `docs/HAZARDS.md` §5 captured verbatim, with the host and path swapped for this endpoint's. */
  private val PagedHeaders: List[Header] =
    List(
      Header("X-Total-Count", "97"),
      Header(
        "Link",
        "<https://forge.example/api/v1/repos/forgejo/forgejo/actions/artifacts?limit=30&page=2>; rel=\"next\"," +
          "<https://forge.example/api/v1/repos/forgejo/forgejo/actions/artifacts?limit=30&page=4>; rel=\"last\"",
      ),
    )

  private val NotFoundBody: String =
    """{"message":"GetActionRun","url":"https://codeberg.org/api/swagger","errors":["run does not exist"]}"""

  private val ForbiddenBody: String =
    """{"message":"token does not have at least one of required scope(s): [write:repository]"}"""

  private val ValidationBody: String =
    """{"message":"ListActionRuns","url":"https://codeberg.org/api/swagger","errors":["invalid status"]}"""
