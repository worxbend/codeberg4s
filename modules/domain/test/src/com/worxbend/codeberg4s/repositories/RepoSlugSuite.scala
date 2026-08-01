package com.worxbend.codeberg4s.repositories

import munit.FunSuite

final class RepoSlugSuite extends FunSuite:

  test("value renders the canonical owner/name form"):
    assertEquals(RepoSlug(owner("forgejo"), name("forgejo")).value, "forgejo/forgejo")

  test("slugs built from the same parts are equal"):
    assertEquals(RepoSlug(owner("forgejo"), name("forgejo")), RepoSlug(owner("forgejo"), name("forgejo")))

  private def owner(value: String): Owner =
    Owner.from(value) match
      case Right(parsed) => parsed
      case Left(error)   => fail(s"invalid owner in test setup: ${error.message}")

  private def name(value: String): RepoName =
    RepoName.from(value) match
      case Right(parsed) => parsed
      case Left(error)   => fail(s"invalid repository name in test setup: ${error.message}")
