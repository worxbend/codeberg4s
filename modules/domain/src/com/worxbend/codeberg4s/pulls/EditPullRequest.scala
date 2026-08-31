package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.issues.{IssueStateChange, LabelId, MilestoneId}
import com.worxbend.codeberg4s.repositories.BranchName

import java.time.Instant

/** Everything `PATCH /repos/{owner}/{repo}/pulls/{index}` may be told, as one value.
  *
  * Built by starting from [[EditPullRequest.Empty]] and naming only what should change:
  *
  * {{{
  * EditPullRequest.Empty.withTitle("fix the hook quoting").allowingMaintainerEdit
  * }}}
  *
  * '''Only what is set is sent, and that is the whole contract of a PATCH.''' An unset field contributes no JSON key,
  * so the instance leaves that property alone. Three consequences are worth spelling out because they are easy to get
  * wrong:
  *
  *   - [[assignees]] and [[labels]] '''replace''' rather than add, so an empty vector clears them. That is why both are
  *     `Option[Vector[…]]`: absent leaves the list alone, present-and-empty empties it.
  *   - clearing a deadline is [[withoutDueDate]], which sends `unset_due_date: true`, and not [[dueBy]] with some
  *     sentinel instant. Forgejo needs the separate flag because `due_date: null` is indistinguishable from an absent
  *     key on its side.
  *   - [[withBase]] '''retargets the pull request''', which makes Forgejo recompute the diff against a different
  *     branch. It is the one field here that can change what the pull request means rather than how it is labelled.
  *
  * An [[EditPullRequest.Empty]] sent as-is is a well-formed request that changes nothing.
  *
  * @param state
  *   the lifecycle transition to apply. [[com.worxbend.codeberg4s.issues.IssueStateChange]] and not a type of this
  *   group's own: Forgejo's `EditPullRequestOption.state` takes the same two `open`/`closed` spellings as
  *   `EditIssueOption.state`, and `docs/LEDGER.md` forbids forking a model that already exists. There is deliberately
  *   no transition that merges — merging is [[MergePullRequest]] and a different endpoint
  * @param allowMaintainerEdit
  *   whether the base repository's maintainers may push to the head branch; absent leaves the setting alone
  */
final case class EditPullRequest(
    title: Option[String],
    body: Option[String],
    assignees: Option[Vector[String]],
    labels: Option[Vector[LabelId]],
    milestone: Option[MilestoneId],
    state: Option[IssueStateChange],
    base: Option[BranchName],
    dueDate: Option[Instant],
    unsetDueDate: Boolean,
    allowMaintainerEdit: Option[Boolean],
):

  /** Renames the pull request. */
  def withTitle(text: String): EditPullRequest = copy(title = Some(text))

  /** Replaces the pull request description. */
  def withBody(text: String): EditPullRequest = copy(body = Some(text))

  /** Replaces the assignee list outright; an empty vector unassigns everyone. */
  def assignedTo(logins: Vector[String]): EditPullRequest = copy(assignees = Some(logins))

  /** Replaces the label list outright; an empty vector removes every label. */
  def labelled(ids: Vector[LabelId]): EditPullRequest = copy(labels = Some(ids))

  /** Moves the pull request into the milestone `id`. */
  def inMilestone(id: MilestoneId): EditPullRequest = copy(milestone = Some(id))

  /** Closes the pull request '''without''' merging it. Merging is [[MergePullRequest]]. */
  def close: EditPullRequest = copy(state = Some(IssueStateChange.Close))

  /** Reopens a closed pull request. A merged one cannot be reopened, and Forgejo answers `422`. */
  def reopen: EditPullRequest = copy(state = Some(IssueStateChange.Reopen))

  /** Retargets the pull request at `branch`; see the class note. */
  def withBase(branch: BranchName): EditPullRequest = copy(base = Some(branch))

  /** Sets the deadline. */
  def dueBy(moment: Instant): EditPullRequest = copy(dueDate = Some(moment))

  /** Clears the deadline, which needs its own flag on the wire — see the class note. */
  def withoutDueDate: EditPullRequest = copy(unsetDueDate = true)

  /** Lets the base repository's maintainers push to the head branch. */
  def allowingMaintainerEdit: EditPullRequest = copy(allowMaintainerEdit = Some(true))

  /** Stops the base repository's maintainers pushing to the head branch. */
  def forbiddingMaintainerEdit: EditPullRequest = copy(allowMaintainerEdit = Some(false))

object EditPullRequest:

  /** An edit that changes nothing — the starting point for every edit. */
  val Empty: EditPullRequest =
    EditPullRequest(
      title               = None,
      body                = None,
      assignees           = None,
      labels              = None,
      milestone           = None,
      state               = None,
      base                = None,
      dueDate             = None,
      unsetDueDate        = false,
      allowMaintainerEdit = None,
    )
