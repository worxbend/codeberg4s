package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ClientSuiteHarness
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName

import munit.FunSuite

/** The fixtures the issue group's suites share, on top of the module-wide [[ClientSuiteHarness]].
  *
  * The stub backend, the pipeline and the request assertions live in [[ClientSuiteHarness]], which every API suite in
  * this module mixes in. What is left here is only what is specific to the issue surface: the repository, the issue and
  * the comment every suite in the group addresses, and the `404` body they stub.
  */
trait IssueLaneHarness extends ClientSuiteHarness:
  self: FunSuite =>

  /** The repository owner every suite uses. */
  val Handle: Owner = orFail(Owner.from("Codeberg"))

  /** The repository every suite uses. */
  val Name: RepoName = orFail(RepoName.from("Community"))

  /** The issue every suite addresses, matching `golden/issue/single.json`. */
  val Number: IssueNumber = orFail(IssueNumber.from(2966L))

  /** The comment every suite addresses, matching the first element of `golden/issue/comments-list.json`. */
  val CommentRef: CommentId = orFail(CommentId.from(20366420L))

/** The error body the issue group's suites share. */
object IssueLaneHarness:

  /** A `404` shaped like `golden/error/404-repo-not-found.json`: a Go symbol for a message, and the useful text in
    * `errors`.
    */
  val NotFoundBody: String =
    """{"message":"GetIssueByIndex","url":"https://codeberg.org/api/swagger","errors":["issue does not exist"]}"""
