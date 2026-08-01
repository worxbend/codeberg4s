package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

final class TagNameSuite extends FunSuite:

  test("accepts a version tag"):
    assertEquals(TagName.from("v16.0.2").toOption.map(_.value), Some("v16.0.2"))

  test("accepts a slashed tag, which Git allows in a ref name"):
    assertEquals(TagName.from("release/2026-08").toOption.map(_.segments), Some(List("release", "2026-08")))

  test("rejects an empty value"):
    assertEquals(field(TagName.from("  ")), Some("tag"))

  test("rejects a traversal"):
    assertEquals(field(TagName.from("../../admin")), Some("tag"))

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)
