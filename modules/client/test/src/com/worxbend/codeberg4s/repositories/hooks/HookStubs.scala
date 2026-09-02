package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.ClientSuiteHarness
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName

import sttp.model.Header

import munit.FunSuite

/** The fixtures the four suites of this package share, on top of the module-wide [[ClientSuiteHarness]].
  *
  * The stub backend, the pipeline and the request assertions live in [[ClientSuiteHarness]], which every API suite in
  * this module mixes in. What is left here is only what is specific to the hook surface: the repository every suite
  * addresses, the URI prefix its requests share, the paging headers these routes return, and the error body they stub.
  */
trait HookStubs extends ClientSuiteHarness:
  self: FunSuite =>

  /** The owner every suite addresses. */
  val Handle: Owner = orFail(Owner.from("Codeberg"))

  /** The repository every suite addresses. */
  val Name: RepoName = orFail(RepoName.from("Community"))

  /** The URI prefix every request in this package is expected to share. */
  val Repository: String = s"$Root/repos/Codeberg/Community"

  /** The paging headers `docs/HAZARDS.md` §5 captured verbatim, with the host swapped for the stub's. */
  def pagedHeaders(total: Int, next: String): List[Header] =
    List(
      Header("X-Total-Count", total.toString),
      Header("Link", s"""<$next>; rel="next""""),
    )

/** The response bodies the hook group's suites share. */
object HookStubs:

  /** A 404 shaped like `golden/error/404-repo-not-found.json`: a Go symbol for a message, and the useful text in
    * `errors`.
    */
  val NotFoundBody: String =
    """{"message":"GetRepositoryByOwnerAndName","url":"https://codeberg.org/api/swagger","errors":["repo not found"]}"""
