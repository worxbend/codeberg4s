package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

/** The instance-wide identifier of one [[TrackedTime]] entry — the `{id}` of
  * `/repos/{owner}/{repo}/issues/{index}/times/{id}`.
  *
  * Not an [[IssueNumber]] and not a [[CommentId]], though all three are `int64`; see [[NumericId]] for why they are
  * kept apart.
  *
  * ==Error contract==
  *
  * Construction produces [[ValidationError]] on the `"trackedTimeId"` field and nothing else; it performs no I/O.
  */
opaque type TrackedTimeId = Long

object TrackedTimeId:

  /** Parses a tracked-time identifier.
    *
    * Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"trackedTimeId"` field
    */
  def from(value: Long): Either[ValidationError, TrackedTimeId] =
    NumericId.from("trackedTimeId", value)

  extension (id: TrackedTimeId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id
