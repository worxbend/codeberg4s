package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.{Owner, RepoName, RepositoryRequests}

/** The path prefix the repository Actions API classes of this package share.
  *
  * [[RepositoryActionApi]] addresses what Actions has done and [[RepositoryActionConfigApi]] addresses what it runs on,
  * but every path either of them builds starts at the same `/repos/{owner}/{repo}/actions` collection. Holding the
  * prefix here rather than once per companion means a change to how that collection is addressed cannot be applied to
  * one half and forgotten on the other.
  *
  * The request shapes this path is handed to live in the companion of [[com.worxbend.codeberg4s.core.CodebergRequest]],
  * shared with the whole library.
  */
private[actions] object ActionRequests:

  /** The `/repos/{owner}/{repo}/actions` prefix. */
  def actionsPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "actions"
