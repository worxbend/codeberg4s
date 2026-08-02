package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.repositories.actions.DispatchWorkflow

/** Forgejo's `DispatchWorkflowOption` request model — the body of
  * `POST /repos/{owner}/{repo}/actions/workflows/{workflowfilename}/dispatches`.
  *
  * An object rather than a case class, for the reason [[com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto]]
  * gives.
  *
  * `ref` is the model's single required property and is always emitted. `inputs` is emitted only when the caller set
  * one, because an empty object and an absent key are the same request and the shorter one is easier to read in a
  * capture. `return_run_info` is emitted only when it is `true`: the spec gives it a default of `false`, so sending
  * that explicitly would state something the instance already assumes — and would change the shape of every dispatch
  * body for no benefit.
  */
private[codeberg4s] object DispatchWorkflowOptionDto:

  /** Renders `command` as the JSON body to `POST`.
    *
    * Inputs keep the order the command built them in, which is what makes a dispatch body reproducible between runs;
    * see [[com.worxbend.codeberg4s.repositories.actions.DispatchWorkflow]].
    */
  def render(command: DispatchWorkflow): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: DispatchWorkflow): List[(String, JsonValue)] =
    List(
      Some("ref"                                           -> JsonValue.Str(command.ref)),
      Option.when(command.inputs.nonEmpty)("inputs"        -> inputs(command)),
      Option.when(command.returnRunInfo)("return_run_info" -> JsonValue.Bool(true)),
    ).flatten

  private def inputs(command: DispatchWorkflow): JsonValue =
    JsonValue.Obj.from(command.inputs.map((name, value) => name -> JsonValue.Str(value)))
