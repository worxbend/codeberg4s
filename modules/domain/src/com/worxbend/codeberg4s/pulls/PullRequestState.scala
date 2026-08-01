package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.users.User

import java.time.Instant
import java.util.Locale

/** Where a [[PullRequest]] is in its lifecycle, with the evidence of a merge attached to the one state that has any.
  *
  * A pull request is open, closed without being merged, or merged. Forgejo spreads that across '''five''' independent
  * fields — `state`, `closed_at`, `merged`, `merged_at`, `merge_commit_sha` and `merged_by` — which is exactly the
  * shape `SCALA_CODE_STYLE.md` forbids a domain model to keep: as six optional fields a caller can build a pull request
  * that is open and merged, or merged with no merge commit and closed at a time it was never closed, and every reader
  * has to remember which combinations the server actually produces.
  *
  * ==`state` alone cannot tell you==
  *
  * '''A merged pull request reports `state: "closed"`.''' `golden/pull/single-merged.json` is pull request 13726 with
  * `"state": "closed"`, `"merged": true`, a `merged_at`, a `merge_commit_sha` and a `merged_by`, and
  * `golden/pull/list-closed.json` contains that same pull request beside 13730 and 13711, which are `"closed"` and
  * '''not''' merged. Anything that branches on `state` and stops there reports every merge as a rejection. That is why
  * [[from]] looks at the merge evidence before it looks at `state`.
  *
  * ==Why the merged fields are still optional==
  *
  * They are measured, not assumed. In `golden/pull/list-closed.json` pull request 13726 arrives with `merged: true`,
  * `merged_at` and `merge_commit_sha` all populated and `"merged_by": null` — while `golden/pull/single-merged.json`,
  * the '''same''' pull request read through `GET /pulls/{index}`, carries a full `merged_by` user. The listing endpoint
  * simply does not resolve the merging account. Making `mergedBy` mandatory would therefore fail every merged pull
  * request on every listing, so [[Merged]] keeps each piece of evidence optional and only guarantees that an [[Open]]
  * or a [[Closed]] pull request has none of it.
  */
enum PullRequestState:

  /** Still open. Carries no timestamp, because there is nothing to timestamp. */
  case Open

  /** Closed without being merged, together with when — absent when the instance did not record it. */
  case Closed(closedAt: Option[Instant])

  /** Merged, together with whatever the endpoint said about the merge.
    *
    * `closed_at` is not repeated here: Forgejo sets it to the merge instant on every merged pull request in the
    * fixtures, so it would be [[mergedAt]] under another name. See the type note for why the three fields below are
    * optional rather than mandatory.
    *
    * @param mergedAt
    *   when the merge landed
    * @param mergedBy
    *   the account that pressed the button, absent on the listing endpoint even when the merge is real
    * @param mergeCommit
    *   the commit the merge produced. Absent for a [[MergeStyle.FastForwardOnly]] merge, which creates no commit
    */
  case Merged(mergedAt: Option[Instant], mergedBy: Option[User], mergeCommit: Option[CommitSha])

  /** Whether this is [[PullRequestState.Open]]. */
  def isOpen: Boolean =
    this match
      case Open            => true
      case Closed(_)       => false
      case Merged(_, _, _) => false

  /** Whether this is [[PullRequestState.Merged]]. */
  def isMerged: Boolean =
    this match
      case Merged(_, _, _) => true
      case Open            => false
      case Closed(_)       => false

  /** Whether the pull request is no longer open — closed '''or''' merged.
    *
    * Named for what a caller usually wants when they ask. A caller who needs the two apart matches on the cases, which
    * is the reason they are cases.
    */
  def isClosed: Boolean = !isOpen

object PullRequestState:

  /** The value Forgejo's `StateType` uses for an open pull request. */
  val OpenWire: String = "open"

  /** The value Forgejo's `StateType` uses for a pull request that is no longer open — merged ones included. */
  val ClosedWire: String = "closed"

  /** Reassembles the state from the fields the wire splits it into.
    *
    * '''Merge evidence is checked first''', before `state` is looked at, because a merged pull request reports
    * `state: "closed"` and would otherwise be indistinguishable from a rejected one. A pull request counts as merged
    * when Forgejo says `merged: true` '''or''' when it supplied a `merged_at` — the second arm costs nothing and means
    * an instance that populates the timestamp without the flag is still read correctly.
    *
    * `closedAt` is dropped for an open pull request rather than carried along, which is the point of the type: an
    * instance that sends `state: "open"` beside a stale `closed_at` cannot smuggle the contradiction into the domain.
    *
    * @param state
    *   the raw `state` field, matched case-insensitively after trimming
    * @param merged
    *   the raw `merged` flag
    * @param mergedAt
    *   the already-parsed `merged_at`
    * @param mergedBy
    *   the already-converted `merged_by`
    * @param mergeCommit
    *   the already-validated `merge_commit_sha`
    * @param closedAt
    *   the already-parsed `closed_at`, used only when the pull request is closed and not merged
    * @return
    *   the state, or a [[ValidationError]] on the `"state"` field when `state` is neither spelling and there is no
    *   merge evidence to fall back on
    */
  def from(
      state: String,
      merged: Boolean,
      mergedAt: Option[Instant],
      mergedBy: Option[User],
      mergeCommit: Option[CommitSha],
      closedAt: Option[Instant],
  ): Either[ValidationError, PullRequestState] =
    if merged || mergedAt.isDefined then Right(Merged(mergedAt, mergedBy, mergeCommit))
    else
      state.trim.toLowerCase(Locale.ROOT) match
        case OpenWire   => Right(Open)
        case ClosedWire => Right(Closed(closedAt))
        case other      => Left(ValidationError("state", s"must be '$OpenWire' or '$ClosedWire', not '$other'"))
