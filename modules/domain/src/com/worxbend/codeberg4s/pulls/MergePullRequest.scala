package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.repositories.CommitSha

/** Everything `POST /repos/{owner}/{repo}/pulls/{index}/merge` may be told, as one value.
  *
  * This is the request behind the most destructive operation in this library, and the type is shaped accordingly.
  *
  * '''The style is mandatory and is not a `String`.''' [[MergeStyle]] is the only argument [[MergePullRequest.using]]
  * takes, because `MergePullRequestOption.Do` is the only property Forgejo declares as required, and the difference
  * between two of its six values is the difference between rewriting a branch's history and not.
  *
  * '''Every flag is a named method, not a Boolean parameter.''' `merge(pull, force = true, delete = true)` makes a
  * reader reconstruct which `true` was which, and one of them deletes a branch. `SCALA_CODE_STYLE.md` bans that shape
  * outright; here it would be a footgun as well as a style violation.
  *
  * ==Making a retry safe==
  *
  * [[expecting]] is the one thing that makes repeating this call safe, and it is worth using deliberately. It sends
  * `head_commit_id`, which Forgejo compares against the branch's actual head and refuses the merge if it has moved — so
  * a merge sent twice after a timeout cannot merge a commit the caller never saw. Without it, a repeat merges whatever
  * the head has since become. [[com.worxbend.codeberg4s.pulls.PullRequestApi.merge]] never retries by itself for
  * exactly this reason.
  *
  * @param style
  *   how to integrate the commits; see [[MergeStyle]]
  * @param title
  *   the merge commit's subject, absent to let Forgejo compose one. Ignored by [[MergeStyle.FastForwardOnly]], which
  *   creates no commit
  * @param message
  *   the merge commit's body, same caveat
  * @param headCommit
  *   the head the caller believes it is merging; see the note above
  * @param mergedCommit
  *   the commit that already holds the merge, for [[MergeStyle.ManuallyMerged]] only. Forgejo's `MergeCommitID`
  * @param deleteBranchAfterMerge
  *   whether to delete the head branch once the merge lands. `false` unless [[deletingSourceBranch]] was called —
  *   deleting someone's branch is not a default this library picks
  * @param forceMerge
  *   whether to merge past failing status checks and unsatisfied review requirements. Requires the credentials to be
  *   allowed to do so, and is refused with a `405` otherwise
  * @param mergeWhenChecksSucceed
  *   whether to schedule the merge instead of performing it. Forgejo answers such a request with a success and merges
  *   later, so a `200` from this variant does '''not''' mean the pull request is merged
  */
final case class MergePullRequest(
    style: MergeStyle,
    title: Option[String],
    message: Option[String],
    headCommit: Option[CommitSha],
    mergedCommit: Option[CommitSha],
    deleteBranchAfterMerge: Boolean,
    forceMerge: Boolean,
    mergeWhenChecksSucceed: Boolean,
):

  /** Sets the merge commit's subject line. */
  def withTitle(text: String): MergePullRequest = copy(title = Some(text))

  /** Sets the merge commit's body. */
  def withMessage(text: String): MergePullRequest = copy(message = Some(text))

  /** Refuses the merge unless the head branch is still at `sha` — the safety catch described in the class note. */
  def expecting(sha: CommitSha): MergePullRequest = copy(headCommit = Some(sha))

  /** Names the commit that already contains the merge, for [[MergeStyle.ManuallyMerged]]. */
  def recordingMerge(sha: CommitSha): MergePullRequest = copy(mergedCommit = Some(sha))

  /** Deletes the head branch once the merge lands. Irreversible on the instance's side. */
  def deletingSourceBranch: MergePullRequest = copy(deleteBranchAfterMerge = true)

  /** Merges past failing checks and unmet review requirements, if the credentials may. */
  def forcing: MergePullRequest = copy(forceMerge = true)

  /** Schedules the merge for when the checks pass rather than performing it now; see the field note. */
  def whenChecksSucceed: MergePullRequest = copy(mergeWhenChecksSucceed = true)

object MergePullRequest:

  /** Starts a merge command from the only thing Forgejo insists on.
    *
    * Total rather than an `Either`: [[MergeStyle]] is a closed set, so there is nothing left to reject. Whether the
    * repository '''permits''' that style is a server-side question — see [[MergeStyle]] — and comes back as a `405`.
    */
  def using(style: MergeStyle): MergePullRequest =
    MergePullRequest(
      style                  = style,
      title                  = None,
      message                = None,
      headCommit             = None,
      mergedCommit           = None,
      deleteBranchAfterMerge = false,
      forceMerge             = false,
      mergeWhenChecksSucceed = false,
    )
