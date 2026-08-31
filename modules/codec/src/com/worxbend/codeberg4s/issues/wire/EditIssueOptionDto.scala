package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.codec.JsonValue
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.WireValues
import com.worxbend.codeberg4s.issues.EditIssue

/** Forgejo's `EditIssueOption` request model — the body of `PATCH /repos/{owner}/{repo}/issues/{index}`.
  *
  * An object rather than a case class, for the reason [[CreateIssueOptionDto]] gives.
  *
  * '''Only what the caller set is emitted, and here that is the entire semantics of the request.''' A `PATCH` leaves
  * alone whatever the body does not mention, so an unset field must not become a key. Two consequences are worth
  * spelling out because they are easy to get wrong:
  *
  *   - `assignees` is emitted whenever [[com.worxbend.codeberg4s.issues.EditIssue.assignedTo]] was called, '''including
  *     with an empty vector''' — Forgejo replaces the assignee list rather than adding to it, so `[]` is how a caller
  *     unassigns everyone, and it must be distinguishable from never having mentioned assignees.
  *   - clearing a deadline is `unset_due_date: true`, not `due_date: null`, because Forgejo cannot tell a null from an
  *     absent key. The flag is emitted only when set.
  *
  * An [[com.worxbend.codeberg4s.issues.EditIssue.Empty]] renders as `{}`, which is a well-formed request that changes
  * nothing.
  */
private[codeberg4s] object EditIssueOptionDto:

  /** Renders `command` as the JSON body to `PATCH`. */
  def render(command: EditIssue): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: EditIssue): List[(String, JsonValue)] =
    List(
      command.title.map(text       => "title" -> JsonValue.Str(text)),
      command.body.map(text        => "body" -> JsonValue.Str(text)),
      command.assignees.map(logins => "assignees" -> WireValues.strings(logins)),
      command.milestone.map(id     => "milestone" -> WireValues.identifier(id.value)),
      command.state.map(change     => "state" -> JsonValue.Str(change.wireValue)),
      command.dueDate.map(moment   => "due_date" -> JsonValue.Str(Timestamps.render(moment))),
      Option.when(command.unsetDueDate)("unset_due_date" -> JsonValue.Bool(true)),
      command.ref.map(reference => "ref" -> JsonValue.Str(reference)),
    ).flatten
