package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.{PositiveId, ValidationError}

/** The instance-wide identifier of a [[Label]] — the `{id}` of `/repos/{owner}/{repo}/labels/{id}`.
  *
  * Labels are addressed by id rather than by name, because a name is not unique across a repository and an
  * organisation: `golden/organization/org-labels-list.json` and `golden/issue/labels-repo.json` both contain a label
  * called `bug`, with different ids. It is also what [[CreateIssue.labelled]] sends, since `CreateIssueOption.labels`
  * is a list of ids and not of names.
  */
opaque type LabelId = Long

object LabelId:

  /** Parses a label id.
    *
    * Rejects anything below `1`.
    *
    * @return
    *   the id, or a [[ValidationError]] on the `"labelId"` field
    */
  def from(value: Long): Either[ValidationError, LabelId] =
    PositiveId.from("labelId", value)

  extension (id: LabelId)

    /** The id as a `Long`. */
    def value: Long = id
