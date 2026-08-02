package com.worxbend.codeberg4s.paging

import scala.collection.immutable.VectorBuilder
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Walks a paged endpoint without every listing having to grow its own `listAll`.
  *
  * Every listing in this library has the same shape — `PageParams => Future[Page[A]]` — so the walk can be written once
  * and applied to any of them, rather than duplicated across the sixty-odd resource groups. That also keeps the
  * convenience honest: a caller passes the very operation they would have called by hand, so nothing is hidden.
  *
  * {{{
  * val all: Future[Vector[Issue]] =
  *   PageWalk.all(PageParams.First): params =>
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
  * '''Failure.''' The returned `Future` fails the way the underlying operation does — with
  * [[com.worxbend.codeberg4s.CodebergException]] on the convenience rail. A failure part-way through a walk discards
  * the pages already gathered; use [[fold]] if partial progress needs to be kept somewhere.
  *
  * '''Memory.''' [[all]] holds every item. A repository can have tens of thousands of issues, so prefer [[fold]] or
  * [[foreach]] when the result does not need to exist all at once — that is the whole reason no operation in this
  * library returns an unbounded collection by default.
  */
object PageWalk:

  /** The most pages any bounded walk visits before giving up, so a server that always offers a next page cannot spin
    * forever. Deliberately generous: 10 000 pages of 50 is half a million items.
    */
  val MaxPages: Int = 10_000

  /** Gathers every item from `first` onward.
    *
    * Convenient and bounded only by the data: read the memory note above before using it on a large repository.
    *
    * @param first
    *   the page to start from, usually [[PageParams.First]]
    * @param fetch
    *   the listing operation, applied once per page
    */
  def all[A](first: PageParams)(fetch: PageParams => Future[Page[A]])(using ExecutionContext): Future[Vector[A]] =
    fold(first, VectorBuilder[A]())(fetch)((builder, page) => builder ++= page.items).map(_.result())

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
    */
  def fold[A, B](first: PageParams, zero: B)(fetch: PageParams => Future[Page[A]])(
      step: (B, Page[A]) => B
  )(using ExecutionContext): Future[B] =
    def loop(params: PageParams, state: B, visited: Int): Future[B] =
      if visited >= MaxPages then Future.successful(state)
      else
        fetch(params).flatMap: page =>
          val next = step(state, page)
          page.nextPage match
            case Some(number) => loop(params.at(number), next, visited + 1)
            case None         => Future.successful(next)

    loop(first, zero, 0)

  /** Applies `onPage` to every page from `first` onward, for a walk whose result is a side effect.
    *
    * The effect runs before the next page is requested, so a slow consumer throttles the walk rather than racing it.
    */
  def foreach[A](first: PageParams)(fetch: PageParams => Future[Page[A]])(
      onPage: Page[A] => Unit
  )(using ExecutionContext): Future[Unit] =
    fold(first, ())(fetch)((_, page) => onPage(page))
