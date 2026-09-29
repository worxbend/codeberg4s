package com.worxbend.codeberg4s.paging

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

final class PageNumberSuite extends FunSuite:

  test("accepts the first page"):
    assertEquals(PageNumber.from(1).toOption.map(_.value), Some(1))

  test("accepts a large page"):
    assertEquals(PageNumber.from(10_000).toOption.map(_.value), Some(10_000))

  test("rejects zero, which Forgejo would silently treat as page one"):
    assertEquals(field(PageNumber.from(0)), Some("pageNumber"))

  test("rejects a negative page"):
    assertEquals(field(PageNumber.from(-1)), Some("pageNumber"))

  test("First is page one"):
    assertEquals(PageNumber.First.value, 1)

  test("next advances by one"):
    assertEquals(PageNumber.First.next.value, 2)

  test("next saturates at Int.MaxValue instead of wrapping to a negative page"):
    val last = orFail(PageNumber.from(Int.MaxValue))

    assertEquals(last.next.value, Int.MaxValue)

  test("next still advances one short of the ceiling, so saturation applies only at the ceiling itself"):
    assertEquals(orFail(PageNumber.from(Int.MaxValue - 1)).next.value, Int.MaxValue)

  test("a saturated page is still a valid page number"):
    assertEquals(PageNumber.from(orFail(PageNumber.from(Int.MaxValue)).next.value).isRight, true)

  test("previous is absent on the first page"):
    assertEquals(PageNumber.First.previous.map(_.value), None)

  test("previous steps back one page"):
    assertEquals(PageNumber.First.next.previous.map(_.value), Some(1))

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)

  private def orFail(result: Either[ValidationError, PageNumber]): PageNumber =
    result match
      case Right(page) => page
      case Left(error) => fail(s"invalid page number in test setup: ${error.message}")
