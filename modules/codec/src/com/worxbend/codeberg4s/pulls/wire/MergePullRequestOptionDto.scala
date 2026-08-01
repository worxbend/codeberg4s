package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.pulls.MergePullRequest

/** Forgejo's `MergePullRequestOption` request model — the body of `POST /repos/{owner}/{repo}/pulls/{index}/merge`.
  *
  * An object rather than a case class, for the reason [[CreatePullRequestOptionDto]] gives.
  *
  * ==The key names are not snake_case, and that is not a typo==
  *
  * This is the one request body in the library whose spelling breaks the convention. Forgejo's Go form binds four of
  * its fields by their '''struct field names''' rather than by a JSON tag, so the wire keys are `Do`,
  * `MergeTitleField`, `MergeMessageField` and `MergeCommitID` — capitalised, mid-word capitals and all — while the
  * remaining four are ordinary snake_case (`head_commit_id`, `delete_branch_after_merge`, `force_merge`,
  * `merge_when_checks_succeed`). The pinned spec's `MergePullRequestOption` definition lists exactly that mix, and its
  * `required` array names `Do`. Renaming any of them to look tidy produces a request Forgejo silently ignores half of.
  *
  * '''Only what the caller set is emitted''', with `Do` always present because [[MergeStyle]] has no absent case. The
  * three flags are emitted only when `true`: `false` and absent mean the same thing to Forgejo, and sending
  * `force_merge: false` would be this library asserting a default it was never told.
  */
private[codeberg4s] object MergePullRequestOptionDto:

  /** Renders `command` as the JSON body to `POST`. */
  def render(command: MergePullRequest): String =
    ujson.write(ujson.Obj.from(fields(command)))

  private def fields(command: MergePullRequest): List[(String, ujson.Value)] =
    List(
      Some("Do" -> ujson.Str(command.style.wireValue)),
      command.title.map(text       => "MergeTitleField" -> ujson.Str(text)),
      command.message.map(text     => "MergeMessageField" -> ujson.Str(text)),
      command.mergedCommit.map(sha => "MergeCommitID" -> ujson.Str(sha.value)),
      command.headCommit.map(sha   => "head_commit_id" -> ujson.Str(sha.value)),
      Option.when(command.deleteBranchAfterMerge)("delete_branch_after_merge" -> ujson.Bool(true)),
      Option.when(command.forceMerge)("force_merge"                           -> ujson.Bool(true)),
      Option.when(command.mergeWhenChecksSucceed)("merge_when_checks_succeed" -> ujson.Bool(true)),
    ).flatten
