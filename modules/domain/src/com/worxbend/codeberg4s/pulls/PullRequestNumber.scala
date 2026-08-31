package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.PositiveId
import com.worxbend.codeberg4s.ValidationError

/** The number a repository gives a pull request — the `{index}` of `/repos/{owner}/{repo}/pulls/{index}`.
  *
  * This is '''not''' [[PullRequest.id]]. Forgejo returns both: `id` is the instance-wide row identifier, which no
  * endpoint in this group accepts, and `number` is the per-repository index every URL and every human uses. They are
  * both `int64` and both plausible values for each other, so keeping them in different types is the only thing that
  * stops a `404` — or, worse, a successful read of an unrelated pull request.
  *
  * ==Why this is not `IssueNumber`==
  *
  * Forgejo numbers issues and pull requests from one shared per-repository sequence, so pull request `13731` and issue
  * `13731` are the same underlying row and `GET /issues/13731` really does answer with the pull request. The two types
  * are still kept apart, because the '''endpoints''' are not interchangeable: `GET /pulls/{index}` on an issue number
  * that belongs to a plain issue answers `404`, and `GET /issues/{index}` on a pull request answers with the
  * issue-shaped projection rather than with a [[PullRequest]]. A single type would make both mistakes silent, and
  * neither is worth the saving of one opaque type. There is deliberately no conversion between them for the same
  * reason.
  */
opaque type PullRequestNumber = Long

object PullRequestNumber:

  /** Parses a pull-request number.
    *
    * Rejects anything below `1`. There is no upper bound: how far a repository's counter has run is a property of the
    * repository, not of this type.
    *
    * @return
    *   the number, or a [[ValidationError]] on the `"pullRequestNumber"` field
    */
  def from(value: Long): Either[ValidationError, PullRequestNumber] =
    PositiveId.from("pullRequestNumber", value)

  extension (number: PullRequestNumber)

    /** The number as a `Long`. */
    def value: Long = number
