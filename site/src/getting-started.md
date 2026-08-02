# Getting Started

Everything on this page is compiled against the library as part of building
this site. If a snippet here stops matching the API, the site build fails.

## Install

**Nothing is published to Maven Central yet.** The build is configured for it —
five artifacts under `com.worxbend`, currently at `0.1.0-SNAPSHOT` — but until
the `0.1.0` tag is cut, these coordinates resolve only against a local publish.
That is the honest state of affairs, and the first thing you would otherwise
discover the hard way.

```scala
// Mill
def mvnDeps = Seq(mvn"com.worxbend::codeberg4s-client:0.1.0")

// sbt
libraryDependencies += "com.worxbend" %% "codeberg4s-client" % "0.1.0"

// scala-cli
//> using dep com.worxbend::codeberg4s-client:0.1.0
```

`codeberg4s-client` pulls in `-transport`, `-codec`, `-core` and `-domain`
transitively. Depend on a narrower artifact if you want less: `codeberg4s-domain`
is the models and the error ADT with no dependencies at all, which is enough to
write code that *handles* a `CodebergError` without linking an HTTP client.

## The client

A `CodebergClient` owns an HTTP connection pool and a scheduler thread. Build
one per instance you talk to, keep it for the lifetime of the application, and
close it once.

```scala mdoc:compile-only
import com.worxbend.codeberg4s.{CodebergClient, CodebergConfig}
import com.worxbend.codeberg4s.auth.Auth

import scala.concurrent.ExecutionContext

given ExecutionContext = ExecutionContext.global

val client: CodebergClient = CodebergClient(CodebergConfig(Auth.Anonymous))

// ... and at shutdown, exactly once:
client.close()
```

`CodebergConfig(auth)` fills in Codeberg's base URI, the default retry policy,
the default user agent, a page size of 30, and 10 s / 30 s timeouts. There is a
second factory, `CodebergClient.usingBackend(config, backend)`, for when your
application already owns an sttp backend — on that path `close()` does **not**
close the backend, because you own it.

## Your first request

Identifiers are opaque types with `Either`-returning smart constructors.
`Owner("forgejo")` does not compile. This is not ceremony: a string containing
a `/` would otherwise forge a request path.

```scala mdoc:compile-only
import com.worxbend.codeberg4s.{CodebergClient, ValidationError}
import com.worxbend.codeberg4s.repositories.{Owner, RepoName, Repository}

import scala.concurrent.Future

def forgejoRepository(client: CodebergClient): Either[ValidationError, Future[Repository]] =
  for
    owner <- Owner.from("forgejo")
    name  <- RepoName.from("forgejo")
  yield client.repos.get(owner, name)
```

Two nested containers is the honest shape: validation fails *before* a request
exists, so it cannot be a failed `Future`. Most applications validate their
configuration once at start-up and carry the validated values around, which
flattens this away.

(The snippets on this page are written as methods taking the values they need.
That is not a requirement of the library — it is how mdoc compiles a fragment
without inventing a client for it.)

## Authenticating

Authentication is a different `Auth` and nothing else. The token is validated
on the way in, so a blank or control-character-bearing string never reaches a
request header.

```scala mdoc:compile-only
import com.worxbend.codeberg4s.{CodebergConfig, ValidationError}
import com.worxbend.codeberg4s.auth.{ApiToken, Auth}

val config: Either[ValidationError, CodebergConfig] =
  ApiToken
    .from(sys.env.getOrElse("CODEBERG_TOKEN", ""))
    .map(token => CodebergConfig(Auth.Token(token)))
```

`Auth` has three cases: `Anonymous`, `Token(ApiToken)` and
`Basic(username, Password)`. Create a personal access token at
`https://codeberg.org/user/settings/applications`.

`ApiToken` is a redacting type: its `toString` is `***`, interpolating it gives
`***`, and `reveal` is the only way to get the material out — a method name you
will notice in review. No `CodebergError` can carry a credential either; the
URI inside a `CallContext` is redacted before the context is built.

Do not assume the anonymous paths are anonymous. The pinned Swagger spec
carries no per-endpoint security information at all, and codeberg.org answers
`401` to an anonymous `GET /users/{username}/followers` — measured, not guessed.

## Choosing an error rail

Every operation exists twice.

The convenience rail fails the `Future` with a `CodebergException`, which
carries the full `CodebergError`, so nothing is lost by using it:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.{CodebergClient, CodebergError, CodebergException}
import com.worxbend.codeberg4s.repositories.{Owner, RepoName, Repository}

import scala.concurrent.{ExecutionContext, Future}

def readOrFallback(
    client: CodebergClient,
    owner: Owner,
    name: RepoName,
    fallback: Repository,
)(using ExecutionContext): Future[Repository] =
  client.repos.get(owner, name).recover:
    case CodebergException(CodebergError.Api(_, 404, _)) => fallback
```

Any case you do not handle stays a failed `Future`, carrying the same value.

The typed rail never fails the `Future`:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.{CodebergClient, CodebergError}
import com.worxbend.codeberg4s.repositories.{Owner, RepoName, Repository}

import scala.concurrent.Future

def attemptRead(
    client: CodebergClient,
    owner: Owner,
    name: RepoName,
): Future[Either[CodebergError, Repository]] =
  client.repos.attempt.get(owner, name)
```

`CodebergError` has exactly five cases — `Transport`, `Api`, `DecodingFailed`,
`Validation`, `RetriesExhausted`. **There is no `NotFound` and no
`RateLimited`.** A `404` is `Api(ctx, 404, body)`; a `429` is
`Api(ctx, 429, body)`, or a `RetriesExhausted` wrapping one after the retry
policy gives up. Matching on a case that does not exist is the most common
mistake made against this library.

## Listing things

Listings take a `PageParams` and return one `Page[A]`:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.issues.{Issue, IssueQuery}
import com.worxbend.codeberg4s.paging.{Page, PageParams}
import com.worxbend.codeberg4s.repositories.{Owner, RepoName}

import scala.concurrent.Future

def firstPageOfIssues(
    client: CodebergClient,
    owner: Owner,
    name: RepoName,
): Future[Page[Issue]] =
  client.issues.list(owner, name, IssueQuery.Empty, PageParams.First)
```

A `Page[A]` carries `items`, the `params` that produced it, an optional
`totalCount` from the `x-total-count` header, and `nextPage` / `prevPage`.
`isLast` is `nextPage.isEmpty`.

### Walking every page

`PageWalk.all`, `PageWalk.fold` and `PageWalk.foreach` walk any listing.
`core.Pagination` implements both, but it is not constructible from outside
`modules/core`, so a caller writes the walk by hand. Nine lines:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.issues.{Issue, IssueQuery}
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.{Owner, RepoName}

import scala.concurrent.{ExecutionContext, Future}

final class IssueWalk(client: CodebergClient, owner: Owner, name: RepoName)(using ExecutionContext):

  def walk(params: PageParams, seen: Vector[Issue]): Future[Vector[Issue]] =
    client.issues.list(owner, name, IssueQuery.Empty, params).flatMap: page =>
      page.nextPage match
        case Some(following) if page.items.nonEmpty => walk(params.at(following), seen ++ page.items)
        case _                                      => Future.successful(seen ++ page.items)
```

Two details in those nine lines are load-bearing.

**The loop branches on `nextPage`, never on `items.size`.** Forgejo clamps
`limit` to the instance maximum while echoing back the value you asked for, so
`items.size < requested` is true on every page. A loop written that way stops
after the first page and reports a truncated result as a complete one — a bug
that does not fail, it under-reports. `PageSize.from` refuses anything above 50
for this reason, but the instance maximum is configurable and
`client.misc.apiSettings().map(_.maxResponseItems)` is where the real ceiling
lives, so the guard is necessary rather than sufficient.

**The `page.items.nonEmpty` guard is not decoration.** Some instances advertise
a next page forever. Without it, the loop runs until the rate limit stops it.

## Where to go next

- **[Guides](guides/README.md)** — one problem per page, in more depth than
  this.
- **[Examples](examples.md)** — the same material as programs you can run.
- **[The API reference][api]** — Scaladoc for every public member.
