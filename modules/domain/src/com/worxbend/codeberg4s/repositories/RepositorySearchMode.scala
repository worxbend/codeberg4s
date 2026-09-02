package com.worxbend.codeberg4s.repositories

/** The kind of repository `GET /repos/search` is asked for — the `mode` parameter of that endpoint.
  *
  * The spec declares the parameter as a plain string, but its own description names the four values Forgejo accepts:
  * "fork", "source", "mirror" and "collaborative". They are a closed set in practice, and spelling one of them wrong
  * costs a round trip, so this library states the four rather than passing a string through.
  *
  * Omitting the parameter asks for every kind, which is why [[RepositorySearchQuery.mode]] is an `Option` and there is
  * no `All` case here.
  */
enum RepositorySearchMode:

  /** Repositories that are a fork of another repository. */
  case Fork

  /** Repositories that are neither a fork nor a mirror — the original of their history. */
  case Source

  /** Repositories the instance keeps in sync with a remote it does not host. */
  case Mirror

  /** Repositories the searched-for account contributes to without owning. */
  case Collaborative

  /** The value to put in the `mode` query parameter. */
  def wireValue: String =
    this match
      case Fork          => "fork"
      case Source        => "source"
      case Mirror        => "mirror"
      case Collaborative => "collaborative"
