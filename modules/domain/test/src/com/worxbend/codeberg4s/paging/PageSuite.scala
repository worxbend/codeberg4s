package com.worxbend.codeberg4s.paging

import munit.FunSuite

final class PageSuite extends FunSuite:

  test("map rewrites the items"):
    assertEquals(middlePage.map(_.toString).items, Vector("1", "2"))

  test("map keeps the pagination metadata untouched"):
    val mapped = middlePage.map(_.toString)

    assertEquals(mapped.params, middlePage.params)
    assertEquals(mapped.totalCount, middlePage.totalCount)
    assertEquals(mapped.nextPage.map(_.value), middlePage.nextPage.map(_.value))
    assertEquals(mapped.prevPage.map(_.value), middlePage.prevPage.map(_.value))

  test("isLast is false while the response offers a next page"):
    assert(!middlePage.isLast)

  test("isLast is true once no next page is offered"):
    assert(lastPage.isLast)

  test("a page with no items can still be the last one"):
    assert(lastPage.copy(items = Vector.empty).isLast)

  test("an absent total count is not an error"):
    assertEquals(lastPage.copy(totalCount = None).totalCount, None)

  test("size counts the items on this page, not the collection"):
    assertEquals(middlePage.size, 2)

  private def middlePage: Page[Int] = Page(
    items      = Vector(1, 2),
    params     = PageParams.First.next,
    totalCount = Some(5),
    nextPage   = Some(PageNumber.First.next.next),
    prevPage   = Some(PageNumber.First),
  )

  private def lastPage: Page[Int] = Page(
    items      = Vector(5),
    params     = PageParams.First.next.next,
    totalCount = Some(5),
    nextPage   = None,
    prevPage   = Some(PageNumber.First.next),
  )
