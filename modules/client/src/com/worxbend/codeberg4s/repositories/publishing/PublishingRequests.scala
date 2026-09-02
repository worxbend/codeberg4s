package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.repositories.ReleaseId
import com.worxbend.codeberg4s.{Owner, RepoName, RepositoryRequests}

/** The two release path prefixes the API classes of this package share.
  *
  * [[RepositoryPublishingApi]] addresses the release record and [[ReleaseAssetApi]] addresses the files hanging off it,
  * and both start at the same `/repos/{owner}/{repo}/releases` collection. Holding the prefixes here rather than once
  * per companion means a change to how a release is addressed cannot be applied to one half and forgotten on the other.
  *
  * The request shapes these paths are handed to live in the companion of
  * [[com.worxbend.codeberg4s.core.CodebergRequest]], shared with the whole library.
  */
private[publishing] object PublishingRequests:

  /** The `/repos/{owner}/{repo}/releases` collection. */
  def releasesPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "releases"

  /** The `/repos/{owner}/{repo}/releases/{id}` prefix. */
  def releasePath(owner: Owner, name: RepoName, id: ReleaseId): List[String] =
    releasesPath(owner, name) :+ id.value.toString
