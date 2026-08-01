package com.worxbend.codeberg4s.users

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

/** [[Username]] is a security boundary before it is a convenience, so each rejection is tested on its own. */
final class UsernameSuite extends FunSuite:

  test("accepts a plain handle"):
    assertEquals(Username.from("earl-warren").toOption.map(_.value), Some("earl-warren"))

  test("trims surrounding whitespace"):
    assertEquals(Username.from("  forgejo\t").toOption.map(_.value), Some("forgejo"))

  test("rejects an empty value"):
    assertEquals(rejection(Username.from("")), Some(("username", "must not be blank")))

  test("rejects a blank value"):
    assertEquals(rejection(Username.from("   ")), Some(("username", "must not be blank")))

  test("rejects a value containing a slash, which would forge a path"):
    assertEquals(rejection(Username.from("earl/warren")), Some(("username", "must not contain a slash")))

  test("rejects a traversal attempt"):
    assertEquals(rejection(Username.from("../../admin/users")), Some(("username", "must not contain a slash")))

  test("rejects an embedded control character, which would corrupt the request line"):
    assertEquals(rejection(Username.from("earl\nwarren")), Some(("username", "must not contain a control character")))

  /** The field and reason of a rejection, or `None` when the value was accepted. */
  private def rejection(result: Either[ValidationError, ?]): Option[(String, String)] =
    result.swap.toOption.map(error => (error.field, error.message))
