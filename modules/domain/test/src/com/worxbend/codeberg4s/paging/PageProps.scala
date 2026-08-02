package com.worxbend.codeberg4s.paging

import com.worxbend.codeberg4s.PropertyBase

import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Prop.AnyOperators
import org.scalacheck.Prop.forAll

/** The paging value types and the one operation `Page` offers.
  *
  * `Page.map` is how every wire DTO page becomes a domain page, so it runs on the result of every list endpoint in the
  * library. What it must never do is touch the pagination metadata: a `map` that dropped `nextPage` would end a walk
  * one page in, and a `map` that dropped `totalCount` would make an inhabited collection look empty — both of which
  * look like a server problem from the caller's side. The properties below pin the metadata down field by field, and
  * also state the two functor laws, because a `map` that satisfies identity and composition cannot be reordering or
  * duplicating items behind the size check.
  */
final class PageProps extends PropertyBase:

  private def pageNumber(value: Int): PageNumber =
    PageNumber.from(value).getOrElse(PageNumber.First)

  private def pageSize(value: Int): PageSize =
    PageSize.from(value).getOrElse(PageSize.Default)

  private val pageNumbers: Gen[PageNumber] = Gen.choose(1, 500).map(pageNumber)

  private val pageParams: Gen[PageParams] =
    for
      number <- pageNumbers
      size   <- Gen.choose(1, 50).map(pageSize)
    yield PageParams(number, size)

  private val pages: Gen[Page[Int]] =
    for
      params <- pageParams
      items  <- Gen.listOf(Gen.choose(-1000, 1000)).map(_.toVector)
      total  <- Gen.option(Gen.choose(0, 100000))
      next   <- Gen.option(pageNumbers)
      prev   <- Gen.option(pageNumbers)
    yield Page(items, params, total, next, prev)

  private val functions: Gen[Int => Int] =
    Gen.oneOf[Int => Int](value => value + 1, value => value * 2, value => value - 7, _ => 0)

  property("map keeps every item, in order, and changes nothing else about the page".tag(Property)):
    forAll(pages, functions) { (page, f) =>
      val mapped = page.map(f)
      (mapped.items ?= page.items.map(f)).label("items") &&
      (mapped.items.size ?= page.items.size).label("size") &&
      (mapped.size ?= page.size).label("reported size") &&
      (mapped.params ?= page.params).label("params") &&
      (mapped.totalCount ?= page.totalCount).label("totalCount") &&
      (mapped.nextPage ?= page.nextPage).label("nextPage") &&
      (mapped.prevPage ?= page.prevPage).label("prevPage") &&
      (mapped.isLast ?= page.isLast).label("isLast")
    }

  property("map obeys the identity law".tag(Property)):
    forAll(pages)(page => page.map(identity) ?= page)

  property("map obeys the composition law".tag(Property)):
    forAll(pages, functions, functions)((page, f, g) => page.map(f).map(g) ?= page.map(f.andThen(g)))

  property("a page reports itself as last exactly when the response offered no next page".tag(Property)):
    forAll(pages)(page => page.isLast ?= page.nextPage.isEmpty)

  property("a page number is accepted exactly when it is at least one, and then keeps its value".tag(Property)):
    forAll(Gen.frequency(3 -> Gen.choose(-3, 3), 1 -> Gen.choose(Int.MinValue, Int.MaxValue))) { value =>
      PageNumber.from(value) match
        case Right(number) => (number.value ?= value) && Prop.propBoolean(value >= 1).label(s"accepted $value")
        case Left(error)   => (error.field ?= "pageNumber") && Prop.propBoolean(value < 1).label(s"rejected $value")
    }

  property("a page size is accepted exactly when Forgejo would serve it unclamped".tag(Property)):
    forAll(Gen.frequency(3 -> Gen.choose(-2, 55), 1 -> Gen.choose(Int.MinValue, Int.MaxValue))) { value =>
      val servable = value >= 1 && value <= 50
      PageSize.from(value) match
        case Right(size) => (size.value ?= value) && Prop.propBoolean(servable).label(s"accepted $value")
        case Left(error) => (error.field ?= "pageSize") && Prop.propBoolean(!servable).label(s"rejected $value")
    }

  property("stepping to the next page and back lands on the page you started from".tag(Property)):
    forAll(pageNumbers) { number =>
      (number.next.value ?= number.value + 1) &&
      (number.next.previous ?= Some(number)) &&
      (number.previous ?= (if number.value > 1 then Some(pageNumber(number.value - 1)) else None))
    }

  property("advancing a window changes the page and never the size".tag(Property)):
    forAll(pageParams) { params =>
      (params.next.page.value ?= params.page.value + 1) &&
      (params.next.size.value ?= params.size.value)
    }

  property("moving a window to an explicit page keeps its size".tag(Property)):
    forAll(pageParams, pageNumbers) { (params, target) =>
      (params.at(target).page ?= target) && (params.at(target).size ?= params.size)
    }
