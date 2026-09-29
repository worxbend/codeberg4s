package com.worxbend.codeberg4s.pulls

import java.util.Locale

/** How Forgejo integrates a pull request's commits.
  *
  * One vocabulary behind two wire fields: `MergePullRequestOption.Do`, the '''only''' required property of the merge
  * request body, and `default_merge_style` on `EditRepoOption`, the style a repository falls back to when a caller
  * names none. Modelling it as an enum rather than as a `String` is not decoration: the spellings below are the
  * complete set Forgejo accepts, anything else comes back as a `405` or a `422`, and the difference between two of them
  * is the difference between rewriting a branch's history and not. A typo in a string literal would be discovered by a
  * production merge.
  *
  * ==The two fields do not accept the same set==
  *
  * `spec/swagger.v1.json` enumerates `Do` as six values. `default_merge_style` is typed as a bare `type: string` whose
  * '''description''' lists those six plus `rebase-update-only`, a style that only makes sense as a repository default:
  * it tells Forgejo to bring a branch up to date by rebasing, and the merge endpoint does not perform it. It is a
  * member here — a repository can be read or edited with it, and a vocabulary that could not spell what the instance
  * says would be the wrong vocabulary — and [[MergePullRequest.using]] refuses it with a `ValidationError`, because
  * sending it as `Do` would only be discovered from the `405`.
  *
  * A repository can forbid any of the others — `allow_merge_commits`, `allow_rebase`, `allow_rebase_explicit`,
  * `allow_squash_merge` and `allow_fast_forward_only_merge` are all fields of
  * [[com.worxbend.codeberg4s.repositories.wire.RepositoryDto]], and `golden/pull/single-open.json` shows one repository
  * with `allow_fast_forward_only_merge: false` and its fork with `true`. Asking for a style the repository forbids is a
  * `405` on a merge and a `422` on an edit, not a validation error this library can catch in advance.
  */
enum MergeStyle:

  /** A merge commit with two parents, keeping every commit of the branch. Forgejo's `merge`. */
  case Merge

  /** Replay the branch's commits onto the base and fast-forward, leaving no merge commit. Forgejo's `rebase`.
    *
    * '''Rewrites the branch's commits''' — the resulting shas differ from the ones the branch had.
    */
  case Rebase

  /** Replay the branch's commits onto the base, then record an explicit merge commit. Forgejo's `rebase-merge`.
    *
    * Rewrites the branch's commits, as [[Rebase]] does.
    */
  case RebaseMerge

  /** Collapse the whole branch into one commit on the base. Forgejo's `squash`. */
  case Squash

  /** Fast-forward the base to the head, and fail if that is not possible. Forgejo's `fast-forward-only`.
    *
    * The only style that cannot create a commit, and therefore the only one that leaves the head sha intact.
    */
  case FastForwardOnly

  /** Record a merge that already happened outside Forgejo. Forgejo's `manually-merged`.
    *
    * Changes no Git history at all: it marks the pull request merged against a commit the caller names, which is what
    * [[MergePullRequest.recordingMerge]] supplies. Forgejo rejects the request when that commit is absent.
    */
  case ManuallyMerged

  /** Only update the branch by rebasing it; no merge. Forgejo's `rebase-update-only`.
    *
    * '''A repository default, not a merge.''' It is a value of `default_merge_style` and of nothing else; see the enum
    * note for why [[MergePullRequest.using]] refuses it.
    */
  case RebaseUpdateOnly

  /** The spelling Forgejo expects in `MergePullRequestOption.Do` and in `default_merge_style`. */
  def wireValue: String =
    this match
      case Merge            => "merge"
      case Rebase           => "rebase"
      case RebaseMerge      => "rebase-merge"
      case Squash           => "squash"
      case FastForwardOnly  => "fast-forward-only"
      case ManuallyMerged   => "manually-merged"
      case RebaseUpdateOnly => "rebase-update-only"

object MergeStyle:

  /** Parses Forgejo's spelling, case-insensitively and trimming. Answers `None` for anything else. */
  def parse(value: String): Option[MergeStyle] =
    val normalized = value.trim.toLowerCase(Locale.ROOT)

    values.find(_.wireValue.contentEquals(normalized))
