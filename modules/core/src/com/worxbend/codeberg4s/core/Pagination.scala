package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.core.Exec.flatMap
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams

/** Walks a paginated Forgejo collection one page at a time.
  *
  * The driver is '''lazy''': page `n + 1` is not requested before page `n` has been handed to the fold. That is the
  * whole point — a repository can hold tens of thousands of issues, and a driver that fetched everything up front would
  * turn one listing into an outage. [[Exec.suspend]] is what keeps that true even when `F` is eager.
  *
  * A walk stops on the first of three conditions: the response offered no following page, the page came back empty, or
  * the fetch failed. The empty-page guard matters against instances that advertise a next page forever; without it a
  * caller would loop until the rate limit stopped them.
  *
  * Failures are not swallowed. The first page that fails ends the walk with that failure, and everything folded so far
  * is discarded — a partial result that looks complete is worse than an error.
  *
  * @tparam F
  *   the effect the client runs in
  */
final class Pagination[F[_]](using exec: Exec[F]):

  /** Folds every page of a collection into a single value, fetching lazily.
    *
    * The step function sees whole pages rather than items so that a caller can use the pagination metadata — stop early
    * on a total count, report progress, or write each page out before the next is requested.
    *
    * @param start
    *   where to begin, usually [[com.worxbend.codeberg4s.paging.PageParams.First]]; its size is kept for every
    *   following page
    * @param zero
    *   the initial accumulator
    * @param fetch
    *   requests one page; called once per page, never ahead of the fold
    * @param step
    *   combines the accumulator with a page, before the next page is requested
    */
  def foldPages[A, B](start: PageParams, zero: B)(fetch: PageParams => F[Page[A]])(step: (B, Page[A]) => B): F[B] =
    loop(start, zero, fetch, step)

  /** Collects every item of a collection into memory.
    *
    * The convenient shape, and the dangerous one: the whole collection ends up in the returned vector. Use it when the
    * collection is known to be small — labels, milestones, a repository's branches — and [[foldPages]] otherwise.
    */
  def listAll[A](start: PageParams)(fetch: PageParams => F[Page[A]]): F[Vector[A]] =
    foldPages(start, Vector.empty[A])(fetch)((collected, page) => collected ++ page.items)

  private def loop[A, B](
      params: PageParams,
      accumulator: B,
      fetch: PageParams => F[Page[A]],
      step: (B, Page[A]) => B,
  ): F[B] =
    exec.suspend(() => fetch(params)).flatMap: page =>
      val folded = step(accumulator, page)
      page.nextPage match
        case Some(following) if page.items.nonEmpty => loop(params.at(following), folded, fetch, step)
        case _                                      => exec.pure(folded)
