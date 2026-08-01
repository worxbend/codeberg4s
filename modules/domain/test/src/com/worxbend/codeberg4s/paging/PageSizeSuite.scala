package com.worxbend.codeberg4s.paging

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

final class PageSizeSuite extends FunSuite:

  test("accepts the smallest page Forgejo serves"):
    assertEquals(PageSize.from(1).toOption.map(_.value), Some(1))

  test("accepts the Forgejo cap"):
    assertEquals(PageSize.from(50).toOption.map(_.value), Some(50))

  test("rejects zero"):
    assertEquals(field(PageSize.from(0)), Some("pageSize"))

  test("rejects a negative size"):
    assertEquals(field(PageSize.from(-10)), Some("pageSize"))

  test("rejects a size above the Forgejo cap, which the server would silently clamp"):
    assertEquals(field(PageSize.from(51)), Some("pageSize"))

  test("Min, Max and Default match the Forgejo contract"):
    assertEquals(PageSize.Min.value, 1)
    assertEquals(PageSize.Max.value, 50)
    assertEquals(PageSize.Default.value, 30)

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)
