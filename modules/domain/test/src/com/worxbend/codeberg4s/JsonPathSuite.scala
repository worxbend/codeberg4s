package com.worxbend.codeberg4s

import munit.FunSuite

final class JsonPathSuite extends FunSuite:

  test("render shows the root as a bare dollar"):
    assertEquals(JsonPath.Root.render, "$")

  test("render joins nested field names with dots"):
    assertEquals(JsonPath.of("owner", "login").render, "$.owner.login")

  test("render writes an array position in brackets"):
    assertEquals(JsonPath.Root.field("items").index(0).render, "$.items[0]")

  test("render keeps a numeric field name distinct from an array position"):
    assertEquals(JsonPath.of("0").render, "$.0")

  test("isRoot is true only for the empty path"):
    assert(JsonPath.Root.isRoot)
    assert(!JsonPath.of("items").isRoot)

  test("paths built the same way compare equal"):
    assertEquals(JsonPath.of("a", "b"), JsonPath.Root.field("a").field("b"))
