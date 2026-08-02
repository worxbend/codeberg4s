package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.syntax.discard

import munit.FunSuite

import scala.collection.mutable.ListBuffer

final class PaginationSuite extends FunSuite:

  private val pagination: Pagination[Exec.Result] = new Pagination[Exec.Result]

  test("a single page is fetched once and no second page is asked for"):
    val log   = ListBuffer.empty[String]
    val pages = Vector(pageOf(1, Vector(1, 2), lastIndex = 1))

    val result = pagination.listAll(PageParams.First)(fetcher(pages, log))

    assertEquals(result, Right(Vector(1, 2)))
    assertEquals(log.toList, List("fetch-1"))

  test("three pages are walked in order and their items concatenated"):
    val log   = ListBuffer.empty[String]
    val pages = threePages

    val result = pagination.listAll(PageParams.First)(fetcher(pages, log))

    assertEquals(result, Right(Vector(1, 2, 3, 4, 5)))
    assertEquals(log.toList, List("fetch-1", "fetch-2", "fetch-3"))

  test("an empty page ends the walk even when the response still offers a next page"):
    val log   = ListBuffer.empty[String]
    val pages = Vector(pageOf(1, Vector.empty[Int], lastIndex = 3))

    val result = pagination.listAll(PageParams.First)(fetcher(pages, log))

    assertEquals(result, Right(Vector.empty[Int]))
    assertEquals(log.toList, List("fetch-1"))

  test("a page is never fetched before the previous one has been folded"):
    val log   = ListBuffer.empty[String]
    val pages = threePages

    val result = pagination.foldPages(PageParams.First, 0)(fetcher(pages, log)): (total, page) =>
      log.append(s"fold-${page.params.page.value}").discard
      total + page.size

    assertEquals(result, Right(5))
    assertEquals(log.toList, List("fetch-1", "fold-1", "fetch-2", "fold-2", "fetch-3", "fold-3"))

  test("the fold sees the pagination metadata, not just the items"):
    val log   = ListBuffer.empty[String]
    val pages = threePages

    val result = pagination.foldPages(PageParams.First, List.empty[Int])(fetcher(pages, log)): (seen, page) =>
      seen.appended(page.params.page.value)

    assertEquals(result, Right(List(1, 2, 3)))

  test("a failed fetch ends the walk with that failure"):
    val log   = ListBuffer.empty[String]
    val pages = Vector(pageOf(1, Vector(1), lastIndex = 2))

    val result = pagination.listAll(PageParams.First)(fetcher(pages, log))

    assert(result.isLeft)
    assertEquals(log.toList, List("fetch-1", "fetch-2"))

  private def threePages: Vector[Page[Int]] = Vector(
    pageOf(1, Vector(1, 2), lastIndex = 3),
    pageOf(2, Vector(3, 4), lastIndex = 3),
    pageOf(3, Vector(5), lastIndex    = 3),
  )

  /** Serves the prepared pages and records every request, so an eager walk shows up as an extra `fetch-` entry. */
  private def fetcher(pages: Vector[Page[Int]], log: ListBuffer[String]): PageParams => Exec.Result[Page[Int]] =
    params =>
      val index = params.page.value
      log.append(s"fetch-$index").discard
      if index >= 1 && index <= pages.size then Right(pages(index - 1))
      else Left(CodebergError.Validation(ValidationError("page", s"page $index does not exist")))

  private def pageOf(index: Int, items: Vector[Int], lastIndex: Int): Page[Int] =
    Page(
      items      = items,
      params     = PageParams.First.at(pageNumber(index)),
      totalCount = None,
      nextPage   = if index < lastIndex then Some(pageNumber(index + 1)) else None,
      prevPage   = if index > 1 then Some(pageNumber(index - 1)) else None,
    )

  private def pageNumber(index: Int): PageNumber =
    if index <= 1 then PageNumber.First else pageNumber(index - 1).next
