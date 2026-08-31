package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue}
import com.worxbend.codeberg4s.issues.CreateLabel

/** Forgejo's `CreateLabelOption` request model — the body of `POST /repos/{owner}/{repo}/labels`.
  *
  * An object rather than a case class, for the reason [[CreateIssueOptionDto]] gives.
  *
  * `name` and `color` are the model's two required properties and are always emitted. `color` is sent in the `#rrggbb`
  * form Forgejo documents for input, via [[com.worxbend.codeberg4s.issues.LabelColor.hashed]], even though the same
  * field comes '''back''' without the `#` on every label in the golden fixtures. Normalising in both directions is what
  * keeps that asymmetry out of the domain.
  *
  * `exclusive` and `is_archived` are emitted only when the caller turned them on, so a plain
  * [[com.worxbend.codeberg4s.issues.CreateLabel.of]] leaves the instance's defaults alone.
  */
private[codeberg4s] object CreateLabelOptionDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: CreateLabel): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: CreateLabel): List[(String, JsonValue)] =
    List(
      Some("name"  -> JsonValue.Str(command.name.value)),
      Some("color" -> JsonValue.Str(command.color.hashed)),
      command.description.map(text => "description" -> JsonValue.Str(text)),
      Option.when(command.isExclusive)("exclusive"  -> JsonValue.Bool(true)),
      Option.when(command.isArchived)("is_archived" -> JsonValue.Bool(true)),
    ).flatten
