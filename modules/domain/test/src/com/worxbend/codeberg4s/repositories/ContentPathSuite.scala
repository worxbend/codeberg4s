package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

final class ContentPathSuite extends FunSuite:

  test("accepts a file at the repository root"):
    assertEquals(ContentPath.from("README.md").toOption.map(_.value), Some("README.md"))

  test("accepts a nested path"):
    assertEquals(ContentPath.from("models/user.go").toOption.map(_.value), Some("models/user.go"))

  test("splits a nested path into segments, because Forgejo routes it that way"):
    assertEquals(ContentPath.from("models/user.go").toOption.map(_.segments), Some(List("models", "user.go")))

  test("name is the last segment"):
    assertEquals(ContentPath.from("models/user.go").toOption.map(_.name), Some("user.go"))

  test("trims surrounding whitespace"):
    assertEquals(ContentPath.from("  README.md  ").toOption.map(_.value), Some("README.md"))

  test("rejects an empty value"):
    assertEquals(field(ContentPath.from("")), Some("filepath"))

  test("rejects a traversal, which is the reason this type validates at all"):
    assertEquals(field(ContentPath.from("../../../etc/passwd")), Some("filepath"))

  test("rejects a traversal buried mid-path"):
    assertEquals(field(ContentPath.from("models/../../secrets")), Some("filepath"))

  test("rejects a leading slash, since the path is repository-relative"):
    assertEquals(field(ContentPath.from("/etc/passwd")), Some("filepath"))

  test("rejects an empty middle segment"):
    assertEquals(field(ContentPath.from("models//user.go")), Some("filepath"))

  test("rejects an embedded control character"):
    assertEquals(field(ContentPath.from("READ\tME.md")), Some("filepath"))

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)
