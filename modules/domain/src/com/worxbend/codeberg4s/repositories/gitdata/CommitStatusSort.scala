package com.worxbend.codeberg4s.repositories.gitdata

/** The orderings `GET /repos/{owner}/{repo}/commits/{ref}/statuses` accepts in its `sort` parameter.
  *
  * The five values are exactly the ones the pinned spec declares as an enum for that parameter. Forgejo's default when
  * `sort` is omitted is newest-first, which none of these names, so [[CommitStatusQuery]] omits the parameter unless a
  * caller asks for one of these.
  */
enum CommitStatusSort:

  /** Oldest first, by creation time. */
  case Oldest

  /** Most recently updated first. */
  case RecentUpdate

  /** Least recently updated first. */
  case LeastUpdate

  /** Lowest index first. */
  case LeastIndex

  /** Highest index first. */
  case HighestIndex

object CommitStatusSort:

  extension (sort: CommitStatusSort)

    /** The spelling the `sort` parameter takes — all lowercase and unseparated, as the spec's enum writes it. */
    def wireValue: String =
      sort match
        case Oldest       => "oldest"
        case RecentUpdate => "recentupdate"
        case LeastUpdate  => "leastupdate"
        case LeastIndex   => "leastindex"
        case HighestIndex => "highestindex"
