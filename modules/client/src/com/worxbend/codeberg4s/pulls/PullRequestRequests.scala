package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.{Owner, RepoName, RepositoryRequests}

/** The two path prefixes the pull-request API classes of this package share.
  *
  * [[PullRequestApi]] addresses the proposed change and [[PullRequestReviewApi]] addresses the verdicts on it, but
  * every path either of them builds starts at the same `/repos/{owner}/{repo}/pulls` collection. Holding the prefixes
  * here rather than once per companion means a change to how a pull request is addressed cannot be applied to one half
  * and forgotten on the other.
  *
  * The request shapes these paths are handed to live in the companion of
  * [[com.worxbend.codeberg4s.core.CodebergRequest]], shared with the whole library.
  */
private[pulls] object PullRequestRequests:

  /** The `/repos/{owner}/{repo}/pulls` collection. */
  def pullsPath(owner: Owner, name: RepoName): List[String] =
    RepositoryRequests.repositoryPath(owner, name) :+ "pulls"

  /** The `/repos/{owner}/{repo}/pulls/{index}` prefix.
    *
    * `index` is the per-repository [[PullRequestNumber]], never [[PullRequest.id]] — the instance-wide row id addresses
    * nothing under this path.
    */
  def pullPath(owner: Owner, name: RepoName, number: PullRequestNumber): List[String] =
    pullsPath(owner, name) :+ number.value.toString
