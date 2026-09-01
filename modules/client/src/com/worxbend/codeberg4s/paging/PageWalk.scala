package com.worxbend.codeberg4s.paging

import com.worxbend.codeberg4s.{CodebergError, CodebergException}

import scala.collection.immutable.VectorBuilder
import scala.concurrent.{ExecutionContext, Future}

/** Walks a paged endpoint without every listing having to grow its own `listAll`.
  *
  * Every listing in this library has the same shape — `PageParams => Future[Page[A]]` — so the walk can be written once
  * and applied to any of them, rather than duplicated across the sixty-odd resource groups. That also keeps the
  * convenience honest: a caller passes the very operation they would have called by hand, so nothing is hidden.
  *
  * {{{
  * val all: Future[Vector[Issue]] =
  *   PageWalk.all(client.firstPage): params =>
  *     client.issues.list(owner, name, IssueQuery.Empty, params)
  * }}}
  *
  * '''Why this is not a method on each listing.''' Sixty `listAll` methods would be sixty places for the termination
  * rule to be got wrong, and the termination rule is the subtle part — see below.
  *
  * '''Termination.''' The walk stops when the server stops offering a next page, which it signals through the RFC 5988
  * `Link` header and nothing else. It never compares the number of items returned against the number requested: Forgejo
  * silently clamps `limit` to its `max_response_items` (50 on codeberg.org) while echoing the requested value back in
  * `Link`, so a caller asking for 100 gets 50 items on every full page and would stop after the first.
  * `docs/HAZARDS.md` §5 has the measured evidence.
  *
  * '''Sequencing.''' Pages are fetched one at a time, each after the previous has been consumed. That is deliberate:
  * fetching pages concurrently against a rate-limited instance is a good way to earn a `429`, and the page after this
  * one is not known until this one arrives.
  *
  * '''Two rails, like every operation.''' [[all]], [[fold]] and [[foreach]] take a convenience-rail operation and fail
  * the returned `Future` with [[com.worxbend.codeberg4s.CodebergException]], the way that operation would.
  * [[attempt.all]], [[attempt.fold]] and [[attempt.foreach]] take an `.attempt`-rail operation — one returning
  * `Either[CodebergError, Page[A]]` — and return `Either[CodebergError, B]`, never failing. Both rails run the one
  * loop, so choosing between them is a choice of error style and never a choice of behaviour, exactly as it is on the
  * client itself.
  *
  * '''Failure.''' A failure part-way through a walk discards the pages already gathered, on either rail; use [[fold]]
  * if partial progress needs to be kept somewhere.
  *
  * '''The page cap is a failure, not a quiet stop.''' A walk visits at most [[MaxPages]] pages. Reaching that cap with
  * the server still offering another page reports [[com.worxbend.codeberg4s.CodebergError.WalkTruncated]] — a failed
  * `Future` on the convenience rail, a `Left` on the `.attempt` rail — rather than returning what was gathered, because
  * a short answer that looks exactly like a complete one is the worse of the two outcomes. The error carries the window
  * to resume from, so a caller who genuinely wants more than half a million items can continue from there.
  *
  * '''Memory.''' [[all]] holds every item. A repository can have tens of thousands of issues, so prefer [[fold]] or
  * [[foreach]] when the result does not need to exist all at once — that is the whole reason no operation in this
  * library returns an unbounded collection by default.
  */
object PageWalk:

  /** The most pages any bounded walk visits before giving up, so a server that always offers a next page cannot spin
    * forever. Deliberately generous: 10 000 pages of 50 is half a million items.
    *
    * A walk that reaches this many pages and is offered another fails with
    * [[com.worxbend.codeberg4s.CodebergError.WalkTruncated]]. A walk whose last page happens to be the ten-thousandth
    * and offers nothing further has reached the natural end of the listing and succeeds.
    */
  val MaxPages: Int = 10_000

  /** Gathers every item from `first` onward.
    *
    * Convenient and bounded only by the data: read the memory note above before using it on a large repository.
    *
    * @param first
    *   the page to start from, usually `client.firstPage` (or [[PageParams.First]] when no client is in hand)
    * @param fetch
    *   the listing operation, applied once per page
    */
  def all[A](first: PageParams)(fetch: PageParams => Future[Page[A]])(using ExecutionContext): Future[Vector[A]] =
    raise(attempt.all(first)(lift(fetch)))

  /** Folds over every page from `first` onward, newest state threaded through.
    *
    * The fold sees whole pages rather than single items, so an implementation can use a page's `totalCount` for
    * progress reporting, or stop caring about items entirely and just count.
    *
    * @param first
    *   the page to start from
    * @param zero
    *   the initial state
    * @param fetch
    *   the listing operation, applied once per page
    * @param step
    *   combines the state so far with the page just fetched
    * @return
    *   the folded state, or a `Future` failed with [[com.worxbend.codeberg4s.CodebergException]] wrapping
    *   [[com.worxbend.codeberg4s.CodebergError.WalkTruncated]] when [[MaxPages]] was reached with pages still to come
    */
  def fold[A, B](first: PageParams, zero: B)(fetch: PageParams => Future[Page[A]])(
      step: (B, Page[A]) => B
  )(using ExecutionContext): Future[B] =
    raise(attempt.fold(first, zero)(lift(fetch))(step))

  /** Applies `onPage` to every page from `first` onward, for a walk whose result is a side effect.
    *
    * The effect runs before the next page is requested, so a slow consumer throttles the walk rather than racing it.
    */
  def foreach[A](first: PageParams)(fetch: PageParams => Future[Page[A]])(
      onPage: Page[A] => Unit
  )(using ExecutionContext): Future[Unit] =
    raise(attempt.foreach(first)(lift(fetch))(onPage))

  /** The same three walks against the `.attempt` rail, taking an operation that materialises its failure as
    * `Either[CodebergError, Page[A]]` and returning one that does the same.
    *
    * These are not a second implementation: [[attempt.fold]] is the only loop in this file, and the convenience rail
    * above is that loop with a `Left` turned back into a failed `Future`. The termination rule the whole helper exists
    * to state once therefore is stated once.
    *
    * {{{
    * val all: Future[Either[CodebergError, Vector[Issue]]] =
    *   PageWalk.attempt.all(client.firstPage): params =>
    *     client.issues.attempt.list(owner, name, IssueQuery.Empty, params)
    * }}}
    */
  object attempt:

    /** Gathers every item from `first` onward, or the first error the walk met.
      *
      * @param first
      *   the page to start from
      * @param fetch
      *   the `.attempt` listing operation, applied once per page
      */
    def all[A](first: PageParams)(fetch: PageParams => Future[Either[CodebergError, Page[A]]])(using
        ExecutionContext): Future[Either[CodebergError, Vector[A]]] =
      fold(first, VectorBuilder[A]())(fetch)((builder, page) => builder ++= page.items).map(_.map(_.result()))

    /** Folds over every page from `first` onward, stopping at the first page that came back a `Left`.
      *
      * The pages already folded in are discarded along with the state built from them: a `Left` means the walk did not
      * see the whole listing, and a partial fold that looks exactly like a complete one is the outcome this library
      * refuses. A caller who wants partial progress should write it out from `step` as it goes.
      *
      * @param first
      *   the page to start from
      * @param zero
      *   the initial state
      * @param fetch
      *   the `.attempt` listing operation, applied once per page
      * @param step
      *   combines the state so far with the page just fetched
      * @return
      *   the folded state, `Left(error)` from the first page that failed, or
      *   `Left(`[[com.worxbend.codeberg4s.CodebergError.WalkTruncated]]`)` when [[MaxPages]] was reached with pages
      *   still to come
      */
    def fold[A, B](first: PageParams, zero: B)(fetch: PageParams => Future[Either[CodebergError, Page[A]]])(
        step: (B, Page[A]) => B
    )(using ExecutionContext): Future[Either[CodebergError, B]] =
      def loop(params: PageParams, state: B, visited: Int): Future[Either[CodebergError, B]] =
        fetch(params).flatMap:
          case Left(error) => Future.successful(Left(error))
          case Right(page) =>
            val next    = step(state, page)
            val fetched = visited + 1
            page.nextPage match
              // The cap is tested against a page the server actually offered, so a
              // listing that ends on the last page the cap allows is complete and
              // succeeds. Only an offer this walk refuses to follow is truncation.
              case Some(number) if fetched >= MaxPages =>
                Future.successful(Left(CodebergError.WalkTruncated(fetched, params.at(number))))
              case Some(number)                        => loop(params.at(number), next, fetched)
              case None                                => Future.successful(Right(next))

      loop(first, zero, 0)

    /** Applies `onPage` to every page from `first` onward, stopping at the first page that came back a `Left`. */
    def foreach[A](first: PageParams)(fetch: PageParams => Future[Either[CodebergError, Page[A]]])(
        onPage: Page[A] => Unit
    )(using ExecutionContext): Future[Either[CodebergError, Unit]] =
      fold(first, ())(fetch)((_, page) => onPage(page))

  /** Reads a convenience-rail operation as an `.attempt`-rail one, so both rails share the single loop. */
  private def lift[A](
      fetch: PageParams => Future[Page[A]]
  )(using ExecutionContext): PageParams => Future[Either[CodebergError, Page[A]]] =
    params => fetch(params).map(Right(_))

  /** Puts an `.attempt`-rail result back on the convenience rail, a `Left` becoming a failed `Future`. */
  private def raise[B](walk: Future[Either[CodebergError, B]])(using ExecutionContext): Future[B] =
    walk.flatMap:
      case Right(value) => Future.successful(value)
      case Left(error)  => Future.failed(CodebergException(error))
