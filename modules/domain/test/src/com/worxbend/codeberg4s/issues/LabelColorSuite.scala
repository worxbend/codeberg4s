package com.worxbend.codeberg4s.issues

import munit.FunSuite

/** [[LabelColor]] normalises the two spellings Forgejo uses for the same colour. */
final class LabelColorSuite extends FunSuite:

  test("a six-digit colour without a hash is accepted verbatim — the form every fixture returns"):
    assertEquals(LabelColor.from("eb6420").map(_.value), Right("eb6420"))

  test("a leading hash is dropped, so the documented input form round-trips to the returned form"):
    assertEquals(LabelColor.from("#eb6420").map(_.value), Right("eb6420"))

  test("digits are lowercased, so two spellings of one colour compare equal"):
    assertEquals(LabelColor.from("#EB6420"), LabelColor.from("eb6420"))

  test("three-digit shorthand is accepted and left as three digits"):
    assertEquals(LabelColor.from("#abc").map(_.value), Right("abc"))

  test("the hashed rendering is what a create request sends"):
    assertEquals(LabelColor.from("ee0701").map(_.hashed), Right("#ee0701"))

  test("surrounding whitespace is trimmed"):
    assertEquals(LabelColor.from("  ee0701 ").map(_.value), Right("ee0701"))

  test("a value that is not hexadecimal is rejected on the labelColor field"):
    assertEquals(LabelColor.from("cornflower").left.map(_.field), Left("labelColor"))

  test("a colour of the wrong length is rejected"):
    assert(LabelColor.from("#eb642").isLeft)
    assert(LabelColor.from("#eb64200").isLeft)

  test("an empty colour is rejected"):
    assert(LabelColor.from("").isLeft)
