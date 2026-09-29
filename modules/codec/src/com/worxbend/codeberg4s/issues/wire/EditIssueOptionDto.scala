package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.{Json, JsonValue, Timestamps, WireValues}
import com.worxbend.codeberg4s.issues.{DueDateChange, EditIssue, MilestoneChange}

/** Forgejo's `EditIssueOption` request model — the body of `PATCH /repos/{owner}/{repo}/issues/{index}`.
  *
  * An object rather than a case class, for the reason [[CreateIssueOptionDto]] gives.
  *
  * '''Only what the caller set is emitted, and here that is the entire semantics of the request.''' A `PATCH` leaves
  * alone whatever the body does not mention, so an unset field must not become a key. Three consequences are worth
  * spelling out because they are easy to get wrong:
  *
  *   - `assignees` is emitted whenever [[com.worxbend.codeberg4s.issues.EditIssue.assignedTo]] was called, '''including
  *     with an empty vector''' — Forgejo replaces the assignee list rather than adding to it, so `[]` is how a caller
  *     unassigns everyone, and it must be distinguishable from never having mentioned assignees.
  *   - clearing a deadline is `unset_due_date: true`, not `due_date: null`, because Forgejo cannot tell a null from an
  *     absent key. Setting and clearing are the two cases of one [[com.worxbend.codeberg4s.issues.DueDateChange]], so
  *     exactly one of the two keys is emitted and never both.
  *   - clearing a milestone is `milestone: 0`, for the same reason `null` will not do; `0` is the value Forgejo reads
  *     as "no milestone" and is not a [[com.worxbend.codeberg4s.issues.MilestoneId]], which refuses it. The two cases
  *     of [[com.worxbend.codeberg4s.issues.MilestoneChange]] are the only way to reach either spelling.
  *
  * An [[com.worxbend.codeberg4s.issues.EditIssue.Empty]] renders as `{}`, which is a well-formed request that changes
  * nothing.
  */
private[codeberg4s] object EditIssueOptionDto:

  /** The wire key of the milestone, shared with `EditPullRequestOption`. */
  val MilestoneKey: String = "milestone"

  /** The number Forgejo reads as "no milestone". Not a `MilestoneId`, which refuses anything below one — that refusal
    * is what keeps this spelling out of every other request.
    */
  val NoMilestone: JsonValue = WireValues.whole(0L)

  /** Renders `command` as the JSON body to `PATCH`. */
  def render(command: EditIssue): String =
    Json.render(JsonValue.Obj.from(fields(command)))

  private def fields(command: EditIssue): List[(String, JsonValue)] =
    List(
      command.title.map(text       => "title" -> JsonValue.Str(text)),
      command.body.map(text        => "body" -> JsonValue.Str(text)),
      command.assignees.map(logins => "assignees" -> WireValues.strings(logins)),
      command.milestone.map(milestoneField),
      command.state.map(change     => "state" -> JsonValue.Str(change.wireValue)),
      command.dueDate.map(dueDateField),
      command.ref.map(reference    => "ref" -> JsonValue.Str(reference)),
    ).flatten

  /** The one key a milestone change becomes: the id to move into, or [[NoMilestone]] to move out.
    *
    * Shared with [[com.worxbend.codeberg4s.pulls.wire.EditPullRequestOptionDto]], whose `EditPullRequestOption` has the
    * same key with the same meaning, so the spelling of "none" is decided once.
    */
  def milestoneField(change: MilestoneChange): (String, JsonValue) =
    change match
      case MilestoneChange.Assign(id) => MilestoneKey -> WireValues.identifier(id.value)
      case MilestoneChange.Clear      => MilestoneKey -> NoMilestone

  /** The one key a deadline change becomes: `due_date` to set one, `unset_due_date: true` to clear it.
    *
    * Shared with the pull-request renderer for the reason [[milestoneField]] gives. The flag is emitted only when set,
    * and a body can never carry both keys because the change is one value.
    */
  def dueDateField(change: DueDateChange): (String, JsonValue) =
    change match
      case DueDateChange.DueBy(moment) => "due_date"       -> JsonValue.Str(Timestamps.render(moment))
      case DueDateChange.Clear         => "unset_due_date" -> JsonValue.Bool(true)
