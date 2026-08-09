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

  /** The one case where dropping the regular expression changed an answer, pinned so it cannot drift back.
    *
    * `from` used to match `^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$`. In a Java regular expression `$` matches not only at
    * the end of the input but also immediately before a line terminator that ends it, and Java counts NEL (U+0085),
    * LINE SEPARATOR (U+2028) and PARAGRAPH SEPARATOR (U+2029) as line terminators. `String.trim` strips only characters
    * up to and including U+0020, so those three reached the matcher intact and were then silently swallowed by `$`:
    * `"eb6420\u0085"` came back as an accepted `"eb6420"`. U+0085 is a control character — the kind this module rejects
    * everywhere a value reaches a request — so accepting it here was a hole, not a convenience.
    */
  test("a trailing Unicode line separator is rejected rather than quietly swallowed"):
    assert(LabelColor.from("eb6420\u0085").isLeft)
    assert(LabelColor.from("eb6420\u2028").isLeft)
    assert(LabelColor.from("eb6420\u2029").isLeft)

  test("whitespace up to U+0020 is still trimmed, line separator or not"):
    assertEquals(LabelColor.from("\neb6420\t").map(_.value), Right("eb6420"))
