package com.worxbend.codeberg4s.pulls

/** How Forgejo should integrate a pull request's commits when it is merged.
  *
  * The wire value is `MergePullRequestOption.Do`, and it is the '''only''' required property of that request body.
  * Modelling it as an enum rather than as a `String` is not decoration: the six spellings below are the complete set
  * Forgejo accepts, a seventh comes back as a `405`, and the difference between two of them is the difference between
  * rewriting a branch's history and not. A typo in a string literal would be discovered by a production merge.
  *
  * A repository can forbid any of these — `allow_merge_commits`, `allow_rebase`, `allow_rebase_explicit`,
  * `allow_squash_merge` and `allow_fast_forward_only_merge` are all fields of
  * [[com.worxbend.codeberg4s.repositories.wire.RepositoryDto]], and `golden/pull/single-open.json` shows one repository
  * with `allow_fast_forward_only_merge: false` and its fork with `true`. Asking for a style the repository forbids is a
  * `405`, not a validation error this library can catch in advance.
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

  /** The value to put in `MergePullRequestOption.Do`. */
  def wireValue: String =
    this match
      case Merge           => "merge"
      case Rebase          => "rebase"
      case RebaseMerge     => "rebase-merge"
      case Squash          => "squash"
      case FastForwardOnly => "fast-forward-only"
      case ManuallyMerged  => "manually-merged"
