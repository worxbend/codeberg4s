package com.worxbend.codeberg4s

import munit.FunSuite

final class RepoNameSuite extends FunSuite:

  test("accepts a plain repository name"):
    assertEquals(RepoName.from("forgejo").toOption.map(_.value), Some("forgejo"))

  test("accepts the punctuation Forgejo allows in a name"):
    assertEquals(RepoName.from("code.berg-4s_v1").toOption.map(_.value), Some("code.berg-4s_v1"))

  test("trims surrounding whitespace"):
    assertEquals(RepoName.from(" forgejo ").toOption.map(_.value), Some("forgejo"))

  test("rejects an empty value"):
    assertEquals(field(RepoName.from("")), Some("repoName"))

  test("rejects a blank value"):
    assertEquals(field(RepoName.from("  ")), Some("repoName"))

  test("rejects a value containing a slash, which would forge a path"):
    assertEquals(field(RepoName.from("forgejo/issues")), Some("repoName"))

  test("rejects an embedded control character"):
    assertEquals(field(RepoName.from("forge\tjo")), Some("repoName"))

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)
