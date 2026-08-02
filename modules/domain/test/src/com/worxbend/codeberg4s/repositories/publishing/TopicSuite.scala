package com.worxbend.codeberg4s.repositories.publishing

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

/** [[Topic]]'s job is to guarantee one safe URI path segment, and to guarantee nothing else. */
final class TopicSuite extends FunSuite:

  test("the four topics of the golden capture are all accepted"):
    val captured = List("forge", "forgejo", "git", "self-hosted")

    assertEquals(captured.map(name => accepted(name).value), captured)

  test("surrounding whitespace is trimmed"):
    assertEquals(accepted("  forgejo  ").value, "forgejo")

  test("a blank topic is rejected"):
    assertEquals(rejected("   ").field, "topic")

  test("a topic containing a slash is rejected, because it would forge a path"):
    assertEquals(rejected("forge/jo").message, "must not contain a slash")

  test("a topic containing a control character is rejected"):
    assertEquals(rejected("forge\njo").message, "must not contain a control character")

  test("a mixed-case topic is accepted verbatim — predicting Forgejo's own grammar is not this type's job"):
    assertEquals(accepted("SelfHosted").value, "SelfHosted")

  private def accepted(value: String): Topic =
    Topic.from(value) match
      case Right(topic) => topic
      case Left(error)  => fail(s"expected $value to be accepted, got ${error.message}")

  private def rejected(value: String): ValidationError =
    Topic.from(value) match
      case Left(error)  => error
      case Right(topic) => fail(s"expected $value to be rejected, got ${topic.value}")
