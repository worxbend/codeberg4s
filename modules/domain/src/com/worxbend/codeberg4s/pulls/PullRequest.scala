package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.issues.{Label, Milestone}
import com.worxbend.codeberg4s.repositories.CommitSha
import com.worxbend.codeberg4s.users.User

import java.time.Instant

/** A pull request on a Codeberg or Forgejo instance.
  *
  * Curated, not generated (ADR-0001), and owned by this group per `docs/LEDGER.md`. [[Label]], [[Milestone]] and
  * [[com.worxbend.codeberg4s.users.User]] are the issue and user waves' models, embedded unchanged: Forgejo returns the
  * identical objects inside a `PullRequest` as inside an `Issue`, so forking them would be a review-blocking defect.
  *
  * ==The lifecycle is a state, not five fields==
  *
  * `state`, `closed_at`, `merged`, `merged_at`, `merge_commit_sha` and `merged_by` collapse into [[state]]. That is the
  * single most important thing about this model, and [[PullRequestState]] explains why — including the trap that a
  * merged pull request reports `state: "closed"` on the wire.
  *
  * ==Keys deliberately not modelled==
  *
  * Four of Forgejo's thirty-eight `PullRequest` keys are kept on [[com.worxbend.codeberg4s.pulls.wire.PullRequestDto]]
  * and nowhere else:
  *
  *   - `assignee` duplicates the first element of `assignees`, exactly as it does on an issue;
  *   - `requested_reviewers_teams` is an array of Forgejo's `Team` model, which `docs/LEDGER.md` assigns to the
  *     organisation wave. It is `[]` on every pull request in the fixtures, and inventing a shape from an empty array
  *     would be a guess;
  *   - `pin_order` describes a repository's pinned-pull-request ordering rather than the pull request;
  *   - `flow` is Forgejo's AGit indicator, `0` on all five captured pull requests, and means nothing to a caller who
  *     did not push with AGit.
  *
  * @param id
  *   the instance-wide row identifier. Almost never what a caller wants: no endpoint accepts it, and every URL uses
  *   [[number]]
  * @param number
  *   the per-repository index every endpoint and every human uses; see [[PullRequestNumber]]
  * @param state
  *   open, closed, or merged together with the evidence of the merge; see [[PullRequestState]]
  * @param isDraft
  *   whether the pull request is marked as not ready for review. Independent of [[state]]: a draft is an open pull
  *   request that Forgejo refuses to merge, not a fourth lifecycle state
  * @param author
  *   the account that opened it, absent for content imported from another forge
  * @param assignees
  *   everyone assigned, empty when unassigned — Forgejo sends `null`, not `[]`, in that case
  * @param requestedReviewers
  *   the accounts whose review was asked for. Not the accounts that reviewed: those are on the reviews endpoint, and a
  *   reviewer who has already submitted may still appear here
  * @param base
  *   the branch the pull request merges '''into'''
  * @param head
  *   the branch the pull request merges '''from'''; its repository differs from the base's for a fork pull request
  * @param mergeBase
  *   the common ancestor the diff is computed against
  * @param isMergeable
  *   the instance's own verdict, absent when it did not report one. Advisory: it is computed in the background, so it
  *   can be stale, and a merge can still be refused by a branch protection rule that this flag knows nothing about
  * @param allowsMaintainerEdit
  *   whether the base repository's maintainers may push to the head branch
  * @param commentCount
  *   ordinary issue-style comments, as the instance counts them; `0` when it did not say
  * @param reviewCommentCount
  *   comments made on the diff during a review, which Forgejo counts separately from [[commentCount]]
  * @param changedFileCount
  *   how many files the pull request touches; the [[com.worxbend.codeberg4s.pulls.ChangedFile]] listing is the detail
  * @param diffUrl
  *   the browser URL of the unified diff — a `.diff` document, not JSON, and not fetched by this library
  * @param patchUrl
  *   the browser URL of the mailbox-format patch series
  * @param dueDate
  *   the deadline set on the pull request, absent when it has none
  */
final case class PullRequest private[codeberg4s] (
    id: Long,
    number: PullRequestNumber,
    title: String,
    body: Option[String],
    state: PullRequestState,
    isDraft: Boolean,
    author: Option[User],
    assignees: Vector[User],
    requestedReviewers: Vector[User],
    labels: Vector[Label],
    milestone: Option[Milestone],
    base: Option[PullRequestBranch],
    head: Option[PullRequestBranch],
    mergeBase: Option[CommitSha],
    isMergeable: Option[Boolean],
    allowsMaintainerEdit: Boolean,
    isLocked: Boolean,
    commentCount: Long,
    reviewCommentCount: Long,
    additions: Long,
    deletions: Long,
    changedFileCount: Long,
    htmlUrl: Option[String],
    url: Option[String],
    diffUrl: Option[String],
    patchUrl: Option[String],
    dueDate: Option[Instant],
    createdAt: Option[Instant],
    updatedAt: Option[Instant],
)
