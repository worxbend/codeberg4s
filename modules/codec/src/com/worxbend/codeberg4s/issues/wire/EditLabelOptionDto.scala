package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.issues.EditLabel

/** Forgejo's `EditLabelOption` request model — the body of `PATCH /repos/{owner}/{repo}/labels/{id}`.
  *
  * An object rather than a case class, for the reason [[CreateIssueOptionDto]] gives. Derived from
  * `spec/swagger.v1.json`; no golden capture of this request exists.
  *
  * '''Nothing is unconditional.''' Every one of the five properties is emitted only when the caller set it, which is
  * the contract of a `PATCH` — see [[com.worxbend.codeberg4s.issues.EditLabel]] for why the two booleans are
  * `Option[Boolean]` and not `Boolean`.
  *
  * `color` is sent in the `#rrggbb` form Forgejo documents for input, via
  * [[com.worxbend.codeberg4s.issues.LabelColor.hashed]], even though the same field comes '''back''' without the `#` on
  * every label in the golden fixtures — the same asymmetry [[CreateLabelOptionDto]] handles.
  */
private[codeberg4s] object EditLabelOptionDto:

  /** Renders `command` as the JSON body to `PATCH`; `{}` when it changes nothing. */
  def render(command: EditLabel): String =
    ujson.write(ujson.Obj.from(fields(command)))

  private def fields(command: EditLabel): List[(String, ujson.Value)] =
    List(
      command.name.map(text        => "name" -> ujson.Str(text.value)),
      command.color.map(shade      => "color" -> ujson.Str(shade.hashed)),
      command.description.map(text => "description" -> ujson.Str(text)),
      command.isExclusive.map(flag => "exclusive" -> ujson.Bool(flag)),
      command.isArchived.map(flag  => "is_archived" -> ujson.Bool(flag)),
    ).flatten
