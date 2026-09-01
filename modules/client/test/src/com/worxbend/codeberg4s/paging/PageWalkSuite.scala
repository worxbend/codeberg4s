package com.worxbend.codeberg4s.paging

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException

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

    val result = PageWalk.fold(PageParams.First, 0)(fetch)((count, page) => count + page.items.size)

    assertEquals(visited.size, PageWalk.MaxPages)
    assert(result.value.exists(_.isFailure), "the walk should not have returned a value")

  test("hitting the page cap fails rather than returning the pages gathered so far"):
    // The defect this replaced: the cap returned the accumulated state, which is
    // the same shape a complete walk returns. A caller could not tell a listing
    // of ten thousand pages from one of ten thousand and one.
    def fetch(params: PageParams): Future[Page[Int]] =
      Future.successful(Page(Vector(1), params, None, PageNumber.from(params.page.value + 1).toOption, None))

    val result              = PageWalk.fold(PageParams.First, 0)(fetch)((count, page) => count + page.items.size)
    val expected: Throwable =
      CodebergException(CodebergError.WalkTruncated(PageWalk.MaxPages, PageParams(cappedPage, PageSize.Default)))

    assertEquals(result.value.flatMap(_.failed.toOption), Some(expected))

  test("the truncation error resumes at the page the walk refused, keeping the caller's page size"):
    val size  = PageSize.from(7).toOption.getOrElse(PageSize.Default)
    val start = PageParams(PageNumber.First, size)

    def fetch(params: PageParams): Future[Page[Int]] =
      Future.successful(Page(Vector.empty, params, None, PageNumber.from(params.page.value + 1).toOption, None))

    val result = PageWalk.fold(start, 0)(fetch)((count, _) => count)

    result.value.flatMap(_.failed.toOption) match
      case Some(CodebergException(CodebergError.WalkTruncated(pagesVisited, resumeFrom))) =>
        assertEquals(pagesVisited, PageWalk.MaxPages)
        assertEquals(resumeFrom.page, cappedPage)
        assertEquals(resumeFrom.size, size)
      case other                                                                          =>
        fail(s"expected a truncated walk, got $other")

  test("a listing that ends on the last page the cap allows is complete, not truncated"):
    // The boundary the cap must not get wrong. Exactly MaxPages pages arrive and
    // the last one offers nothing further, so the walk saw the whole collection
    // and there is nothing to report.
    def fetch(params: PageParams): Future[Page[Int]] =
      val next = if params.page.value < PageWalk.MaxPages then PageNumber.from(params.page.value + 1).toOption else None
      Future.successful(Page(Vector(1), params, None, next, None))

    val counted = await(PageWalk.fold(PageParams.First, 0)(fetch)((count, page) => count + page.items.size))

    assertEquals(counted, PageWalk.MaxPages)

  test("the attempt rail gathers every item across every page, in order"):
    val listing = Listing(count = 3, size = 4)

    val items = await(PageWalk.attempt.all(PageParams.First)(params => listing.fetch(params).map(Right(_))))

    assertEquals(items, Right(Vector.range(0, 12)))

  test("an error page yields a Left and stops the walk there"):
    // The mirror of "a failing page fails the whole walk". The rails differ in
    // how the failure is spelled, never in which pages are visited.
    val visited = ListBuffer.empty[Int]
    val denied  = CodebergError.Validation("page", "the listing went away mid-walk")

    def fetch(params: PageParams): Future[Either[CodebergError, Page[Int]]] =
      visited.append(params.page.value)
      if params.page.value >= 2 then Future.successful(Left(denied))
      else Future.successful(Right(Page(Vector(1), params, None, PageNumber.from(2).toOption, None)))

    val result = await(PageWalk.attempt.fold(PageParams.First, 0)(fetch)((count, page) => count + page.items.size))

    assertEquals(result, Left(denied))
    assertEquals(visited.toList, List(1, 2))

  test("hitting the page cap yields Left(WalkTruncated) carrying the window to resume from"):
    val size  = PageSize.from(7).toOption.getOrElse(PageSize.Default)
    val start = PageParams(PageNumber.First, size)

    def fetch(params: PageParams): Future[Either[CodebergError, Page[Int]]] =
      Future.successful(Right(Page(Vector(1), params, None, PageNumber.from(params.page.value + 1).toOption, None)))

    val result = await(PageWalk.attempt.fold(start, 0)(fetch)((count, page) => count + page.items.size))

    assertEquals(result, Left(CodebergError.WalkTruncated(PageWalk.MaxPages, PageParams(cappedPage, size))))

  test("the attempt rail runs the effect once per page and reports success"):
    val listing = Listing(count = 3, size = 2)
    val sizes   = ListBuffer.empty[Int]

    val result = await(
      PageWalk.attempt.foreach(PageParams.First)(params => listing.fetch(params).map(Right(_)))(page =>
        sizes.append(page.items.size).discard
      )
    )

    assertEquals(result, Right(()))
    assertEquals(sizes.toList, List(2, 2, 2))

  /** The page a capped walk is offered and declines: one past the last it fetched. */
  private val cappedPage: PageNumber =
    PageNumber.from(PageWalk.MaxPages + 1).toOption.getOrElse(PageNumber.First)

  extension [A](value: A) private def discard: Unit = ()
