package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

/** The instance-wide identifier of a [[Milestone]] — the `{id}` of `/repos/{owner}/{repo}/milestones/{id}`.
  *
  * A milestone is read by id and assigned to an issue by id ([[CreateIssue.inMilestone]] sends
  * `CreateIssueOption.milestone`, an `int64`), even though the list endpoint additionally filters by title. Titles are
  * not unique across repositories and are freely renamed; the id is what survives a rename.
  */
opaque type MilestoneId = Long

object MilestoneId:

  /** Parses a milestone id.
    *
    * Rejects anything below `1`.
    *
    * @return
    *   the id, or a [[ValidationError]] on the `"milestoneId"` field
    */
  def from(value: Long): Either[ValidationError, MilestoneId] =
    NumericId.from("milestoneId", value)

  extension (id: MilestoneId)

    /** The id as a `Long`. */
    def value: Long = id
