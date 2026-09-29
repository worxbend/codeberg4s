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
  * Clearing a milestone is [[withoutMilestone]], for the same reason — see [[MilestoneChange]].
  *
  * '''Setting and clearing the same property are one field, not two.''' [[dueDate]] is a [[DueDateChange]] and
  * [[milestone]] a [[MilestoneChange]], each of which is either a new value or a clearing, so the later of
  * `dueBy(friday)` and `withoutDueDate` replaces the earlier and a body can never carry both `due_date` and
  * `unset_due_date: true`. The field used to be an `Option[Instant]` beside an `unsetDueDate: Boolean`, which let a
  * caller build exactly that request and left Forgejo to decide which half to honour.
  *
  * An [[EditIssue.Empty]] sent as-is is a well-formed request that changes nothing. It is not rejected here, because
  * "apply whatever the user edited, which today is nothing" is a reasonable thing for a caller's code to arrive at.
  *
  * @param assignees
  *   the complete new assignee list; Forgejo '''replaces''' rather than adds, so an empty vector unassigns everyone.
  *   That is why the field is `Option[Vector[String]]` and not `Vector[String]` — absent leaves the assignees alone,
  *   present-and-empty clears them
  * @param milestone
  *   the milestone to move into, or the instruction to leave the current one; absent leaves the milestone alone
  * @param state
  *   the lifecycle transition to apply; see [[IssueStateChange]] for why this is not a [[LifecycleState]]
  * @param dueDate
  *   the deadline to set, or the instruction to clear the current one; absent leaves the deadline alone
  */
final case class EditIssue(
    title: Option[String],
    body: Option[String],
    assignees: Option[Vector[String]],
    milestone: Option[MilestoneChange],
    state: Option[IssueStateChange],
    dueDate: Option[DueDateChange],
    ref: Option[String],
):

  /** Renames the issue. */
  def withTitle(text: String): EditIssue = copy(title = Some(text))

  /** Replaces the issue description. */
  def withBody(text: String): EditIssue = copy(body = Some(text))

  /** Replaces the assignee list outright; an empty vector unassigns everyone. */
  def assignedTo(logins: Vector[String]): EditIssue = copy(assignees = Some(logins))

  /** Moves the issue into the milestone `id`. */
  def inMilestone(id: MilestoneId): EditIssue = copy(milestone = Some(MilestoneChange.Assign(id)))

  /** Takes the issue out of whatever milestone it is in — see [[MilestoneChange.Clear]] for what goes on the wire. */
  def withoutMilestone: EditIssue = copy(milestone = Some(MilestoneChange.Clear))

  /** Closes the issue. The instance stamps `closed_at`; see [[IssueStateChange]]. */
  def close: EditIssue = copy(state = Some(IssueStateChange.Close))

  /** Reopens the issue. */
  def reopen: EditIssue = copy(state = Some(IssueStateChange.Reopen))

  /** Sets the deadline. */
  def dueBy(moment: Instant): EditIssue = copy(dueDate = Some(DueDateChange.DueBy(moment)))

  /** Clears the deadline, which needs its own flag on the wire — see the class note. */
  def withoutDueDate: EditIssue = copy(dueDate = Some(DueDateChange.Clear))

  /** Files the issue against a Git reference. */
  def onRef(reference: String): EditIssue = copy(ref = Some(reference))

object EditIssue:

  /** An edit that changes nothing — the starting point for every edit. */
  val Empty: EditIssue =
    EditIssue(
      title     = None,
      body      = None,
      assignees = None,
      milestone = None,
      state     = None,
      dueDate   = None,
      ref       = None,
    )

/** What an edit does to a deadline: sets a new one, or clears the one there is.
  *
  * One type rather than an `Option[Instant]` beside a `Boolean`, which is how [[EditIssue]] used to spell it. Two
  * fields let a caller write `dueBy(friday).withoutDueDate` and send `due_date` and `unset_due_date: true` in the same
  * body — a request nobody means, which Forgejo resolves by taking the date and ignoring the flag. With one field the
  * later builder replaces the earlier and the body carries exactly one of the two keys. That is the rule
  * `SCALA_CODE_STYLE.md` § "Enums And ADTs" states for lifecycle fields, applied to a command.
  *
  * [[com.worxbend.codeberg4s.pulls.EditPullRequest]] carries this same type: `EditPullRequestOption` has the same two
  * keys with the same meaning, and `docs/LEDGER.md` forbids forking a model that already exists.
  */
enum DueDateChange:

  /** Sets the deadline to `moment`. Sends `due_date`. */
  case DueBy(moment: Instant)

  /** Clears the deadline. Sends `unset_due_date: true`, because on Forgejo's side `due_date: null` is indistinguishable
    * from an absent key.
    */
  case Clear

/** What an edit does to a milestone: moves the issue into one, or takes it out of the one it is in.
  *
  * Clearing has no flag of its own on the wire. Forgejo's `EditIssueOption.milestone` is an `int64` whose `null` is
  * indistinguishable from an absent key on its side — the same problem a deadline solves with `unset_due_date` — and
  * the value it reads as "no milestone" is `0`. That value is not a [[MilestoneId]]: the type refuses anything below
  * `1`, precisely so that a `0` can never pass for a milestone anywhere else in the library. So the clearing is a named
  * case rather than a sentinel id, and the request renderer is the one place that knows it is spelled `0`.
  *
  * [[com.worxbend.codeberg4s.pulls.EditPullRequest]] carries this same type, for the reason [[DueDateChange]] gives.
  */
enum MilestoneChange:

  /** Moves the issue into the milestone `id`. Sends `milestone` with the id. */
  case Assign(id: MilestoneId)

  /** Takes the issue out of its milestone. Sends `milestone: 0`, the value Forgejo reads as none. */
  case Clear
