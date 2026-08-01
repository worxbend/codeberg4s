package com.worxbend.codeberg4s.issues

import java.time.Instant

/** Everything `PATCH /repos/{owner}/{repo}/issues/{index}` may be told, as one value.
  *
  * Built by starting from [[EditIssue.Empty]] and naming only what should change:
  *
  * {{{
  * EditIssue.Empty.withTitle("the build is red again").close
  * }}}
  *
  * '''Only what is set is sent, and that is the whole contract of a PATCH.''' An unset field contributes no JSON key,
  * so the instance leaves that property alone. A field set to its "empty" value is a different request: clearing a
  * deadline is [[withoutDueDate]], which sends `unset_due_date: true`, and not [[dueBy]] with some sentinel instant.
  * Forgejo needs the separate flag because `due_date: null` is indistinguishable from an absent key on its side.
  *
  * An [[EditIssue.Empty]] sent as-is is a well-formed request that changes nothing. It is not rejected here, because
  * "apply whatever the user edited, which today is nothing" is a reasonable thing for a caller's code to arrive at.
  *
  * @param assignees
  *   the complete new assignee list; Forgejo '''replaces''' rather than adds, so an empty vector unassigns everyone.
  *   That is why the field is `Option[Vector[String]]` and not `Vector[String]` — absent leaves the assignees alone,
  *   present-and-empty clears them
  * @param state
  *   the lifecycle transition to apply; see [[IssueStateChange]] for why this is not a [[LifecycleState]]
  * @param unsetDueDate
  *   whether to clear an existing deadline. Ignored by Forgejo when [[dueDate]] is also set
  */
final case class EditIssue(
    title: Option[String],
    body: Option[String],
    assignees: Option[Vector[String]],
    milestone: Option[MilestoneId],
    state: Option[IssueStateChange],
    dueDate: Option[Instant],
    unsetDueDate: Boolean,
    ref: Option[String],
):

  /** Renames the issue. */
  def withTitle(text: String): EditIssue = copy(title = Some(text))

  /** Replaces the issue description. */
  def withBody(text: String): EditIssue = copy(body = Some(text))

  /** Replaces the assignee list outright; an empty vector unassigns everyone. */
  def assignedTo(logins: Vector[String]): EditIssue = copy(assignees = Some(logins))

  /** Moves the issue into the milestone `id`. */
  def inMilestone(id: MilestoneId): EditIssue = copy(milestone = Some(id))

  /** Closes the issue. The instance stamps `closed_at`; see [[IssueStateChange]]. */
  def close: EditIssue = copy(state = Some(IssueStateChange.Close))

  /** Reopens the issue. */
  def reopen: EditIssue = copy(state = Some(IssueStateChange.Reopen))

  /** Sets the deadline. */
  def dueBy(moment: Instant): EditIssue = copy(dueDate = Some(moment))

  /** Clears the deadline, which needs its own flag on the wire — see the class note. */
  def withoutDueDate: EditIssue = copy(unsetDueDate = true)

  /** Files the issue against a Git reference. */
  def onRef(reference: String): EditIssue = copy(ref = Some(reference))

object EditIssue:

  /** An edit that changes nothing — the starting point for every edit. */
  val Empty: EditIssue =
    EditIssue(
      title        = None,
      body         = None,
      assignees    = None,
      milestone    = None,
      state        = None,
      dueDate      = None,
      unsetDueDate = false,
      ref          = None,
    )
