package com.worxbend.codeberg4s.pulls

import com.worxbend.codeberg4s.{Owner, RepoName, RepositoryRequests}

/** The path prefixes the pull-request API classes of this package share.
  *
  * [[PullRequestApi]] addresses the proposed change, [[PullRequestReviewApi]] the verdicts on it and
  * [[ReviewCommentApi]] the remarks inside one verdict, but every path any of them builds starts at the same
  * `/repos/{owner}/{repo}/pulls` collection. Holding the prefixes here rather than once per companion means a change to
  * how a pull request or a review is addressed cannot be applied to one class and forgotten in the others.
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

  /** The `/repos/{owner}/{repo}/pulls/{index}/reviews` collection. */
  def reviewsPath(owner: Owner, name: RepoName, number: PullRequestNumber): List[String] =
    pullPath(owner, name, number) :+ "reviews"

  /** The `/repos/{owner}/{repo}/pulls/{index}/reviews/{id}` prefix. */
  def reviewPath(owner: Owner, name: RepoName, number: PullRequestNumber, review: ReviewId): List[String] =
    reviewsPath(owner, name, number) :+ review.value.toString
