# Pagination

For anyone about to write a loop over a listing. This is the most important
guide on this site: the obvious loop is wrong against Forgejo, and it fails by
under-reporting rather than by raising anything.

## The types

Three of them, and no operation in this library returns an unbounded collection
by accident.

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.issues.Issue
import com.worxbend.codeberg4s.issues.IssueQuery
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName

import scala.concurrent.Future

def firstPageOfIssues(client: CodebergClient, owner: Owner, name: RepoName): Future[Page[Issue]] =
  client.issues.list(owner, name, IssueQuery.Empty, PageParams.First)
```

**`PageParams(page, size)`** is the window you ask for: which page, and how many
items it may hold. `PageParams.First` is page 1 at the default size of 30.
`params.next` advances by one page keeping the size; `params.at(number)` jumps.

**`PageSize`** is Forgejo's `limit`. Valid values are `1..50`; `PageSize.from`
rejects anything else rather than clamping it. **`PageNumber`** is one-based;
`PageNumber.from` rejects `0`, because Forgejo silently treats `page=0` as
`page=1` and would hide an off-by-one in your code.

**`Page[A]`** is what one request returns:

| Member | Meaning |
| --- | --- |
| `items: Vector[A]` | this page's items, in the order the server sent them |
| `params: PageParams` | the window that produced this page, so you can resume or reproduce the request |
| `totalCount: Option[Int]` | the `x-total-count` header, when the endpoint sent one |
| `nextPage: Option[PageNumber]` | the following page, when the response offered one |
| `prevPage: Option[PageNumber]` | the preceding page, when there is one |
| `isLast: Boolean` | `nextPage.isEmpty` |
| `size: Int` | `items.size` — this page's length, **not** the collection's |

## The clamp hazard

Here is the loop almost everybody writes first:

```scala
// WRONG. Never do this against Forgejo.
if page.items.size < requestedSize then "that was the last page" else "fetch the next one"
```

Forgejo **clamps `limit` to the instance's own maximum, and echoes the value you
asked for**. Ask for 500 items per page on codeberg.org, whose maximum is 50, and
you get 50 items back — with nothing in the body saying so, and with the RFC 5988
`Link` header still spelling `limit=500`.

Measured against codeberg.org, `GET …/issues?page=1&limit=500` on a collection of
1590 issues answers:

```
link: <…?limit=500&page=2>; rel="next",<…?limit=500&page=32>; rel="last"
x-total-count: 1590
```

with 50 items in the body. Note `page=32`, which is `ceil(1590 / 50)`: the page
arithmetic uses the **effective** limit while the URL text carries the
**requested** one.

So `items.size < requested` is true on page 1 of 32, and on every other page too.
A loop written that way stops after the first page and reports a truncated
result as complete. It does not fail, it does not warn, and it does not raise —
it under-reports. That is the worst failure mode a client library can have, and
it is why this document exists.

`PageSize` refuses anything above 50 partly for this reason, but the instance
maximum is per-instance configuration, so the guard is necessary and not
sufficient. The real ceiling lives at
`client.misc.apiSettings().map(_.maxResponseItems)` — see
[Self-hosted instances](./09-self-hosted.md).

## The signal that is correct

The library decides "is there another page" from the response's `rel="next"`
`Link` header, and from nothing else. It never looks at how many items arrived.
Use the same signal:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.issues.Issue
import com.worxbend.codeberg4s.paging.Page

def moreToCome(page: Page[Issue]): Boolean = page.nextPage.isDefined
```

`page.isLast` says the same thing more briefly.

The `Link` behaviour was measured across all four page positions on codeberg.org:
`next` and `last` are omitted on the last page, `first` and `prev` are omitted on
the first, and a page past the end answers **`200` with `[]`** rather than `404`.

## Walking every page

To walk every page without writing this loop, see [PageWalk](#walking-every-page-with-pagewalk)
below — so you write the loop. Here it is:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.issues.Issue
import com.worxbend.codeberg4s.issues.IssueQuery
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

def allIssues(client: CodebergClient, owner: Owner, name: RepoName, start: PageParams)(using
    ExecutionContext): Future[Vector[Issue]] =

  def loop(params: PageParams, collected: Vector[Issue]): Future[Vector[Issue]] =
    client.issues.list(owner, name, IssueQuery.Empty, params).flatMap: page =>
      val soFar = collected ++ page.items
      page.nextPage match
        case Some(following) if page.items.nonEmpty => loop(params.at(following), soFar)
        case _                                      => Future.successful(soFar)

  loop(start, Vector.empty)
```

Four details in that loop are load-bearing.

**`flatMap`, not a `while` loop.** Each page is requested only after the previous
one has arrived, so at most one request is in flight and the pages arrive in
order. Nothing here blocks a thread.

**`params.at(following)`** keeps the page size and moves the page number. Do not
rebuild the window from scratch; you will drop the size.

**`if page.items.nonEmpty`** is not decoration. Some instances advertise a next
page forever. Without that guard the loop runs until the rate limit stops it,
which is a request storm you pay for.

**Recursion, not `foldLeft`.** You cannot fold over pages you have not fetched.
The recursion *is* the sequencing.

Note that the recursion is not tail-recursive in the JVM sense, and does not need
to be: each step returns a `Future` and the stack unwinds between pages, so the
depth is bounded by one call, not by the number of pages.

### Failures end the walk

If page 7 of 40 fails, `loop` returns that failure and the six pages already
collected are discarded. That is on purpose: a partial result that looks
complete is worse than an error. If you want the partial result, fold it out as
you go — which is the next section.

## Folding without holding everything in memory

`allIssues` above accumulates every issue in a `Vector`. On a repository with
50 000 issues that is a heap problem, not a pagination problem. The fix is to
fold each page into something small before requesting the next one:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.issues.Issue
import com.worxbend.codeberg4s.issues.IssueQuery
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Folds every page of a repository's issues, one page at a time.
  *
  * `step` sees whole pages rather than single items, so it can use the pagination
  * metadata — report progress, stop on a total count, write the page out before
  * the next one is requested.
  */
def foldIssuePages[B](client: CodebergClient, owner: Owner, name: RepoName, start: PageParams, zero: B)(
    step: (B, Page[Issue]) => B
)(using ExecutionContext): Future[B] =

  def loop(params: PageParams, accumulator: B): Future[B] =
    client.issues.list(owner, name, IssueQuery.Empty, params).flatMap: page =>
      val folded = step(accumulator, page)
      page.nextPage match
        case Some(following) if page.items.nonEmpty => loop(params.at(following), folded)
        case _                                      => Future.successful(folded)

  loop(start, zero)
```

Counting open issues without keeping any of them:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.issues.IssueQuery
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

def countIssues(client: CodebergClient, owner: Owner, name: RepoName)(using ExecutionContext): Future[Long] =

  def loop(params: PageParams, counted: Long): Future[Long] =
    client.issues.list(owner, name, IssueQuery.Empty, params).flatMap: page =>
      val total = counted + page.items.size
      page.nextPage match
        case Some(following) if page.items.nonEmpty => loop(params.at(following), total)
        case _                                      => Future.successful(total)

  loop(PageParams.First, 0L)
```

Peak memory here is one page — 30 issues by default — regardless of how many
there are.

If your side effect is itself asynchronous, thread it through the same way, with
`flatMap` on the step rather than a plain `step` function; the shape does not
otherwise change.

## Two more traps worth naming

**`totalCount` is an `Option`, and `None` is not zero.** Several Forgejo
endpoints omit `x-total-count` entirely — measured: `GET …/issues/1/labels`
sends neither `link` nor `x-total-count`, because it takes no paging parameters
at all. Treat `None` as "unknown". Deciding a collection is empty because the
header was missing is how a sync job convinces itself there is nothing to do.

```scala mdoc:compile-only
import com.worxbend.codeberg4s.issues.Issue
import com.worxbend.codeberg4s.paging.Page

def describeSize(page: Page[Issue]): String =
  page.totalCount match
    case Some(total) => s"$total in total"
    case None        => "the instance did not say how many there are"
```

**`page` and `limit` travel together.** This library always sends both, because
list endpoints given a lone `limit` have been observed to ignore it and return
the entire collection — 862 forks and 5233 stargazers in the captured fixtures.
You do not have to do anything about this; it is why `PageParams` is one value
rather than two optional parameters.

## Walking every page with `PageWalk`

The loops above are the mechanism. `com.worxbend.codeberg4s.paging.PageWalk`
wraps them so you do not have to write one:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.issues.Issue
import com.worxbend.codeberg4s.issues.IssueQuery
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageWalk
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

def every(client: CodebergClient, owner: Owner, name: RepoName)(using
    ExecutionContext): Future[Vector[Issue]] =
  PageWalk.all(PageParams.First): params =>
    client.issues.list(owner, name, IssueQuery.Empty, params)
```

It takes the very operation you would have called yourself, so nothing is
hidden — and there is one implementation of the termination rule rather than one
per listing.

Three shapes, and the choice between them is about memory:

| Method | Returns | Use it when |
| --- | --- | --- |
| `PageWalk.all` | `Future[Vector[A]]` | the whole collection is small enough to hold |
| `PageWalk.fold` | `Future[B]` | you are aggregating — a count, a maximum, a running total |
| `PageWalk.foreach` | `Future[Unit]` | each page is written somewhere and then forgotten |

`fold` and `foreach` are the ones to reach for on a large repository. `all`
holds every item, which is exactly what no operation in this library does by
default:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.issues.IssueQuery
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.paging.PageWalk
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

def openCount(client: CodebergClient, owner: Owner, name: RepoName)(using
    ExecutionContext): Future[Int] =
  PageWalk.fold(PageParams.First, 0): params =>
    client.issues.list(owner, name, IssueQuery.Empty, params)
  .apply((count, page) => count + page.items.size)
```

Pages are fetched one at a time, each after the previous has been consumed —
fetching them concurrently against a rate-limited instance is a good way to earn
a `429`, and the next page is not known until this one arrives.

A walk is bounded at `PageWalk.MaxPages` (10 000), so an instance that never
stops offering `rel="next"` cannot hang your process.

Note what `PageWalk` is *not*: it is not a method on each listing. Sixty
`listAll` methods would be sixty places for the termination rule above to be got
wrong, and that rule is the subtle part.

## Where the evidence is

Everything measured in this guide — the clamp, the `Link` positions, the missing
headers, the `200 []` past the end, the 1:1 correspondence between `x-total-count`
and `rel="last"` — is in [`docs/HAZARDS.md`](../project/HAZARDS.md) §5, with
verbatim captured headers and the exact `curl` commands that produced them.

## Next

- [Retries and rate limits](./05-retries-and-rate-limits.md) — a long walk over
  many pages is the fastest way to meet one.
- [Errors](./03-errors.md) — what to do when page 7 fails.
