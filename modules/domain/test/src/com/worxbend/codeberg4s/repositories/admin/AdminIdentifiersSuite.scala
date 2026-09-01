package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

/** The identifiers this group validates.
  *
  * Two of them are positive integers and one is a path segment; the interesting cases are the boundary and the values
  * that would forge a path, since [[MirrorName]] is interpolated into a request URI.
  * [[com.worxbend.codeberg4s.repositories.RepositoryId]] is covered by its own suite, beside the model that carries
  * one.
  */
final class AdminIdentifiersSuite extends FunSuite:

  test("a topic id reports its own field name, so a caller knows which argument was wrong"):
    assertEquals(TopicId.from(0L).swap.toOption.map(_.field), Some("topicId"))

  test("a topic id renders the number it was built from, which is what keys a cache on it"):
    assertEquals(TopicId.from(1L).map(_.value), Right(1L))
    assertEquals(TopicId.from(4815162342L).map(_.value), Right(4815162342L))

  test("an activity id reports its own field name too"):
    assertEquals(ActivityId.from(0L).swap.toOption.map(_.field), Some("activityId"))

  test("an activity id renders the number it was built from"):
    assertEquals(ActivityId.from(1L).map(_.value), Right(1L))
    assertEquals(ActivityId.from(90210L).map(_.value), Right(90210L))

  test("a mirror name accepts the generated handle Forgejo hands out"):
    assertEquals(MirrorName.from("remote_a1b2c3").map(_.value), Right("remote_a1b2c3"))

  test("a mirror name trims surrounding whitespace"):
    assertEquals(MirrorName.from("  remote_a1b2c3  ").map(_.value), Right("remote_a1b2c3"))

  test("a mirror name rejects a slash, because it becomes one path segment"):
    assertEquals(MirrorName.from("remote/../repos").swap.toOption.map(_.field), Some("mirrorName"))

  test("a mirror name rejects a control character"):
    assert(MirrorName.from("remote\nname").isLeft, "a control character must not reach the request line")

  test("a mirror name rejects a blank value"):
    assert(MirrorName.from("   ").isLeft, "a blank mirror name addresses nothing")

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

  test("a validated mirror name renders as the segment it will occupy"):
    assertEquals(orFail(MirrorName.from("remote_x")).value, "remote_x")
