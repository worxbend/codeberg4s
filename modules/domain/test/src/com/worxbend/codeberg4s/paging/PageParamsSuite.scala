package com.worxbend.codeberg4s.paging

import munit.FunSuite

final class PageParamsSuite extends FunSuite:

  test("First asks for page one"):
    assertEquals(PageParams.First.page.value, 1)

  test("First asks for the default page size"):
    assertEquals(PageParams.First.size.value, PageSize.Default.value)

  test("next advances the page and keeps the size"):
    val advanced = PageParams.First.next

    assertEquals(advanced.page.value, 2)
    assertEquals(advanced.size.value, PageParams.First.size.value)

  test("at moves to an explicit page and keeps the size"):
    val moved = PageParams.First.at(PageNumber.First.next.next)

    assertEquals(moved.page.value, 3)
    assertEquals(moved.size.value, PageParams.First.size.value)
