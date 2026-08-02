package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.core.CodebergRequest
import com.worxbend.codeberg4s.core.RequestBody
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName

/** The request shapes and path prefixes every sub-API of the issue group builds on.
  *
  * [[IssueApi]] predates this object and keeps its own private copies of the four builders it was written with; nothing
  * here changes what those do. The seven sub-APIs share these instead, so that `/repos/{owner}/{repo}/issues` is
  * spelled once rather than eight times and a `DELETE` with a body cannot accidentally become one without.
  *
  * Internal to this group.
  */
private[issues] object IssueRequests:

  /** A `GET`, with a query that may be empty. */
  def read(operation: String, path: List[String], query: List[(String, String)]): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Get,
      path      = path,
      query     = query,
      headers   = Nil,
      body      = None,
    )

  /** A mutating call carrying a JSON body. */
  def write(operation: String, method: HttpMethod, path: List[String], body: String): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = method,
      path      = path,
      query     = Nil,
      headers   = Nil,
      body      = Some(RequestBody.Json(body)),
    )

  /** A mutating call carrying a JSON body and a query, which only the attachment uploads need. */
  def upload(
      operation: String,
      path: List[String],
      query: List[(String, String)],
      body: RequestBody,
  ): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Post,
      path      = path,
      query     = query,
      headers   = Nil,
      body      = Some(body),
    )

  /** A `DELETE` with no body — the ordinary shape. */
  def remove(operation: String, path: List[String]): CodebergRequest =
    CodebergRequest(
      operation = operation,
      method    = HttpMethod.Delete,
      path      = path,
      query     = Nil,
      headers   = Nil,
      body      = None,
    )

  /** A `DELETE` that carries a JSON body, which reactions, blocks, dependencies and label removals all need because
    * what to remove is not in the URL. RFC 9110 permits this and defines no semantics for it; Forgejo defines its own.
    */
  def removeWithBody(operation: String, path: List[String], body: String): CodebergRequest =
    write(operation, HttpMethod.Delete, path, body)

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

  /** `/repos/{owner}/{repo}/labels/{id}`. */
  def labelPath(owner: Owner, name: RepoName, id: LabelId): List[String] =
    repoPath(owner, name) ++ List("labels", id.value.toString)

  /** `/repos/{owner}/{repo}/milestones/{id}`. */
  def milestonePath(owner: Owner, name: RepoName, id: MilestoneId): List[String] =
    repoPath(owner, name) ++ List("milestones", id.value.toString)
