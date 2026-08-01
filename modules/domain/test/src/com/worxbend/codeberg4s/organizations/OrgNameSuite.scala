package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

/** [[OrgName]] is a security boundary before it is a convenience, so each rejection is tested on its own. */
final class OrgNameSuite extends FunSuite:

  test("accepts a plain handle"):
    assertEquals(OrgName.from("forgejo").toOption.map(_.value), Some("forgejo"))

  test("trims surrounding whitespace"):
    assertEquals(OrgName.from("  forgejo\t").toOption.map(_.value), Some("forgejo"))

  test("accepts the punctuation-only names GET /orgs actually returns"):
    val captured = List("_CYBER_STONES_", "-_", "-_-")

    assertEquals(captured.flatMap(name => OrgName.from(name).toOption.map(_.value)), captured)

  test("rejects an empty value"):
    assertEquals(rejection(OrgName.from("")), Some(("orgName", "must not be blank")))

  test("rejects a blank value"):
    assertEquals(rejection(OrgName.from("   ")), Some(("orgName", "must not be blank")))

  test("rejects a value containing a slash, which would forge a path"):
    assertEquals(rejection(OrgName.from("forgejo/teams")), Some(("orgName", "must not contain a slash")))

  test("rejects a traversal attempt"):
    assertEquals(rejection(OrgName.from("../../admin/users")), Some(("orgName", "must not contain a slash")))

  test("rejects an embedded control character, which would corrupt the request line"):
    assertEquals(rejection(OrgName.from("for\ngejo")), Some(("orgName", "must not contain a control character")))

  /** The field and reason of a rejection, or `None` when the value was accepted. */
  private def rejection(result: Either[ValidationError, ?]): Option[(String, String)] =
    result.swap.toOption.map(error => (error.field, error.message))
