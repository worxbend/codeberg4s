package com.worxbend.codeberg4s

import munit.FunSuite

final class ProbeSuite extends FunSuite:
  test("build skeleton runs tests"):
    assertEquals(Probe.Ok, true)
