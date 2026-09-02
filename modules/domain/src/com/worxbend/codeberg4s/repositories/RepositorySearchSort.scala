package com.worxbend.codeberg4s.repositories

/** The attribute `GET /repos/search` orders its results by — the `sort` parameter of that endpoint.
  *
  * A closed set because the spec makes it one: the parameter carries an `enum` of exactly these nine values, and
  * Forgejo answers a tenth with a `422`. An enum here turns that round trip into a compile error.
  *
  * '''There is no case for the default.''' The parameter is optional and its documented default is `alpha`, but
  * omitting it and sending `alpha` are still two different requests to make: a caller who names an ordering pins it
  * against a future change of the instance's default, and a caller who omits it says the order does not matter. That is
  * why [[RepositorySearchQuery.sort]] is an `Option` rather than this enum carrying a `Default` case.
  *
  * The wire spellings are lowercase and mostly unpunctuated, with two exceptions — `git_size` and `lfs_size` — which is
  * reason enough for a caller never to write them by hand.
  *
  * @see
  *   [[SortDirection]], which the same endpoint reads only when a sort is named
  */
enum RepositorySearchSort:

  /** Alphabetically by name. The instance's documented default. */
  case Alpha

  /** By creation time. */
  case Created

  /** By last update. */
  case Updated

  /** By total repository size on disk — git data and LFS objects together. */
  case Size

  /** By the size of the git data alone. */
  case GitSize

  /** By the size of the LFS objects alone. */
  case LfsSize

  /** By the repository's instance-wide id, which is creation order by another name. */
  case Id

  /** By how many accounts have starred the repository. */
  case Stars

  /** By how many forks the repository has. */
  case Forks

  /** The value to put in the `sort` query parameter. */
  def wireValue: String =
    this match
      case Alpha   => "alpha"
      case Created => "created"
      case Updated => "updated"
      case Size    => "size"
      case GitSize => "git_size"
      case LfsSize => "lfs_size"
      case Id      => "id"
      case Stars   => "stars"
      case Forks   => "forks"
