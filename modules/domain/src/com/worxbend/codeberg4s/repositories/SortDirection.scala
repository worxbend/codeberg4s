package com.worxbend.codeberg4s.repositories

/** Which way round a sorted listing runs — the `order` parameter of `GET /repos/search`.
  *
  * The spec enumerates exactly `asc` and `desc`, and documents two things about the parameter that are easy to get
  * wrong. Its default is `asc`, and it is '''ignored entirely unless a `sort` is named''' — so asking for descending
  * order without an ordering attribute is not an error, it is a request that silently comes back in the instance's own
  * order. [[RepositorySearchQuery.inOrder]] therefore reads as a modifier of
  * [[RepositorySearchQuery.sortedBy]] rather than as a filter of its own.
  *
  * ==Why it lives in this package==
  *
  * Several other Forgejo endpoints declare the same two-value `order`, so this concept is not repository-specific.
  * `docs/LEDGER.md` makes the wave that first needs a shared model its owner, and repository search is the first
  * operation in this library to send `order` at all. If another group later needs it, this type moves to the root
  * package and this one imports it; what must not happen is a second copy.
  */
enum SortDirection:

  /** Smallest, earliest or alphabetically first result first — `asc`, the instance's default. */
  case Ascending

  /** Largest, latest or alphabetically last result first — `desc`. */
  case Descending

  /** The value to put in the `order` query parameter. */
  def wireValue: String =
    this match
      case Ascending  => "asc"
      case Descending => "desc"
