package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

final class OwnerSuite extends FunSuite:

  test("accepts a plain owner"):
    assertEquals(Owner.from("forgejo").toOption.map(_.value), Some("forgejo"))

  test("trims surrounding whitespace"):
    assertEquals(Owner.from("  forgejo  ").toOption.map(_.value), Some("forgejo"))

  test("rejects an empty value"):
    assertEquals(field(Owner.from("")), Some("owner"))

  test("rejects a blank value"):
    assertEquals(field(Owner.from("   ")), Some("owner"))

  test("rejects a value containing a slash, which would forge a path"):
    assertEquals(field(Owner.from("forgejo/forgejo")), Some("owner"))

  test("rejects a traversal attempt"):
    assertEquals(field(Owner.from("../../admin/users")), Some("owner"))

  test("rejects an embedded control character"):
    assertEquals(field(Owner.from("forge\njo")), Some("owner"))

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)
