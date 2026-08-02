package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.repositories.actions.RegisterRunner

/** Forgejo's `RegisterRunnerOptions` request model — the body of `POST /repos/{owner}/{repo}/actions/runners`.
  *
  * An object rather than a case class, for the reason [[com.worxbend.codeberg4s.issues.wire.CreateIssueOptionDto]]
  * gives.
  *
  * `name` is the model's single required property and is always emitted. `description` and `ephemeral` are emitted only
  * when the caller set them, so a plain [[com.worxbend.codeberg4s.repositories.actions.RegisterRunner.named]] leaves
  * the instance's defaults alone.
  */
private[codeberg4s] object RegisterRunnerOptionDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: RegisterRunner): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: RegisterRunner): List[(String, JsonValue)] =
    List(
      Some("name" -> JsonValue.Str(command.name)),
      command.description.map(text => "description" -> JsonValue.Str(text)),
      Option.when(command.isEphemeral)("ephemeral" -> JsonValue.Bool(true)),
    ).flatten
