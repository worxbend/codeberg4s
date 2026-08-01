package com.worxbend.codeberg4s

import munit.FunSuite

final class HttpMethodSuite extends FunSuite:

  test("wireName renders the uppercase name for every method"):
    val rendered = HttpMethod.values.toList.map(_.wireName)
    assertEquals(rendered, List("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"))

  test("only GET and HEAD are safe"):
    val safe = HttpMethod.values.toList.filter(_.isSafe)
    assertEquals(safe, List(HttpMethod.Get, HttpMethod.Head))
