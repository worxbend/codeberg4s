package com.worxbend.codeberg4s.users

/** How `GET /users/search` orders its results — the `sort` parameter of that endpoint.
  *
  * A closed set because the spec makes it one: the parameter carries an `enum` of exactly these six values. The wire
  * spellings are lowercase and unpunctuated — `reversealphabetically`, not `reverse_alphabetically` — which is reason
  * enough for a caller never to write them by hand.
  *
  * '''There is no case for the default.''' The spec declares no default for this parameter, so what an instance does
  * with `sort` omitted is the instance's business and not something this library can name. Omitting it is therefore a
  * request in its own right, which is why [[UserSearchQuery.sort]] is an `Option`.
  */
enum UserSearchSort:

  /** Oldest account first, by registration. */
  case Oldest

  /** Newest account first, by registration. */
  case Newest

  /** By login, A to Z. */
  case Alphabetically

  /** By login, Z to A. */
  case ReverseAlphabetically

  /** Most recently updated account first. */
  case RecentUpdate

  /** Least recently updated account first. */
  case LeastUpdate

  /** The value to put in the `sort` query parameter. */
  def wireValue: String =
    this match
      case Oldest                => "oldest"
      case Newest                => "newest"
      case Alphabetically        => "alphabetically"
      case ReverseAlphabetically => "reversealphabetically"
      case RecentUpdate          => "recentupdate"
      case LeastUpdate           => "leastupdate"
