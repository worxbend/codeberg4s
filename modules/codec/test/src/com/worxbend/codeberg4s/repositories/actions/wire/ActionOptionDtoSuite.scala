package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.actions.CreateVariable
import com.worxbend.codeberg4s.repositories.actions.DispatchWorkflow
import com.worxbend.codeberg4s.repositories.actions.RegisterRunner
import com.worxbend.codeberg4s.repositories.actions.SecretValue
import com.worxbend.codeberg4s.repositories.actions.UpdateVariable
import com.worxbend.codeberg4s.repositories.actions.VariableName

import munit.FunSuite

/** The four request bodies this group renders.
  *
  * These are asserted as exact strings rather than as re-parsed objects, because the point of a renderer is the bytes:
  * a key that moved, an optional field that started being emitted, or an input order that stopped being stable is a
  * change in what the instance receives, and a round-trip through a parser would hide all three.
  */
final class ActionOptionDtoSuite extends FunSuite:

  test("a secret body carries the material under the key the spec names"):
    assertEquals(SecretOptionDto.render(secret("hunter2")), """{"data":"hunter2"}""")

  test("a secret body escapes what would otherwise break out of the JSON string"):
    assertEquals(
      SecretOptionDto.render(secret("line\nwith \"quotes\" and \\ backslash")),
      """{"data":"line\nwith \"quotes\" and \\ backslash"}""",
    )

  test("a fresh runner registration sends only the name Forgejo requires"):
    assertEquals(RegisterRunnerOptionDto.render(runner("build-box-3")), """{"name":"build-box-3"}""")

  test("a runner registration emits only the optional fields the caller set"):
    assertEquals(
      RegisterRunnerOptionDto.render(runner("build-box-3").describedAs("hetzner cx42").ephemeral),
      """{"name":"build-box-3","description":"hetzner cx42","ephemeral":true}""",
    )

  test("a dispatch sends only the ref when nothing else was asked for"):
    assertEquals(DispatchWorkflowOptionDto.render(dispatch("refs/heads/main")), """{"ref":"refs/heads/main"}""")

  test("a dispatch emits its inputs in the order the caller built them"):
    val command = dispatch("main").withInput("environment", "staging").withInput("dry_run", "true")

    assertEquals(
      DispatchWorkflowOptionDto.render(command),
      """{"ref":"main","inputs":{"environment":"staging","dry_run":"true"}}""",
    )

  test("a dispatch states return_run_info only when it is true, because false is the API's own default"):
    assertEquals(
      DispatchWorkflowOptionDto.render(dispatch("main").returningRunInfo),
      """{"ref":"main","return_run_info":true}""",
    )

  test("creating a variable sends the content under 'value', not under the 'data' it is read back as"):
    assertEquals(VariableOptionDto.renderCreate(CreateVariable.of("staging")), """{"value":"staging"}""")

  test("creating a variable sends an empty value as an empty value"):
    assertEquals(VariableOptionDto.renderCreate(CreateVariable.of("")), """{"value":""}""")

  test("updating a variable sends the value alone when nothing is being renamed"):
    assertEquals(VariableOptionDto.renderUpdate(UpdateVariable.of("staging")), """{"value":"staging"}""")

  test("updating a variable sends the new name only when the command carries one"):
    val command = UpdateVariable.of("staging").movedTo(orFail(VariableName.from("ENVIRONMENT")))

    assertEquals(VariableOptionDto.renderUpdate(command), """{"value":"staging","name":"ENVIRONMENT"}""")

  private def secret(value: String): SecretValue =
    orFail(SecretValue.from(value))

  private def runner(name: String): RegisterRunner =
    orFail(RegisterRunner.named(name))

  private def dispatch(ref: String): DispatchWorkflow =
    orFail(DispatchWorkflow.on(ref))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
