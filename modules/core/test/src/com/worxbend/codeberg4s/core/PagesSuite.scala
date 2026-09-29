package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize

import munit.FunSuite

final class PagesSuite extends FunSuite:

  private def responseWith(headers: (String, List[String])*): CodebergResponse =
    CodebergResponse(200, headers.toMap, ResponseBody.utf8("[]"))

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

  // --- next page synthesised from the total, when no Link relation was offered -------

  test("without a Link header, a total larger than the items seen so far offers the next page"):
    // The Forgejo handlers for hooks, trees, wiki revisions, topics and issue
    // dependencies set X-Total-Count and never Link; before the total was read,
    // every one of them reported page one as the last page.
    val built = Pages.from(responseWith("x-total-count" -> List("7")), PageParams.First, Vector(1, 2, 3))

    assertEquals(built.nextPage, Some(page(2)))
    assertEquals(built.isLast, false)

  test("the synthesised next page is derived from the items served, not from the limit requested"):
    // Fifty asked for, thirty served: the instance clamped. Page three of a
    // hundred has served ninety, so a fourth page is offered; the requested
    // limit would have said three pages of fifty cover everything and stopped.
    val params = PageParams(page(3), size(50))
    val built  = Pages.from(responseWith("x-total-count" -> List("100")), params, Vector.fill(30)(1))

    assertEquals(built.nextPage, Some(page(4)))

  test("a page whose items reach the total exactly is the last page"):
    val params = PageParams(page(2), size(30))
    val built  = Pages.from(responseWith("x-total-count" -> List("60")), params, Vector.fill(30)(1))

    assertEquals(built.nextPage, None)
    assertEquals(built.isLast, true)

  test("a short last page under a clamped limit offers one more page, which is the safe direction to err in"):
    // Page four of a hundred served the last ten. Four times ten is forty, which
    // is short of a hundred, so the arithmetic cannot tell this page from a
    // clamped middle page and offers a fifth; that one comes back empty and
    // ends the walk. One spare request, never a truncated collection.
    val params = PageParams(page(4), size(50))
    val built  = Pages.from(responseWith("x-total-count" -> List("100")), params, Vector.fill(10)(1))

    assertEquals(built.nextPage, Some(page(5)))

  test("an empty page never offers a next page, whatever the total says"):
    val built = Pages.from(responseWith("x-total-count" -> List("100")), PageParams(page(5), size(50)), Vector.empty[Int])

    assertEquals(built.nextPage, None)

  test("a page with no total and no Link header is the last page, however full"):
    assertEquals(Pages.from(responseWith(), PageParams.First, Vector.fill(50)(1)).nextPage, None)

  test("a Link header carrying any relation is authoritative, even when the total would say otherwise"):
    // A last page from an instance that sends Link: rel="prev" is there,
    // rel="next" is not. The total is stale or the page size is odd; either
    // way the server said the collection ends here, and it is believed.
    val headers = List(linkHeader("<x?page=1>; rel=\"prev\""), "x-total-count" -> List("100"))
    val built   = Pages.from(responseWith(headers*), PageParams(page(2), size(50)), Vector.fill(3)(1))

    assertEquals(built.nextPage, None)

  test("an unreadable Link header is treated as absent, so the total decides"):
    val headers = List(linkHeader("<<<;;;>>>"), "x-total-count" -> List("100"))
    val built   = Pages.from(responseWith(headers*), PageParams.First, Vector.fill(30)(1))

    assertEquals(built.nextPage, Some(page(2)))

  // --- envelope listings, whose total travels in the body ----------------------------

  test("an envelope's own total stands in for a missing x-total-count"):
    val built = Pages.fromEnvelope(responseWith(), PageParams.First, ListEnvelope(Vector(1, 2, 3), Some(7)))

    assertEquals(built.totalCount, Some(7))
    assertEquals(built.nextPage, Some(page(2)))

  test("x-total-count wins over the envelope's total when both are present"):
    val built =
      Pages.fromEnvelope(
        responseWith("x-total-count" -> List("3")),
        PageParams.First,
        ListEnvelope(Vector(1, 2, 3), Some(7))
      )

    assertEquals(built.totalCount, Some(3))
    assertEquals(built.nextPage, None)

  test("an envelope with no total and no headers is the last page"):
    val built = Pages.fromEnvelope(responseWith(), PageParams.First, ListEnvelope(Vector(1, 2, 3), None))

    assertEquals(built.totalCount, None)
    assertEquals(built.nextPage, None)

  test("a Link relation is authoritative for an envelope listing too"):
    val built =
      Pages.fromEnvelope(
        responseWith(linkHeader("<x?page=9>; rel=\"next\"")),
        PageParams.First,
        ListEnvelope(Vector(1), Some(1))
      )

    assertEquals(built.nextPage, Some(page(9)))

  test("a wire count is a total only when it is a non-negative Int"):
    assertEquals(ListEnvelope.countOf(Some(4210L)), Some(4210))
    assertEquals(ListEnvelope.countOf(Some(0L)), Some(0))
    assertEquals(ListEnvelope.countOf(Some(-1L)), None)
    assertEquals(ListEnvelope.countOf(Some(Int.MaxValue.toLong + 1L)), None)
    assertEquals(ListEnvelope.countOf(None), None)
