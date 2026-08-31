package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue, Timestamps}
import com.worxbend.codeberg4s.issues.{CreateMilestone, EditMilestone}

/** Forgejo's `CreateMilestoneOption` and `EditMilestoneOption` request models — the bodies of
  * `POST /repos/{owner}/{repo}/milestones` and `PATCH /repos/{owner}/{repo}/milestones/{id}`.
  *
  * An object rather than a case class, for the reason [[CreateIssueOptionDto]] gives. Derived from
  * `spec/swagger.v1.json`; no golden capture of either request exists.
  *
  * The two models declare the same four properties, which is why one file renders both. They differ only in how the
  * spec types `state` — an `enum` on create, a bare string on edit — and both accept the two words
  * [[com.worxbend.codeberg4s.issues.IssueStateChange]] emits, so nothing here has to know the difference.
  *
  * ==What is not emitted==
  *
  * On a create, `title` is always sent and everything else only when the caller set it, so the instance's own defaults
  * survive. On an edit, '''nothing''' is unconditional: that is the contract of a `PATCH`, and it is why an
  * [[com.worxbend.codeberg4s.issues.EditMilestone.Empty]] renders as `{}` rather than as a request that blanks the
  * milestone.
  */
private[codeberg4s] object MilestoneOptionDto:

  /** Renders `command` as the JSON body to `POST`. */
  def renderCreate(command: CreateMilestone): String =
    val fields = List(
      Some("title" -> JsonValue.Str(command.title)),
      command.description.map(text => "description" -> JsonValue.Str(text)),
      command.dueOn.map(moment     => "due_on" -> JsonValue.Str(Timestamps.render(moment))),
      command.state.map(transition => "state" -> JsonValue.Str(transition.wireValue)),
    ).flatten

    Json.render(JsonValue.Obj.from(fields))

  /** Renders `command` as the JSON body to `PATCH`; `{}` when it changes nothing. */
  def renderEdit(command: EditMilestone): String =
    val fields = List(
      command.title.map(text       => "title" -> JsonValue.Str(text)),
      command.description.map(text => "description" -> JsonValue.Str(text)),
      command.dueOn.map(moment     => "due_on" -> JsonValue.Str(Timestamps.render(moment))),
      command.state.map(transition => "state" -> JsonValue.Str(transition.wireValue)),
    ).flatten

    Json.render(JsonValue.Obj.from(fields))
