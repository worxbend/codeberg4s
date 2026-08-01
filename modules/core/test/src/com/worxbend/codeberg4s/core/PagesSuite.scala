package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize

import munit.FunSuite

final class PagesSuite extends FunSuite:

  private def responseWith(headers: (String, List[String])*): CodebergResponse =
    CodebergResponse(200, headers.toMap, "[]")

  private def page(value: Int): PageNumber =
    PageNumber.from(value).getOrElse(PageNumber.First)

  private def size(value: Int): PageSize =
    PageSize.from(value).getOrElse(PageSize.Default)

  private def linkHeader(value: String): (String, List[String]) =
    "link" -> List(value)

  test("the items and the requested window are carried through untouched"):
    val params = PageParams(page(3), size(2))
    val built  = Pages.from(responseWith(), params, Vector("a", "b"))

    assertEquals(built.items, Vector("a", "b"))
    assertEquals(built.params, params)

  test("the next page comes from the next relation"):
    val built = Pages.from(responseWith(linkHeader("<x?page=4>; rel=\"next\"")), PageParams.First, Vector(1))

    assertEquals(built.nextPage, Some(page(4)))
    assertEquals(built.isLast, false)

  test("a full page with no next relation is the last page"):
    val params = PageParams(PageNumber.First, size(50))
    val built  = Pages.from(responseWith(), params, Vector.fill(50)(1))

    assertEquals(built.nextPage, None)
    assertEquals(built.isLast, true)

  test("a short page with a next relation is not the last page — Forgejo clamps limit silently"):
    val params = PageParams(PageNumber.First, size(50))
    val built  = Pages.from(responseWith(linkHeader("<x?page=2>; rel=\"next\"")), params, Vector.fill(3)(1))

    assertEquals(built.nextPage, Some(page(2)))
    assertEquals(built.isLast, false)

  test("an empty page still reports the following page the header advertised"):
    val built = Pages.from(responseWith(linkHeader("<x?page=2>; rel=\"next\"")), PageParams.First, Vector.empty[Int])

    assertEquals(built.nextPage, Some(page(2)))

  test("the total count comes from x-total-count"):
    val built = Pages.from(responseWith("x-total-count" -> List("1589")), PageParams.First, Vector(1))

    assertEquals(built.totalCount, Some(1589))

  test("a missing x-total-count means unknown, never zero"):
    assertEquals(Pages.from(responseWith(), PageParams.First, Vector(1)).totalCount, None)

  test("the previous page comes from the prev relation when the instance sent one"):
    val built =
      Pages.from(responseWith(linkHeader("<x?page=7>; rel=\"prev\"")), PageParams(page(3), size(2)), Vector(1))

    assertEquals(built.prevPage, Some(page(7)))

  test("without a prev relation the previous page is derived from the requested window"):
    val built = Pages.from(responseWith(), PageParams(page(3), size(2)), Vector(1))

    assertEquals(built.prevPage, Some(page(2)))

  test("the first page has no previous page"):
    assertEquals(Pages.from(responseWith(), PageParams.First, Vector(1)).prevPage, None)
