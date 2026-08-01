package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.repositories.RepoSlug
import com.worxbend.codeberg4s.users.User

import java.time.Instant

/** An issue on a Codeberg or Forgejo instance.
  *
  * Curated, not generated (ADR-0001), and owned by this group per `docs/LEDGER.md`. Three of Forgejo's twenty-five
  * `Issue` keys are deliberately absent from this model and kept only on
  * [[com.worxbend.codeberg4s.issues.wire.IssueDto]]: `assignee` duplicates the first element of `assignees`,
  * `original_author_id` is the foreign forge's row id and means nothing here, and `pin_order` describes a repository's
  * pinned-issue ordering rather than the issue.
  *
  * ==A listing of issues contains pull requests==
  *
  * `GET /repos/{owner}/{repo}/issues` returns both. `golden/issue/list-labelled.json` is a capture of that endpoint on
  * `forgejo/forgejo` and every element in it is a pull request, `html_url` and all. [[isPullRequest]] is how a caller
  * tells them apart, and it is the only pull-request concept this model carries — the `pull_request` payload itself
  * (draft, merged, merged_at) belongs to the pull-request wave and is not modelled here.
  *
  * ==Nullability==
  *
  * `docs/HAZARDS.md` §1 measured this model specifically: `assignee`, `assignees`, `closed_at`, `due_date` and
  * `milestone` all arrive as JSON `null` on the very first issue of the very first page. Absent and `null` are the same
  * thing to the decoder, a null array becomes an empty `Vector`, and `closed_at` is folded into [[state]] rather than
  * surviving as a field of its own.
  *
  * @param id
  *   the instance-wide row identifier. Almost never what a caller wants: no endpoint accepts it, and every URL uses
  *   [[number]]
  * @param number
  *   the per-repository index every endpoint and every human uses; see [[IssueNumber]]
  * @param state
  *   open, or closed together with when; see [[LifecycleState]] for why `closed_at` is not a separate field
  * @param author
  *   the account that opened the issue, absent for content imported from another forge
  * @param originalAuthor
  *   the display name an import recorded, absent for an issue opened on this instance
  * @param assignees
  *   everyone assigned, empty when the issue is unassigned — Forgejo sends `null`, not `[]`, in that case
  * @param repository
  *   which repository the issue belongs to, from Forgejo's reduced `repository` object. Worth reading on a
  *   cross-repository listing such as `GET /repos/issues/search`, where the issues come from everywhere
  * @param commentCount
  *   how many comments the issue has, as the instance counts them; `0` when it did not say
  * @param isPullRequest
  *   whether this "issue" is really a pull request — see above
  * @param ref
  *   the Git reference the issue was filed against, absent for the usual case of none
  * @param dueDate
  *   the deadline set on the issue, absent when it has none
  */
final case class Issue(
    id: Long,
    number: IssueNumber,
    title: String,
    body: Option[String],
    state: LifecycleState,
    author: Option[User],
    originalAuthor: Option[String],
    assignees: Vector[User],
    labels: Vector[Label],
    milestone: Option[Milestone],
    repository: Option[RepoSlug],
    commentCount: Long,
    isLocked: Boolean,
    isPullRequest: Boolean,
    htmlUrl: Option[String],
    url: Option[String],
    ref: Option[String],
    dueDate: Option[Instant],
    createdAt: Option[Instant],
    updatedAt: Option[Instant],
)
