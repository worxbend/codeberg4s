package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

/** The number a repository gives an issue — the `{index}` of `/repos/{owner}/{repo}/issues/{index}`.
  *
  * This is '''not''' [[Issue.id]]. Forgejo returns both on every issue: `id` is the instance-wide row identifier, which
  * no endpoint in this group accepts, and `number` is the per-repository counter every URL and every human uses. They
  * are both `int64`, they are both plausible values for each other, and passing one where the other belongs produces a
  * `404` or, worse, a successful read of an unrelated issue. Keeping them in different types is the only thing that
  * stops that.
  *
  * The counter is shared with pull requests — Forgejo numbers issues and pull requests from the same sequence — so
  * `number` identifies an issue only together with a repository.
  */
opaque type IssueNumber = Long

object IssueNumber:

  /** Parses an issue number.
    *
    * Rejects anything below `1`. There is no upper bound: how far a repository's counter has run is a property of the
    * repository, not of this type.
    *
    * @return
    *   the number, or a [[ValidationError]] on the `"issueNumber"` field
    */
  def from(value: Long): Either[ValidationError, IssueNumber] =
    NumericId.from("issueNumber", value)

  extension (number: IssueNumber)

    /** The number as a `Long`. */
    def value: Long = number
