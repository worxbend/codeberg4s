package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.CommitSha

import munit.FunSuite

/** The commands and queries a caller builds before any request exists.
  *
  * Everything here is pre-flight: a value these constructors reject never becomes a call, which is why
  * [[com.worxbend.codeberg4s.CodebergError.Validation]] is not part of
  * [[com.worxbend.codeberg4s.repositories.actions.RepositoryActionApi]]'s failure contract.
  */
final class ActionCommandSuite extends FunSuite:

  test("registering a runner needs a name, and a blank one is refused before the call"):
    assertEquals(RegisterRunner.named("  ").swap.toOption.map(_.field), Some("runnerName"))

  test("a runner name is trimmed"):
    assertEquals(orFail(RegisterRunner.named("  build-box-3 ")).name, "build-box-3")

  test("a fresh registration command sets nothing the instance has a default for"):
    val command = orFail(RegisterRunner.named("build-box-3"))

    assertEquals(command.description, None)
    assertEquals(command.isEphemeral, false)

  test("a registration command accumulates its optional fields"):
    val command = orFail(RegisterRunner.named("build-box-3")).describedAs("hetzner cx42").ephemeral

    assertEquals(command.description, Some("hetzner cx42"))
    assertEquals(command.isEphemeral, true)

  test("dispatching a workflow needs a ref, and a blank one is refused before the call"):
    assertEquals(DispatchWorkflow.on(" \n").swap.toOption.map(_.field), Some("ref"))

  test("a dispatch ref is trimmed and otherwise left alone, because it travels in a body"):
    assertEquals(orFail(DispatchWorkflow.on("  refs/heads/main ")).ref, "refs/heads/main")

  test("dispatch inputs keep the order the caller built them in"):
    val command = orFail(DispatchWorkflow.on("main")).withInput("environment", "staging").withInput("dry_run", "true")

    assertEquals(command.inputs, Vector("environment" -> "staging", "dry_run" -> "true"))

  test("a repeated dispatch input is kept twice rather than silently resolved"):
    val command = orFail(DispatchWorkflow.on("main")).withInput("env", "a").withInput("env", "b")

    assertEquals(command.inputs, Vector("env" -> "a", "env" -> "b"))

  test("run info is off unless the caller asks for it, matching the API's own default"):
    assertEquals(orFail(DispatchWorkflow.on("main")).returnRunInfo, false)
    assertEquals(orFail(DispatchWorkflow.on("main")).returningRunInfo.returnRunInfo, true)

  test("creating a variable accepts any value, the empty string included"):
    assertEquals(CreateVariable.of("").value, "")

  test("updating a variable does not rename it unless asked"):
    assertEquals(UpdateVariable.of("staging").renamedTo, None)

  test("an update that moves the variable carries the new name"):
    val moved = UpdateVariable.of("staging").movedTo(orFail(VariableName.from("ENVIRONMENT")))

    assertEquals(moved.renamedTo.map(_.value), Some("ENVIRONMENT"))
    assertEquals(moved.value, "staging")

  test("an empty run query asks for nothing in particular"):
    assertEquals(ActionRunQuery.Empty.events, Vector.empty[String])
    assertEquals(ActionRunQuery.Empty.statuses, Vector.empty[ActionStatus])
    assertEquals(ActionRunQuery.Empty.runNumber, None)
    assertEquals(ActionRunQuery.Empty.headSha, None)
    assertEquals(ActionRunQuery.Empty.ref, None)
    assertEquals(ActionRunQuery.Empty.workflowId, None)

  test("a run query accumulates repeated filters in the order they were added"):
    val query = ActionRunQuery.Empty
      .triggeredBy("push")
      .triggeredBy("pull_request")
      .withStatus(ActionStatus.Failure)
      .withStatus(ActionStatus.Cancelled)

    assertEquals(query.events, Vector("push", "pull_request"))
    assertEquals(query.statuses, Vector(ActionStatus.Failure, ActionStatus.Cancelled))

  test("a run query carries the single-valued filters it was given"):
    val query = ActionRunQuery.Empty
      .numbered(42L)
      .atCommit(orFail(CommitSha.from("5f7e2e5c003c066a865ea483e42809fa87d85eae")))
      .onRef("refs/heads/main")
      .ofWorkflow(orFail(WorkflowFileName.from("build.yml")))

    assertEquals(query.runNumber, Some(42L))
    assertEquals(query.headSha.map(_.value), Some("5f7e2e5c003c066a865ea483e42809fa87d85eae"))
    assertEquals(query.ref, Some("refs/heads/main"))
    assertEquals(query.workflowId.map(_.value), Some("build.yml"))

  test("an empty task query asks for every status"):
    assertEquals(ActionTaskQuery.Empty.statuses, Vector.empty[ActionStatus])

  test("a task query accumulates statuses"):
    assertEquals(
      ActionTaskQuery.Empty.withStatus(ActionStatus.Running).statuses,
      Vector(ActionStatus.Running),
    )

  test("an empty artifact query asks for every artifact"):
    assertEquals(ArtifactQuery.Empty.name, None)

  test("an artifact query matches one name exactly"):
    assertEquals(ArtifactQuery.Empty.named("coverage").name, Some("coverage"))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
