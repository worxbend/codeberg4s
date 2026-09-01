package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize

import munit.FunSuite

/** The three spellings a paging window has on the wire.
  *
  * Every paged endpoint in the library renders its window through this one object, so these tests are where the wire
  * names `page`, `limit` and `per_page` are pinned. Results are asserted as ordered lists of pairs rather than as maps,
  * because [[com.worxbend.codeberg4s.core.CodebergRequest.query]] is a list: order is part of what is sent.
  */
final class PagingQuerySuite extends FunSuite:

  test("window states both parameters, so a limit is never sent without a page"):
    assertEquals(PagingQuery.window(window(2, 25)), List("page" -> "2", "limit" -> "25"))

  test("window renders the default first page as the instance default size"):
    assertEquals(PagingQuery.window(PageParams.First), List("page" -> "1", "limit" -> "30"))

  test("window writes page before limit, the order Forgejo's own Link header uses"):
    assertEquals(PagingQuery.window(window(4, 10)).map(_._1), List("page", "limit"))

  test("pageOnly drops the size, for a route that declares no size parameter"):
    assertEquals(PagingQuery.pageOnly(window(3, 25)), List("page" -> "3"))

  test("perPageWindow spells the size per_page, as the git trees route requires"):
    assertEquals(PagingQuery.perPageWindow(window(2, 25)), List("page" -> "2", "per_page" -> "25"))

  private def window(page: Int, size: Int): PageParams =
    PageParams(orFail(PageNumber.from(page)), orFail(PageSize.from(size)))

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")
