package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

/** [[TeamId]] exists so a repository id cannot be handed to `GET /teams/{id}`; the bound it enforces is tested here. */
final class TeamIdSuite extends FunSuite:

  test("accepts the first row id an instance can hand out"):
    assertEquals(TeamId.from(1L).toOption.map(_.value), Some(1L))

  test("accepts a large identifier"):
    assertEquals(TeamId.from(70422L).toOption.map(_.value), Some(70422L))

  test("rejects zero, which is Forgejo's absent-identifier value rather than a team"):
    assertEquals(rejection(TeamId.from(0L)), Some(("teamId", "must be at least 1")))

  test("rejects a negative identifier"):
    assertEquals(rejection(TeamId.from(-3L)), Some(("teamId", "must be at least 1")))

  /** The field and reason of a rejection, or `None` when the value was accepted. */
  private def rejection(result: Either[ValidationError, ?]): Option[(String, String)] =
    result.swap.toOption.map(error => (error.field, error.message))
