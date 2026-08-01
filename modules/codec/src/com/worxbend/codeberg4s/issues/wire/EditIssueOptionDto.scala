package com.worxbend.codeberg4s.issues.wire

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
    ujson.write(ujson.Obj.from(fields(command)))

  private def fields(command: EditIssue): List[(String, ujson.Value)] =
    List(
      command.title.map(text       => "title" -> ujson.Str(text)),
      command.body.map(text        => "body" -> ujson.Str(text)),
      command.assignees.map(logins => "assignees" -> WireNumbers.strings(logins)),
      command.milestone.map(id     => "milestone" -> WireNumbers.identifier(id.value)),
      command.state.map(change     => "state" -> ujson.Str(change.wireValue)),
      command.dueDate.map(moment   => "due_date" -> ujson.Str(WireInstant.render(moment))),
      Option.when(command.unsetDueDate)("unset_due_date" -> ujson.Bool(true)),
      command.ref.map(reference => "ref" -> ujson.Str(reference)),
    ).flatten
