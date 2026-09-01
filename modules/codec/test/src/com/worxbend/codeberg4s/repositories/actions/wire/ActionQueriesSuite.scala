package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.repositories.actions.ActionRunQuery
import com.worxbend.codeberg4s.repositories.actions.ActionStatus
import com.worxbend.codeberg4s.repositories.actions.ActionTaskQuery
import com.worxbend.codeberg4s.repositories.actions.ArtifactQuery
import com.worxbend.codeberg4s.repositories.actions.JobAttempt
import com.worxbend.codeberg4s.repositories.actions.RunnerLabel
import com.worxbend.codeberg4s.repositories.actions.RunnerVisibility
import com.worxbend.codeberg4s.repositories.actions.WorkflowFileName

import munit.FunSuite

/** The query parameters this group sends.
  *
  * Order is asserted, not just contents: a stable order makes a recorded request comparable between runs, which is what
  * lets the client suite assert on whole query lists.
  */
final class ActionQueriesSuite extends FunSuite:

  test("an empty run query sends nothing at all"):
    assertEquals(ActionQueries.runs(ActionRunQuery.Empty), Nil)

  test("array filters are repeated keys, not one comma-joined value"):
    val query = ActionRunQuery.Empty
      .triggeredBy("push")
      .triggeredBy("pull_request")
      .withStatus(ActionStatus.Failure)
      .withStatus(ActionStatus.Cancelled)

    assertEquals(
      ActionQueries.runs(query),
      List(
        "event"  -> "push",
        "event"  -> "pull_request",
        "status" -> "failure",
        "status" -> "cancelled",
      ),
    )

  test("the single-valued run filters render in the order the spec declares them"):
    val query = ActionRunQuery.Empty
      .numbered(42L)
      .atCommit(orFail(CommitSha.from("5F7E2E5C003C066A865EA483E42809FA87D85EAE")))
      .onRef("refs/heads/main")
      .ofWorkflow(orFail(WorkflowFileName.from("build.yml")))

    assertEquals(
      ActionQueries.runs(query),
      List(
        "run_number"  -> "42",
        "head_sha"    -> "5f7e2e5c003c066a865ea483e42809fa87d85eae",
        "ref"         -> "refs/heads/main",
        "workflow_id" -> "build.yml",
      ),
    )

  test("an empty task query sends nothing"):
    assertEquals(ActionQueries.tasks(ActionTaskQuery.Empty), Nil)

  test("task statuses are repeated keys too"):
    val query = ActionTaskQuery.Empty.withStatus(ActionStatus.Running).withStatus(ActionStatus.Waiting)

    assertEquals(ActionQueries.tasks(query), List("status" -> "running", "status" -> "waiting"))

  test("an empty artifact query sends nothing"):
    assertEquals(ActionQueries.artifacts(ArtifactQuery.Empty), Nil)

  test("an artifact name filter is sent verbatim"):
    assertEquals(ActionQueries.artifacts(ArtifactQuery.Empty.named("coverage")), List("name" -> "coverage"))

  test("the runner listing always states which visibility it wants"):
    assertEquals(ActionQueries.runners(RunnerVisibility.OwnedOnly), List("visible" -> "false"))
    assertEquals(ActionQueries.runners(RunnerVisibility.AllVisible), List("visible" -> "true"))

  test("job-search labels are comma-joined, because the spec declares one string"):
    val labels = Vector(orFail(RunnerLabel.from("ubuntu-latest")), orFail(RunnerLabel.from("docker")))

    assertEquals(ActionQueries.runnerJobs(labels), List("labels" -> "ubuntu-latest,docker"))

  test("no labels means no filter, which asks for every job"):
    assertEquals(ActionQueries.runnerJobs(Vector.empty), Nil)

  test("omitting the attempt is how the latest one is asked for"):
    assertEquals(ActionQueries.jobLogs(None), Nil)

  test("a named attempt is sent as a one-based number"):
    assertEquals(ActionQueries.jobLogs(Some(orFail(JobAttempt.from(3L)))), List("attempt" -> "3"))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
