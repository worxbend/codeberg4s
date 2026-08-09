package com.worxbend.codeberg4s.issues

import java.time.Instant

/** A milestone: a named, optionally dated bucket that issues and pull requests are grouped into.
  *
  * Owned by this group per `docs/LEDGER.md` and consumed by pull requests, which embed the identical model.
  *
  * The two counts are the reason a milestone is worth reading at all — a caller renders progress from them — and
  * Forgejo maintains them itself, so they are `Long` rather than `Option[Long]`: an absent count is reported as `0`,
  * which is the same answer the instance gives for an empty milestone.
  *
  * @param id
  *   the instance-wide identifier, and the only way to address a milestone
  * @param title
  *   the milestone's name, verbatim. A plain `String` rather than [[MilestoneTitle]] — see [[MilestoneTitle]] for why
  * @param state
  *   open, or closed together with when; see [[LifecycleState]] for why `closed_at` is not a separate field
  * @param openIssueCount
  *   how many issues in the milestone are still open
  * @param closedIssueCount
  *   how many issues in the milestone have been closed
  * @param dueOn
  *   the deadline the milestone was given, absent when it has none. Forgejo's `due_on`, sent as `null` when unset
  */
final case class Milestone private[codeberg4s] (
    id: MilestoneId,
    title: String,
    description: Option[String],
    state: LifecycleState,
    openIssueCount: Long,
    closedIssueCount: Long,
    dueOn: Option[Instant],
    createdAt: Option[Instant],
    updatedAt: Option[Instant],
)
