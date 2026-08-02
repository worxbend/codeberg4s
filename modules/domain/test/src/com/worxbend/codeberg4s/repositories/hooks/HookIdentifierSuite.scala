package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

/** The identifiers this group puts in request paths, and what they refuse.
  *
  * Three of the four are strings that become path segments, so their validation is a security boundary rather than a
  * formality — a value carrying a slash or a `..` would reach an endpoint the API surface never offered. The fourth is
  * a number, where the risk is confusion rather than escaping.
  */
final class HookIdentifierSuite extends FunSuite:

  // --- HookId ---------------------------------------------------------------

  test("a hook id accepts a positive row id and reports it verbatim"):
    assertEquals(orFail(HookId.from(4242L)).value, 4242L)

  test("a hook id rejects zero, which addresses nothing"):
    assertEquals(rejection(HookId.from(0L)), Some(("hookId", "must be at least 1")))

  test("a hook id rejects a negative value"):
    assertEquals(rejection(HookId.from(-1L)).map((field, _) => field), Some("hookId"))

  // --- GitHookName ----------------------------------------------------------

  test("a Git hook name accepts the names Git itself defines"):
    assertEquals(orFail(GitHookName.from("pre-receive")).value, "pre-receive")
    assertEquals(orFail(GitHookName.from("post-receive")).value, "post-receive")
    assertEquals(orFail(GitHookName.from("update")).value, "update")

  test("a Git hook name is trimmed"):
    assertEquals(orFail(GitHookName.from("  update  ")).value, "update")

  test("a Git hook name rejects a slash, which would forge a path"):
    assertEquals(rejection(GitHookName.from("../../admin")).map((field, _) => field), Some("gitHookName"))

  test("a Git hook name rejects a control character, which would corrupt the request line"):
    assertEquals(rejection(GitHookName.from("update\nX")).map((field, _) => field), Some("gitHookName"))

  test("a Git hook name rejects a blank value"):
    assertEquals(rejection(GitHookName.from("   ")), Some(("gitHookName", "must not be blank")))

  // --- WikiPageName ---------------------------------------------------------

  test("a wiki page name accepts spaces, because a page name is a title"):
    assertEquals(orFail(WikiPageName.from("Getting Started")).value, "Getting Started")

  test("a wiki page name accepts slashes, because a wiki has sub-pages"):
    val name = orFail(WikiPageName.from("Deployment/Kubernetes"))

    assertEquals(name.value, "Deployment/Kubernetes")
    assertEquals(name.segments, List("Deployment", "Kubernetes"))

  test("a single-segment page name is one segment, not a split of characters"):
    assertEquals(orFail(WikiPageName.from("Home")).segments, List("Home"))

  test("a wiki page name rejects a traversal segment, which would climb out of the wiki route"):
    assertEquals(rejection(WikiPageName.from("Home/../../admin")).map((field, _) => field), Some("pageName"))

  test("a wiki page name rejects a leading or trailing slash"):
    assertEquals(rejection(WikiPageName.from("/Home")).map((field, _) => field), Some("pageName"))
    assertEquals(rejection(WikiPageName.from("Home/")).map((field, _) => field), Some("pageName"))

  test("a wiki page name rejects an empty segment"):
    assertEquals(rejection(WikiPageName.from("Home//Sub")).map((field, _) => field), Some("pageName"))

  test("a wiki page name rejects a blank value"):
    assertEquals(rejection(WikiPageName.from("  ")), Some(("pageName", "must not be blank")))

  // --- RepositoryFlag -------------------------------------------------------

  test("a repository flag accepts an instance's own vocabulary, whatever it is"):
    assertEquals(orFail(RepositoryFlag.from("featured")).value, "featured")
    assertEquals(orFail(RepositoryFlag.from("no-mirror_2024")).value, "no-mirror_2024")

  test("a repository flag rejects a slash, which would forge a path"):
    assertEquals(rejection(RepositoryFlag.from("a/b")).map((field, _) => field), Some("repositoryFlag"))

  test("a repository flag rejects a blank value"):
    assertEquals(rejection(RepositoryFlag.from("")), Some(("repositoryFlag", "must not be blank")))

  private def rejection[A](result: Either[ValidationError, A]): Option[(String, String)] =
    result.swap.toOption.map(error => (error.field, error.message))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
