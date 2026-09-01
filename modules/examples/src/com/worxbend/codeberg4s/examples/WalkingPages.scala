package com.worxbend.codeberg4s.examples

import com.worxbend.codeberg4s.issues.{Issue, IssueQuery}
import com.worxbend.codeberg4s.{
  Auth,
  CodebergClient,
  CodebergConfig,
  Owner,
  Page,
  PageNumber,
  PageParams,
  PageSize,
  RepoName
}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

/** Walking a paginated listing correctly, and the one mistake that makes a walk silently wrong.
  *
  * ==Running it==
  *
  * {{{
  * ./mill modules.examples.runMain com.worxbend.codeberg4s.examples.WalkingPages
  * }}}
  *
  * ==Environment==
  *
  * None. It lists issues on `forgejo/forgejo` anonymously.
  *
  * ==`items.size` is not an end-of-pages test==
  *
  * This is the most important operational fact about the library (`docs/HAZARDS.md` §5). Forgejo silently clamps the
  * `limit` query parameter to its own maximum while the `Link` header echoes the value that was asked for. A caller who
  * asks for 200 items and stops when a page holds fewer than 200 stops on page one of thirty-two, having read a
  * fraction of the collection and reported success.
  *
  * [[com.worxbend.codeberg4s.paging.Page.nextPage]] comes from the RFC 5988 `Link` header and from nothing else. It is
  * the only thing that says whether the collection continues, and `page.isLast` is the same fact spelled the other way.
  * A page past the end is `200` with an empty array, not a `404`.
  *
  * A page's `totalCount` comes from `x-total-count`. Several endpoints omit that header, so `None` means "unknown" and
  * never zero; it is fine for a progress line and wrong as a loop bound.
  *
  * ==No resource group returns a whole collection==
  *
  * Every listing operation on `client.repos`, `client.issues` and the rest returns exactly one `Page`. None of them has
  * a `listAll`, because a repository can hold tens of thousands of issues and buffering them all is a decision the
  * caller has to make on purpose rather than one a method name makes for them.
  *
  * So the walk is written out below by hand. That is the point of this program: whatever helper you eventually wrap it
  * in, the helper is doing exactly this, and the terminator is the part it has to get right. Reading the nine lines
  * once is how you can tell a correct wrapper from one that stops on page one.
  */
object WalkingPages:

  private val AwaitLimit: FiniteDuration = 5.minutes

  /** How many pages either walk will read before stopping.
    *
    * A bound, not a page count: `forgejo/forgejo` has hundreds of pages of issues, this is an example, and an example
    * that spends someone else's rate-limit budget on a full walk is a bad example. Real code either drops the bound or
    * derives it from what the caller asked for.
    */
  private val MaxPages: Int = 3

  /** The listing this program walks. Both names are literals, so the compiler checks them and hands back the
    * identifiers themselves; `Owner.from` is for a value known only at run time.
    */
  private val owner: Owner = Owner("forgejo")

  private val name: RepoName = RepoName("forgejo")

  def main(args: Array[String]): Unit =
    given ExecutionContext = ExecutionContext.global

    val client: CodebergClient = CodebergClient(CodebergConfig(Auth.Anonymous))

    try
      // PageSize.Max is 50, which is Forgejo's real ceiling. PageSize.from
      // rejects anything larger rather than letting the instance clamp it,
      // so the surprise happens here instead of three pages into a walk.
      val start = PageParams(PageNumber.First, PageSize.Max)

      // One request per page. The listing operation is passed as a function
      // of PageParams so that both walks below work for any listing in the
      // library — every one of them has the same shape.
      val issues: PageParams => Future[Page[Issue]] =
        params => client.issues.list(owner, name, IssueQuery.Empty, params)

      ExampleConsole.heading("walk — one line per page, driven by nextPage")
      Await.result(describeEachPage(issues, start, MaxPages), AwaitLimit)

      ExampleConsole.heading("bounded fold — carries a count, never the items")
      val counts = Await.result(foldPages(issues, start, Counts.Zero, MaxPages, Counts.add), AwaitLimit)
      ExampleConsole.line(s"  $counts")
    finally client.close()

  /** Reads pages until the response stops offering one, printing what each page said about the walk.
    *
    * The terminator is `page.nextPage`, and `params.at(next)` reproduces the window at that page number while keeping
    * the size. `page.items.size` appears in the output only to show that it is '''not''' what ends the loop.
    *
    * Recursion rather than a loop: each step depends on the previous response, there is no mutable cursor to get wrong,
    * and the recursive call is the last thing the `flatMap` does, so nothing accumulates on the stack.
    */
  private def describeEachPage[A](
      fetch: PageParams => Future[Page[A]],
      params: PageParams,
      remaining: Int,
  )(using ExecutionContext): Future[Unit] =
    if remaining < 1 then Future.successful(())
    else
      fetch(params).flatMap: page =>
        val total   = page.totalCount.fold("unknown")(count => count.toString)
        val asked   = params.size.value
        val arrived = page.items.size

        ExampleConsole.line(
          s"  page ${params.page.value}: asked for $asked, got $arrived, total $total, isLast ${page.isLast}"
        )

        page.nextPage match
          case Some(next) => describeEachPage(fetch, params.at(next), remaining - 1)
          case None       =>
            ExampleConsole.line("  the response offered no next page — that, and only that, is the end")
            Future.successful(())

  /** Folds every item of every page into one accumulator, holding at most one page of items at a time.
    *
    * The point of the shape is what it does '''not''' do: it never builds a `Vector` of the whole collection. Each page
    * is folded into `zero` and then dropped, so memory is bounded by the page size no matter how long the collection
    * is. Replacing `combine` with `_ :+ _` would undo it in one character, which is exactly what a `listAll`-shaped
    * convenience does — a fine thing to reach for deliberately, and a bad thing to get by default.
    *
    * @param remaining
    *   how many further pages to read; `0` stops immediately, so a caller can bound the walk without a mutable counter
    */
  private def foldPages[A, B](
      fetch: PageParams => Future[Page[A]],
      params: PageParams,
      zero: B,
      remaining: Int,
      combine: (B, A) => B,
  )(using ExecutionContext): Future[B] =
    if remaining < 1 then Future.successful(zero)
    else
      fetch(params).flatMap: page =>
        val folded = page.items.foldLeft(zero)(combine)

        page.nextPage match
          case Some(next) => foldPages(fetch, params.at(next), folded, remaining - 1, combine)
          case None       => Future.successful(folded)

  /** What the fold carries: two counters and nothing else.
    *
    * `GET /repos/{owner}/{repo}/issues` returns pull requests as well as issues — Forgejo serves both from the same
    * endpoint and the same number sequence — so a walk that reports "issues" without checking
    * [[com.worxbend.codeberg4s.issues.Issue.isPullRequest]] is over-reporting.
    */
  private final case class Counts(issues: Int, pullRequests: Int):

    override def toString: String = s"$issues issues and $pullRequests pull requests seen"

  private object Counts:

    val Zero: Counts = Counts(0, 0)

    def add(counts: Counts, issue: Issue): Counts =
      if issue.isPullRequest then counts.copy(pullRequests = counts.pullRequests + 1)
      else counts.copy(issues                              = counts.issues + 1)
