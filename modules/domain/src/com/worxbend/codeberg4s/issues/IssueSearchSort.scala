package com.worxbend.codeberg4s.issues

/** The orderings `GET /repos/issues/search` offers.
  *
  * A closed set because the spec makes it one: the `sort` parameter carries an `enum` of exactly these nine values,
  * with `latest` as the declared default. Sending a tenth is a `422`, so an enum here turns a round trip into a compile
  * error.
  *
  * The wire spellings are lowercase and unpunctuated — `recentupdate`, not `recent_update` — which is a good enough
  * reason on its own for a caller never to write them by hand.
  */
enum IssueSearchSort:

  /** Best match for the `q` term. Meaningless without one. */
  case Relevance

  /** Newest first, by creation. The instance's default. */
  case Latest

  /** Oldest first, by creation. */
  case Oldest

  /** Most recently updated first. */
  case RecentUpdate

  /** Least recently updated first. */
  case LeastUpdate

  /** Most commented first. */
  case MostComment

  /** Least commented first. */
  case LeastComment

  /** Nearest deadline first. */
  case NearDueDate

  /** Furthest deadline first. */
  case FarDueDate

  /** The value to put in the `sort` query parameter. */
  def wireValue: String =
    this match
      case Relevance    => "relevance"
      case Latest       => "latest"
      case Oldest       => "oldest"
      case RecentUpdate => "recentupdate"
      case LeastUpdate  => "leastupdate"
      case MostComment  => "mostcomment"
      case LeastComment => "leastcomment"
      case NearDueDate  => "nearduedate"
      case FarDueDate   => "farduedate"
