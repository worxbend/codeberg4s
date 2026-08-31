package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName

/** The path prefixes every sub-API of the issue group builds on, so that `/repos/{owner}/{repo}/issues` is spelled once
  * rather than eight times.
  *
  * The request shapes these paths are handed to live in the companion of
  * [[com.worxbend.codeberg4s.core.CodebergRequest]], shared with the whole library.
  *
  * Internal to this group.
  */
private[issues] object IssueRequests:

  /** `/repos/{owner}/{repo}`. */
  def repoPath(owner: Owner, name: RepoName): List[String] =
    List("repos", owner.value, name.value)

  /** `/repos/{owner}/{repo}/issues`. */
  def issuesPath(owner: Owner, name: RepoName): List[String] =
    repoPath(owner, name) :+ "issues"

  /** `/repos/{owner}/{repo}/issues/{index}`. */
  def issuePath(owner: Owner, name: RepoName, number: IssueNumber): List[String] =
    issuesPath(owner, name) :+ number.value.toString

  /** `/repos/{owner}/{repo}/issues/comments/{id}` — no issue number, because comment ids are instance-wide. */
  def commentPath(owner: Owner, name: RepoName, id: CommentId): List[String] =
    issuesPath(owner, name) ++ List("comments", id.value.toString)

  /** `/repos/{owner}/{repo}/labels` — the labels a repository offers, not the ones on any one issue. */
  def labelsPath(owner: Owner, name: RepoName): List[String] =
    repoPath(owner, name) :+ "labels"

  /** `/repos/{owner}/{repo}/labels/{id}`. */
  def labelPath(owner: Owner, name: RepoName, id: LabelId): List[String] =
    labelsPath(owner, name) :+ id.value.toString

  /** `/repos/{owner}/{repo}/milestones`. */
  def milestonesPath(owner: Owner, name: RepoName): List[String] =
    repoPath(owner, name) :+ "milestones"

  /** `/repos/{owner}/{repo}/milestones/{id}`. */
  def milestonePath(owner: Owner, name: RepoName, id: MilestoneId): List[String] =
    milestonesPath(owner, name) :+ id.value.toString
