package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{Json, WireModel}
import com.worxbend.codeberg4s.repositories.actions.ActionRunJob
import com.worxbend.codeberg4s.repositories.actions.ActionStatus
import com.worxbend.codeberg4s.repositories.actions.ActionTask
import com.worxbend.codeberg4s.repositories.actions.DispatchedWorkflowRun

import munit.FunSuite

import java.time.Instant

/** Decoding a job, a task, the envelope the task and run listings arrive in, and a dispatch acknowledgement.
  *
  * '''Payloads written by hand from `spec/swagger.v1.json`, not captured'''; see [[ActionArtifactDto]].
  */
final class ActionJobTaskDtoSuite extends FunSuite:

  test("a full job decodes field for field"):
    val dto = decodeJob(ActionJobTaskDtoSuite.JobBody)

    assertEquals(dto.id, Some(55L))
    assertEquals(dto.runId, Some(4711L))
    assertEquals(dto.name, Some("build"))
    assertEquals(dto.status, Some("running"))
    assertEquals(dto.needs, Vector("lint"))
    assertEquals(dto.runsOn, Vector("ubuntu-latest"))

  test("a full job converts, attempt and status included"):
    val decoded = job(ActionJobTaskDtoSuite.JobBody)

    assertEquals(decoded.id.value, 55L)
    assertEquals(decoded.runId.map(_.value), Some(4711L))
    assertEquals(decoded.status, Some(ActionStatus.Running))
    assertEquals(decoded.attempt.map(_.value), Some(2L))
    assertEquals(decoded.handle, Some("55-2"))

  test("an attempt of zero is dropped, because the logs endpoint would reject it"):
    assertEquals(job("""{"id":55,"attempt":0}""").attempt, None)

  test("a null needs array becomes an empty vector rather than aborting the read"):
    assertEquals(decodeJob("""{"id":55,"needs":null,"runs_on":null}""").needs, Vector.empty[String])

  test("a job without an id cannot be converted"):
    assertEquals(jobFailure("""{"name":"build"}"""), Some("$.id"))

  test("JSON null and an absent key decode identically for a job"):
    assertEquals(decodeJob(ActionJobTaskDtoSuite.NullJobBody), decodeJob("""{"id":55}"""))

  test("a bad element of a job array reports its own position"):
    val dtos = Json.decode[Vector[ActionRunJobDto]]("""[{"id":1},{"name":"no id"}]""") match
      case Right(decoded) => decoded
      case Left(failure)  => fail(s"the array did not decode: ${failure.message}")

    assertEquals(WireModel.all(JsonPath.Root, dtos).swap.toOption.map(_.path.render), Some("$[1].id"))

  test("a full task decodes and converts"):
    val decoded = task(ActionJobTaskDtoSuite.TaskBody)

    assertEquals(decoded.id.value, 903L)
    assertEquals(decoded.status, Some(ActionStatus.Success))
    assertEquals(decoded.workflowId.map(_.value), Some("build.yml"))
    assertEquals(decoded.headBranch, Some("main"))
    assertEquals(decoded.headSha.map(_.value), Some("5f7e2e5c003c066a865ea483e42809fa87d85eae"))
    assertEquals(decoded.runNumber, Some(42L))
    assertEquals(decoded.runStartedAt, Some(Instant.parse("2026-07-30T19:14:20Z")))

  test("a task whose head_sha is not an object id keeps the task and drops the sha"):
    assertEquals(task("""{"id":903,"head_sha":"HEAD"}""").headSha, None)

  test("a task without an id cannot be converted"):
    assertEquals(taskFailure("""{"name":"build"}"""), Some("$.id"))

  test("the listing envelope unwraps its entries from workflow_runs"):
    val envelope = decodeRunEnvelope("""{"total_count":2,"workflow_runs":[{"id":1},{"id":2}]}""")

    assertEquals(envelope.totalCount, Some(2L))
    assertEquals(envelope.entries.flatMap(_.id), Vector(1L, 2L))

  test("the task listing uses the same workflow_runs key, despite carrying tasks"):
    val envelope = Json.decode[WorkflowRunsEnvelopeDto[ActionTaskDto]](
      """{"total_count":1,"workflow_runs":[{"id":903}]}"""
    ) match
      case Right(value)  => value
      case Left(failure) => fail(s"the envelope did not decode: ${failure.message}")

    assertEquals(envelope.entries.flatMap(_.id), Vector(903L))

  test("an envelope with no entries key is an empty page rather than a failure"):
    assertEquals(decodeRunEnvelope("""{"total_count":0}""").entries, Vector.empty[ActionRunDto])

  test("an envelope whose entries key is null is an empty page too"):
    assertEquals(decodeRunEnvelope("""{"workflow_runs":null}""").entries, Vector.empty[ActionRunDto])

  test("a bad entry of an envelope reports its position under workflow_runs"):
    val envelope = decodeRunEnvelope("""{"workflow_runs":[{"id":1},{"title":"no id"}]}""")
    val at       = JsonPath.Root.field(WorkflowRunsEnvelopeDto.EntriesKey)

    assertEquals(
      WireModel.all(at, envelope.entries).swap.toOption.map(_.path.render),
      Some("$.workflow_runs[1].id"),
    )

  test("a dispatch acknowledgement converts even when it names no run"):
    assertEquals(dispatched("""{}"""), DispatchedWorkflowRun(None, None, Vector.empty))

  test("a dispatch acknowledgement carries the run it started"):
    val decoded = dispatched("""{"id":4711,"run_number":42,"jobs":["build","test"]}""")

    assertEquals(decoded.id.map(_.value), Some(4711L))
    assertEquals(decoded.runNumber, Some(42L))
    assertEquals(decoded.jobs, Vector("build", "test"))

  test("a dispatch acknowledgement whose id is not a positive identifier still succeeds"):
    assertEquals(dispatched("""{"id":0}""").id, None)

  private def decodeJob(body: String): ActionRunJobDto =
    Json.decode[ActionRunJobDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

  private def job(body: String): ActionRunJob =
    decodeJob(body).toDomain match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

  private def jobFailure(body: String): Option[String] =
    decodeJob(body).toDomain.swap.toOption.map(_.path.render)

  private def task(body: String): ActionTask =
    decodeTask(body).toDomain match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not convert: ${failure.path.render} ${failure.message}")

  private def taskFailure(body: String): Option[String] =
    decodeTask(body).toDomain.swap.toOption.map(_.path.render)

  private def decodeTask(body: String): ActionTaskDto =
    Json.decode[ActionTaskDto](body) match
      case Right(dto)    => dto
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

  private def decodeRunEnvelope(body: String): WorkflowRunsEnvelopeDto[ActionRunDto] =
    Json.decode[WorkflowRunsEnvelopeDto[ActionRunDto]](body) match
      case Right(value)  => value
      case Left(failure) => fail(s"the envelope did not decode: ${failure.path.render} ${failure.message}")

  private def dispatched(body: String): DispatchedWorkflowRun =
    Json.decode[DispatchedWorkflowRunDto](body).flatMap(_.toDomain) match
      case Right(value)  => value
      case Left(failure) => fail(s"the payload did not decode: ${failure.path.render} ${failure.message}")

/** The payloads this suite decodes, shaped to the spec's job and task definitions. */
object ActionJobTaskDtoSuite:

  private val JobBody: String =
    """{
      |  "id": 55,
      |  "run_id": 4711,
      |  "name": "build",
      |  "status": "running",
      |  "needs": ["lint"],
      |  "runs_on": ["ubuntu-latest"],
      |  "attempt": 2,
      |  "task_id": 903,
      |  "handle": "55-2",
      |  "owner_id": 0,
      |  "repo_id": 12
      |}""".stripMargin

  private val NullJobBody: String =
    """{
      |  "id": 55,
      |  "run_id": null,
      |  "name": null,
      |  "status": null,
      |  "needs": null,
      |  "runs_on": null,
      |  "attempt": null,
      |  "task_id": null,
      |  "handle": null,
      |  "owner_id": null,
      |  "repo_id": null
      |}""".stripMargin

  private val TaskBody: String =
    """{
      |  "id": 903,
      |  "name": "build",
      |  "status": "success",
      |  "workflow_id": "build.yml",
      |  "head_branch": "main",
      |  "head_sha": "5f7e2e5c003c066a865ea483e42809fa87d85eae",
      |  "event": "push",
      |  "run_number": 42,
      |  "url": "https://forge.example/o/r/actions/runs/42",
      |  "display_title": "release the hounds",
      |  "run_started_at": "2026-07-30T21:14:20+02:00",
      |  "created_at": "2026-07-30T21:14:15+02:00",
      |  "updated_at": "2026-07-30T21:15:45+02:00"
      |}""".stripMargin
