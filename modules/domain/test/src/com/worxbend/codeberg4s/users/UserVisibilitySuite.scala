package com.worxbend.codeberg4s.users

import munit.FunSuite

/** [[UserVisibility]] is descriptive rather than load-bearing, so an unknown value must cost the caller nothing more
  * than the field itself — a fourth Forgejo visibility must not make an otherwise good user object undecodable.
  */
final class UserVisibilitySuite extends FunSuite:

  test("public is spelled public — the value every golden user fixture reports"):
    assertEquals(UserVisibility.Public.wireName, "public")

  test("limited is spelled limited"):
    assertEquals(UserVisibility.Limited.wireName, "limited")

  test("private is spelled private"):
    assertEquals(UserVisibility.Private.wireName, "private")

  test("the enum is exactly the three values Forgejo's VisibleType declares"):
    assertEquals(UserVisibility.values.toList.map(_.wireName).sorted, List("limited", "private", "public"))

  test("every visibility round-trips from its own wire spelling"):
    val roundTripped = UserVisibility.values.toList.map(level => UserVisibility.parse(level.wireName))

    assertEquals(roundTripped, UserVisibility.values.toList.map(Some.apply))

  test("the value golden/user/user-single.json carries parses to the case it names"):
    assertEquals(UserVisibility.parse("public"), Some(UserVisibility.Public))

  test("parsing is case-insensitive and trims, because only observation claims the casing"):
    assertEquals(UserVisibility.parse(" Public "), Some(UserVisibility.Public))
    assertEquals(UserVisibility.parse("PRIVATE"), Some(UserVisibility.Private))

  test("a fourth visibility is absent rather than a failure, so the rest of the user still decodes"):
    assertEquals(UserVisibility.parse("unlisted"), None)

  test("a misspelling is absent rather than being rounded to the case it resembles"):
    assertEquals(UserVisibility.parse("publik"), None)

  test("a blank visibility is absent"):
    assertEquals(UserVisibility.parse("   "), None)
