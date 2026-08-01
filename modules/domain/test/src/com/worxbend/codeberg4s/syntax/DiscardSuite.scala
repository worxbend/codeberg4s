package com.worxbend.codeberg4s.syntax

import munit.FunSuite

final class DiscardSuite extends FunSuite:

  test("discard turns a value-returning call into a statement"):
    val builder = StringBuilder()

    builder.append("first").discard
    builder.append("second").discard

    assertEquals(builder.toString, "firstsecond")

  test("discard evaluates its receiver exactly once"):
    val builder = StringBuilder()

    builder.append("once").discard

    assertEquals(builder.toString, "once")
