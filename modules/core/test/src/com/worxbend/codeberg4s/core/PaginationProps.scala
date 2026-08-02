package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageNumber
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.syntax.discard

import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Prop.AnyOperators
import org.scalacheck.Prop.forAll

import scala.collection.mutable.ListBuffer

/** The pagination driver's invariants, over an arbitrary collection rather than the three-page example.
  *
  * Four things must hold for every shape of collection, and each one is a way a paginating client goes wrong in the
  * field. '''Exactly once, in order''' — a driver that refetched or reordered a page would silently duplicate or
  * scramble a caller's data. '''One fetch per page''' — an extra request per page doubles the load on the instance and
  * burns the caller's rate limit. '''Laziness''' — page `n + 1` must not be requested before page `n` has been folded,
  * or a listing of a large repository becomes an outage. '''Nothing partial''' — a fetch that fails must end the walk
  * with that failure and not with a prefix that looks like a complete answer.
  *
  * The pages are built to advertise a next page for as long as one exists, which is the only end-of-collection signal
  * Forgejo emits that can be trusted; see `Pages` for why a short page does not mean the last page.
  */
final class PaginationProps extends PropertyBase:

  private val pagination: Pagination[Exec.Result] = new Pagination[Exec.Result]

  private val size: PageSize = PageSize.from(10).getOrElse(PageSize.Default)

  private def numbered(value: Int): PageNumber =
    PageNumber.from(value).getOrElse(PageNumber.First)

  /** Collections of one to six pages, each holding between zero and five items. */
  private val collections: Gen[Vector[Vector[Int]]] =
    Gen
      .choose(1, 6)
      .flatMap(count => Gen.listOfN(count, Gen.listOf(Gen.choose(0, 999)).map(_.toVector)))
      .map(_.toVector)

  /** The same, with every page guaranteed non-empty, so nothing but the missing `rel="next"` ends the walk. */
  private val inhabitedCollections: Gen[Vector[Vector[Int]]] =
    Gen
      .choose(1, 6)
      .flatMap(count => Gen.listOfN(count, Gen.nonEmptyListOf(Gen.choose(0, 999)).map(_.toVector)))
      .map(_.toVector)

  /** Page `index` of `collection`, advertising a following page for as long as one exists. */
  private def pageAt(collection: Vector[Vector[Int]], index: Int): Page[Int] =
    Page(
      items      = collection(index - 1),
      params     = PageParams(numbered(index), size),
      totalCount = Some(collection.map(_.size).sum),
      nextPage   = if index < collection.size then Some(numbered(index + 1)) else None,
      prevPage   = numbered(index).previous,
    )

  /** A fetch that serves `collection` and records the page numbers it was asked for. */
  private def fetcher(collection: Vector[Vector[Int]], log: ListBuffer[String]): PageParams => Exec.Result[Page[Int]] =
    params =>
      val index = params.page.value
      log.append(s"fetch-$index").discard
      if index >= 1 && index <= collection.size then Right(pageAt(collection, index))
      else Left(CodebergError.Validation(ValidationError("page", s"no page $index in this fixture")))

  private def numbersIn(log: ListBuffer[String], prefix: String): List[Int] =
    log.toList.filter(_.startsWith(prefix)).flatMap(_.stripPrefix(prefix).toIntOption)

  property("every item of every page is folded exactly once, in the order the server sent it".tag(Property)):
    forAll(inhabitedCollections) { collection =>
      val log = ListBuffer.empty[String]

      val collected = pagination.listAll(PageParams.First)(fetcher(collection, log))

      (collected ?= Right(collection.flatten)).label("the concatenation of every page, in order") &&
      (numbersIn(log, "fetch-") ?= (1 to collection.size).toList).label("one fetch per page, in order")
    }

  property("listAll is foldPages with concatenation".tag(Property)):
    forAll(collections) { collection =>
      val listLog = ListBuffer.empty[String]
      val foldLog = ListBuffer.empty[String]

      val collected = pagination.listAll(PageParams.First)(fetcher(collection, listLog))
      val folded    = pagination.foldPages(PageParams.First, Vector.empty[Int])(fetcher(collection, foldLog)) {
        (accumulated, page) => accumulated ++ page.items
      }

      (collected ?= folded).label("same result") &&
      (listLog.toList ?= foldLog.toList).label("same fetches")
    }

  property("no page is requested before the previous one has been folded".tag(Property)):
    forAll(inhabitedCollections) { collection =>
      val log = ListBuffer.empty[String]

      val total = pagination.foldPages(PageParams.First, 0)(fetcher(collection, log)) { (running, page) =>
        log.append(s"fold-${page.params.page.value}").discard
        running + page.size
      }

      val expected = (1 to collection.size).toList.flatMap(index => List(s"fetch-$index", s"fold-$index"))

      (log.toList ?= expected).label("fetch and fold must strictly alternate") &&
      (total ?= Right(collection.map(_.size).sum))
    }

  property("an empty page ends the walk even while the response still advertises a next page".tag(Property)):
    forAll(inhabitedCollections, Gen.choose(1, 7)) { (collection, position) =>
      val stopAt   = math.min(position, collection.size + 1)
      val withHole = collection.take(stopAt - 1) ++ Vector(Vector.empty[Int]) ++ collection.drop(stopAt - 1)
      val log      = ListBuffer.empty[String]

      val collected = pagination.listAll(PageParams.First)(fetcher(withHole, log))

      (collected ?= Right(withHole.take(stopAt - 1).flatten)).label(s"items before the empty page at $stopAt") &&
      (numbersIn(log, "fetch-") ?= (1 to stopAt).toList).label("the empty page is the last one fetched")
    }

  property("a failed fetch ends the walk with that failure and discards what was folded".tag(Property)):
    forAll(inhabitedCollections, Gen.choose(1, 6)) { (collection, position) =>
      val failAt                 = math.min(position, collection.size)
      val log                    = ListBuffer.empty[String]
      val failure: CodebergError =
        CodebergError.Validation(ValidationError("page", s"page $failAt is unavailable"))

      val serve = fetcher(collection, log)

      val collected = pagination.listAll(PageParams.First) { params =>
        if params.page.value >= failAt then Left(failure) else serve(params)
      }

      (collected ?= Left(failure)).label("the failure, never a partial result") &&
      (numbersIn(log, "fetch-") ?= (1 until failAt).toList).label("nothing is fetched past the failure")
    }

  property("the window's size is carried to every page of the walk".tag(Property)):
    forAll(inhabitedCollections, Gen.choose(1, 50)) { (collection, requested) =>
      val window = PageParams(PageNumber.First, PageSize.from(requested).getOrElse(PageSize.Default))
      val sizes  = ListBuffer.empty[Int]

      val collected = pagination.foldPages(window, 0) { params =>
        sizes.append(params.size.value).discard
        val index = params.page.value
        if index >= 1 && index <= collection.size then Right(pageAt(collection, index))
        else Left(CodebergError.Validation(ValidationError("page", s"no page $index")))
      }((running, page) => running + page.size)

      Prop.propBoolean(collected.isRight) &&
      (sizes.toList ?= List.fill(collection.size)(requested)).label("every request must keep the window's size")
    }
