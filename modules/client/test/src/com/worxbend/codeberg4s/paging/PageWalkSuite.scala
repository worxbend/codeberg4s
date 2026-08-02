package com.worxbend.codeberg4s.paging

import munit.FunSuite

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** [[PageWalk]] — the generic walk every paged listing shares.
  *
  * The behaviour worth pinning is the termination rule. Forgejo clamps `limit` to 50 while echoing the requested value
  * in `Link`, so a walk that stopped when a page came back short would stop after the first page of a listing the
  * caller asked 100 items for. Several tests below would pass under that broken rule, and several would not; the ones
  * that would not are the point.
  */
final class PageWalkSuite extends FunSuite:

  private given ExecutionContext = ExecutionContext.parasitic

  /** Serves pages `1..count`, each carrying `size` items, offering a next page until the last. */
  private final class Listing(count: Int, size: Int):
    private val seen = ListBuffer.empty[Int]

    def fetch(params: PageParams): Future[Page[Int]] =
      val number = params.page.value
      seen.append(number)
      val start  = (number - 1) * size
      val items  = Vector.range(start, start + size)
      val next   = if number < count then PageNumber.from(number + 1).toOption else None
      Future.successful(Page(items, params, Some(count * size), next, None))

    def requested: List[Int] = seen.toList

  private def await[A](future: Future[A]): A =
    future.value match
      case Some(result) => result.get
      case None         => fail("the future had not completed under the parasitic execution context")

  test("all gathers every item across every page, in order"):
    val listing = Listing(count = 3, size = 4)

    val items = await(PageWalk.all(PageParams.First)(listing.fetch))

    assertEquals(items, Vector.range(0, 12))

  test("all visits each page exactly once, in order"):
    val listing = Listing(count = 4, size = 2)

    await(PageWalk.all(PageParams.First)(listing.fetch)).discard

    assertEquals(listing.requested, List(1, 2, 3, 4))

  test("a single page terminates without asking for a second"):
    val listing = Listing(count = 1, size = 5)

    val items = await(PageWalk.all(PageParams.First)(listing.fetch))

    assertEquals(items, Vector.range(0, 5))
    assertEquals(listing.requested, List(1))

  test("an empty listing yields nothing and asks once"):
    val listing = Listing(count = 1, size = 0)

    val items = await(PageWalk.all(PageParams.First)(listing.fetch))

    assertEquals(items, Vector.empty)
    assertEquals(listing.requested, List(1))

  test("the walk continues while a next page is offered, even when a page is short"):
    // This is the regression the whole helper exists for. Page one carries two
    // items where the caller asked for a larger window, because Forgejo clamps
    // limit; a walk that stopped on a short page would return four items here.
    def fetch(params: PageParams): Future[Page[Int]] =
      val number = params.page.value
      val next   = if number < 3 then PageNumber.from(number + 1).toOption else None
      Future.successful(Page(Vector(number * 10, number * 10 + 1), params, None, next, None))

    val items = await(PageWalk.all(PageParams.First)(fetch))

    assertEquals(items, Vector(10, 11, 20, 21, 30, 31))

  test("the walk stops when no next page is offered, however full the last page is"):
    def fetch(params: PageParams): Future[Page[Int]] =
      Future.successful(Page(Vector.fill(50)(1), params, None, None, None))

    val items = await(PageWalk.all(PageParams.First)(fetch))

    assertEquals(items.size, 50)

  test("the page size the caller chose is carried to every page"):
    val requested = ListBuffer.empty[PageParams]

    def fetch(params: PageParams): Future[Page[Int]] =
      requested.append(params)
      val next = if params.page.value < 3 then PageNumber.from(params.page.value + 1).toOption else None
      Future.successful(Page(Vector.empty, params, None, next, None))

    val size  = PageSize.from(7).toOption.getOrElse(PageSize.Default)
    val start = PageParams(PageNumber.First, size)

    await(PageWalk.all(start)(fetch)).discard

    assertEquals(requested.toList.map(_.size), List(size, size, size))

  test("fold threads state through every page"):
    val listing = Listing(count = 3, size = 2)

    val total = await(PageWalk.fold(PageParams.First, 0)(listing.fetch)((sum, page) => sum + page.items.size))

    assertEquals(total, 6)

  test("fold sees whole pages, so it can read the reported total"):
    val listing = Listing(count = 2, size = 3)

    val totals = await(
      PageWalk.fold(PageParams.First, Vector.empty[Option[Int]])(listing.fetch)((seen, page) =>
        seen :+ page.totalCount
      )
    )

    assertEquals(totals, Vector(Some(6), Some(6)))

  test("fold does not accumulate items, so a large walk stays bounded"):
    val listing = Listing(count = 100, size = 50)

    val counted = await(PageWalk.fold(PageParams.First, 0)(listing.fetch)((count, page) => count + page.items.size))

    assertEquals(counted, 5000)

  test("foreach runs the effect once per page"):
    val listing = Listing(count = 3, size = 2)
    val sizes   = ListBuffer.empty[Int]

    await(PageWalk.foreach(PageParams.First)(listing.fetch)(page => sizes.append(page.items.size).discard))

    assertEquals(sizes.toList, List(2, 2, 2))

  test("a failing page fails the whole walk"):
    val boom = RuntimeException("page two is unavailable")

    def fetch(params: PageParams): Future[Page[Int]] =
      if params.page.value >= 2 then Future.failed(boom)
      else Future.successful(Page(Vector(1), params, None, PageNumber.from(2).toOption, None))

    val result = PageWalk.all(PageParams.First)(fetch)

    assertEquals(result.value.flatMap(_.failed.toOption), Some(boom))

  test("a server that always offers a next page cannot spin forever"):
    // Bounded rather than trusted: a misbehaving or misconfigured instance that
    // never stops offering rel="next" must not hang the caller's process.
    val visited = ListBuffer.empty[Int]

    def fetch(params: PageParams): Future[Page[Int]] =
      visited.append(params.page.value)
      Future.successful(Page(Vector(1), params, None, PageNumber.from(params.page.value + 1).toOption, None))

    val counted = await(PageWalk.fold(PageParams.First, 0)(fetch)((count, page) => count + page.items.size))

    assertEquals(counted, PageWalk.MaxPages)
    assertEquals(visited.size, PageWalk.MaxPages)

  extension [A](value: A) private def discard: Unit = ()
