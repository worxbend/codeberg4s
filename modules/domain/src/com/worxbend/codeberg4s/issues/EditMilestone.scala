package com.worxbend.codeberg4s.issues

import java.time.Instant

/** What `PATCH /repos/{owner}/{repo}/milestones/{id}` may be told — Forgejo's `EditMilestoneOption`.
  *
  * '''Derived from `spec/swagger.v1.json`''', which declares the same four properties as `CreateMilestoneOption` and
  * marks none required. No golden capture of this request exists.
  *
  * '''Only what is set is sent''', which is the whole contract of a `PATCH`: an unset field contributes no JSON key and
  * the instance leaves that property alone. There is no way to '''clear''' a deadline through this endpoint — Forgejo
  * declares no `unset_due_on` flag, unlike [[EditIssue.withoutDueDate]] — so a milestone's deadline can be changed but
  * not removed, and that is the API's limitation rather than this type's.
  *
  * An [[EditMilestone.Empty]] sent as-is is a well-formed request that changes nothing.
  *
  * @param title
  *   the new milestone name; a plain `String` for the reason [[CreateMilestone]] gives
  * @param state
  *   the lifecycle transition to apply; see [[IssueStateChange]], shared with issues because it is the same transition
  */
final case class EditMilestone(
    title: Option[String],
    description: Option[String],
    dueOn: Option[Instant],
    state: Option[IssueStateChange],
):

  /** Renames the milestone. */
  def renamedTo(text: String): EditMilestone = copy(title = Some(text))

  /** Replaces the description. */
  def describedAs(text: String): EditMilestone = copy(description = Some(text))

  /** Moves the deadline; see the class note on why it cannot be cleared. */
  def dueBy(moment: Instant): EditMilestone = copy(dueOn = Some(moment))

  /** Closes the milestone. */
  def close: EditMilestone = copy(state = Some(IssueStateChange.Close))

  /** Reopens the milestone. */
  def reopen: EditMilestone = copy(state = Some(IssueStateChange.Reopen))

object EditMilestone:

  /** An edit that changes nothing — the starting point for every milestone edit. */
  val Empty: EditMilestone = EditMilestone(title = None, description = None, dueOn = None, state = None)
