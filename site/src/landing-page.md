A Scala 3 client for the [Codeberg](https://codeberg.org) and
[Forgejo](https://forgejo.org) REST API v1. All 439 in-scope operations are
implemented, on both error rails, against models built from captured responses
rather than from the published Swagger spec.

Codeberg is the *default* base URI, not a hardcoded one: the same client talks
to any Forgejo or Gitea-compatible instance.

## The four properties that matter

**A `Future` API, and nothing else.** The public API is
`scala.concurrent.Future`. No effect system leaks into your code, and none is
added to your classpath — the dependency list is sttp client4 and jsoniter-scala, and
that is the whole list. If your application uses cats-effect or ZIO, you wrap a
`Future` at your own boundary; if it uses neither, you pay for neither.

**Two error rails.** Every operation exists twice.
`client.repos.get(owner, name)` returns `Future[Repository]` and fails that
`Future` with a `CodebergException`.
`client.repos.attempt.get(owner, name)` returns
`Future[Either[CodebergError, Repository]]` and never fails. The second is the
first with its failure channel materialised, so the two cannot drift apart.
Pick one per call site, not per project.

**Illegal requests are unrepresentable.** Owners, repository names, branches,
labels, tokens, base URIs and page sizes are opaque types whose only
constructors return `Either`. `Owner("forgejo")` does not compile;
`Owner.from("forgejo")` gives you an `Either[ValidationError, Owner]`. A string
that would forge a request path is rejected before a client is involved, and a
malformed token never reaches a request header.

**Pagination you cannot get wrong by accident.** No operation returns an
unbounded `List`. Every listing returns a `Page[A]` whose `nextPage` is derived
from the RFC 5988 `Link` header and from nothing else — never from how many
items came back. That distinction is not pedantry; see
[the clamp hazard](#the-one-thing-to-read-before-you-paginate) below.

## Quick start

```scala mdoc:compile-only
import com.worxbend.codeberg4s.{CodebergClient, CodebergConfig}
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.repositories.{Owner, RepoName}
import scala.concurrent.ExecutionContext.Implicits.global

val client = CodebergClient(CodebergConfig(Auth.Anonymous))
val stars  = for
  owner <- Owner.from("forgejo")
  name  <- RepoName.from("forgejo")
yield client.repos.get(owner, name).map(_.starsCount)
```

`client` owns a connection pool and a scheduler thread, so build one per
instance you talk to and `close()` it at shutdown.
[Getting Started](getting-started.md) does the same thing slowly, with the
imports written out and the shutdown handled.

## The one thing to read before you paginate

Forgejo **clamps the `limit` query parameter to the instance's own maximum
while the `Link` header echoes the value you asked for**. Ask for 100 items on
an instance capped at 50 and you get 50 back, with nothing in the body saying
so. A loop that stops when `items.size < requested` therefore stops on the
first page of thirty and reports a truncated answer as a complete one.

`Page.nextPage` and `Page.isLast` come from the `Link` header. They are the
only correct end-of-pages test. `totalCount` is an `Option` because several
endpoints omit `x-total-count`, and `None` means "unknown", never zero.

The measured evidence for this, and for five other divergences between the
pinned spec and what Codeberg actually returns, is in
[Hazards](project/HAZARDS.md).

## Errors have exactly five shapes

`CodebergError` is a closed family: `Transport`, `Api`, `DecodingFailed`,
`Validation`, `RetriesExhausted`.

There is no `NotFound` case and no `RateLimited` case. A `404` is
`Api(ctx, 404, body)`; a `429` is `Api(ctx, 429, body)`, or a
`RetriesExhausted` wrapping one once the retry policy has run out of attempts.
Every remote case carries a `CallContext` — operation id, method, redacted URI,
elapsed milliseconds — so you can tell which call failed without correlating
logs.

## Where to go next

- **[Getting Started](getting-started.md)** — install, first request,
  authentication, shutdown. Start here if you have never used the library.
- **[Guides](guides/README.md)** — task-oriented, one problem per page:
  authentication, errors, pagination, retries and rate limits, observability,
  testing, writing data, self-hosted instances and troubleshooting.
- **[Reference](reference/README.md)** — the resource groups, a glossary and an
  FAQ: looked up rather than read through.
- **[Examples](examples.md)** — runnable programs in `modules/examples`,
  compiled by the build under `-Werror`, each with the exact command that runs
  it.
- **[The API reference][api]** — full Scaladoc for all five published
  artifacts.
- **[Project documents](project/README.md)** — the roadmap, the measured
  hazards, the readiness assessment and the architecture decision records,
  copied verbatim from the repository at build time.

Every Scala snippet on this site is compiled by mdoc against the library it
documents, as part of building the site. A snippet that stops compiling fails
the build.
