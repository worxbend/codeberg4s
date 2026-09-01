package com.worxbend.codeberg4s.repositories

import munit.FunSuite

/** The instance-wide repository identifier, which every `Repository` carries and `repos.admin.byId` takes. */
final class RepositoryIdSuite extends FunSuite:

  test("a repository id accepts the smallest value Forgejo assigns"):
    assertEquals(RepositoryId.from(1L).map(_.value), Right(1L))

  test("a repository id rejects zero, which is what an unset Go field looks like"):
    assertEquals(RepositoryId.from(0L).swap.toOption.map(_.field), Some("repositoryId"))

  test("a repository id rejects a negative value"):
    assert(RepositoryId.from(-1L).isLeft, "a negative repository id must be rejected")
