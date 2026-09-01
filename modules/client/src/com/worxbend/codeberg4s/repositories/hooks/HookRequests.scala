package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.{Owner, RepoName}

/** The path prefix every route in this package shares, so that `/repos/{owner}/{repo}` is spelled once rather than
  * four times.
  *
  * The request shapes these paths are handed to live in the companion of
  * [[com.worxbend.codeberg4s.core.CodebergRequest]], shared with the whole library.
  *
  * Internal to this group.
  */
private[hooks] object HookRequests:

  /** `/repos/{owner}/{repo}`. */
  def repositoryPath(owner: Owner, name: RepoName): List[String] =
    List("repos", owner.value, name.value)
