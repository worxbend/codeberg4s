package com.worxbend.codeberg4s.pulls

/** The orderings `GET /repos/{owner}/{repo}/pulls` accepts in its `sort` parameter.
  *
  * Forgejo's own seven spellings, none of which is a plain field name and two of which are run together without a
  * separator (`recentupdate`, `leastupdate`). An enum rather than a `String` for the reason [[MergeStyle]] gives: a
  * misspelling is not rejected, it is ignored, and the caller silently gets the instance's default ordering instead of
  * the one they asked for.
  *
  * There is no case for the default. Leaving [[PullRequestQuery.sort]] unset is what asks for it, and that is a
  * different request from asking for any of these — see [[PullRequestQuery]].
  */
enum PullRequestSort:

  /** Oldest first, by creation time. */
  case Oldest

  /** Most recently updated first. */
  case RecentUpdate

  /** Most recently closed first; meaningful only alongside a closed or all state filter. */
  case RecentClose

  /** Least recently updated first. */
  case LeastUpdate

  /** Most commented first. */
  case MostComment

  /** Least commented first. */
  case LeastComment

  /** Forgejo's priority ordering, which folds in pinning and assignment. */
  case Priority

  /** The value to put in the `sort` query parameter. */
  def wireValue: String =
    this match
      case Oldest       => "oldest"
      case RecentUpdate => "recentupdate"
      case RecentClose  => "recentclose"
      case LeastUpdate  => "leastupdate"
      case MostComment  => "mostcomment"
      case LeastComment => "leastcomment"
      case Priority     => "priority"
