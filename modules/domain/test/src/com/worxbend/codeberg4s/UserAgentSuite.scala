package com.worxbend.codeberg4s

import munit.FunSuite

final class UserAgentSuite extends FunSuite:

  test("accepts a conventional user agent"):
    assertEquals(UserAgent.from("codeberg4s/0.1.0").toOption.map(_.value), Some("codeberg4s/0.1.0"))

  test("trims surrounding whitespace"):
    assertEquals(UserAgent.from("  codeberg4s  ").toOption.map(_.value), Some("codeberg4s"))

  test("rejects a blank value"):
    assertEquals(field(UserAgent.from(" \t ")), Some("userAgent"))

  test("rejects an empty value"):
    assertEquals(field(UserAgent.from("")), Some("userAgent"))

  test("rejects a header-splitting control character"):
    assertEquals(field(UserAgent.from("codeberg4s\r\nX-Injected: 1")), Some("userAgent"))

  test("the default identifies the library"):
    assertEquals(UserAgent.Default.value, "codeberg4s")

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)
