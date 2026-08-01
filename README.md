# codeberg4s

A Scala 3 client for the [Codeberg](https://codeberg.org) / [Forgejo](https://forgejo.org)
REST API v1.

- **`Future`-based public API** — no effect system leaks into your code, and no
  effect system is added to your classpath.
- **Works against any Forgejo or Gitea-compatible instance.** Codeberg is the
  default base URI, not a hardcoded one.
- **Two error rails.** Use exceptions if that suits your codebase, or typed
  `Either` values if it does not. Same implementation underneath.
- **Pagination you cannot get wrong.** List endpoints return a `Page[A]`; walking
  every page is opt-in and streams rather than materialising.
- **Small dependency footprint** — sttp client4 and upickle. That is the list.

> Status: pre-release, `0.1.0` in progress. See [`docs/ROADMAP.md`](docs/ROADMAP.md)
> for what is implemented today and [`docs/API_INVENTORY.md`](docs/API_INVENTORY.md)
> for endpoint-level coverage.

## Install

```scala
// Mill
def mvnDeps = Seq(mvn"com.worxbend::codeberg4s-client:0.1.0")

// sbt
libraryDependencies += "com.worxbend" %% "codeberg4s-client" % "0.1.0"
```

## Quick start

```scala
import com.worxbend.codeberg4s.*
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.auth.ApiToken

import scala.concurrent.ExecutionContext.Implicits.global

val token  = ApiToken.from(sys.env("CODEBERG_TOKEN"))   // Either[ValidationError, ApiToken]
val config = token.map(t => CodebergConfig(Auth.Token(t)))

// The backend is a resource. Close it when your application shuts down.
val client = CodebergClient(config)

client.repos.get(Owner("forgejo"), RepoName("forgejo"))   // Future[Repository]
```

Everything is grouped the way the API's own tags are: `client.repos`,
`client.issues`, `client.pulls`, `client.users`, `client.orgs`,
`client.notifications`.

## The two error rails

The convenience rail fails the `Future` with a `CodebergException`, which carries
the full `CodebergError` ADT — so nothing is lost by using it:

```scala
client.issues.get(slug, IssueNumber(42)).recover:
  case CodebergException(CodebergError.NotFound(_))       => fallbackIssue
  case CodebergException(CodebergError.RateLimited(wait)) => scheduleRetry(wait)
```

The typed rail never fails the `Future`:

```scala
client.issues.attempt.get(slug, IssueNumber(42)): Future[Either[CodebergError, Issue]]
```

Pick one per call site. They are projections of the same code path, so they
cannot drift.

## Pagination

A repository can have tens of thousands of issues, so no operation returns an
unbounded `List` by accident. One page at a time:

```scala
client.issues.list(slug, IssueQuery(state = IssueState.Open), PageParams.First)
// Future[Page[Issue]] — items, totalCount, nextPage
```

Every page, driven sequentially with the retry policy applied per page:

```scala
client.issues.listAll(slug, IssueQuery(state = IssueState.Open))   // Future[Vector[Issue]]
```

Or fold pages without holding them all in memory — the right choice for large
repositories:

```scala
client.issues.foldPages(slug, query, 0):
  (count, page) => count + page.items.size
```

## Configuration

```scala
CodebergConfig(
  baseUri         = BaseUri.from("https://my-forgejo.example/api/v1"),
  auth            = Auth.Token(token),
  retry           = RetryPolicy.Default,   // 429 and 5xx, jittered backoff, honours Retry-After
  userAgent       = UserAgent.from("my-app/1.0"),
  defaultPageSize = PageSize.Default,      // 30; Forgejo caps limit at 50
  connectTimeout  = 10.seconds,
  readTimeout     = 30.seconds,
)
```

Retries apply to idempotent methods only. `POST`, `PATCH` and `DELETE` are never
retried automatically — opt in per call if you know the operation is safe to
repeat.

## Tokens are not logged, ever

`ApiToken` is a redacting type. Its `toString` is `***`, string interpolation of
it is `***`, and no `CodebergError` — including the request URI captured in
`CallContext` — can contain it. There are tests that assert exactly this, because
a leaked token in an exception message is the failure mode that matters most in a
library like this one.

If you want request/response visibility, implement the `Telemetry` port. The
library has no logging dependency and writes nothing to stdout.

## Development

```bash
./mill modules.__.compile      # warnings are errors
./mill modules.__.test         # unit tests
./mill modules.__.reformat     # scalafmt
./mill modules.__.fix          # scalafix
./verify.sh                    # the full pre-handoff gate
```

Design decisions are recorded in [`docs/adr/`](docs/adr/). Start with
[ADR-0005](docs/adr/0005-future-public-api.md), which explains why the API is
`Future`-based, and [ADR-0001](docs/adr/0001-curated-models-over-codegen.md),
which explains why the models are hand-written rather than generated from the
Swagger spec.

## Licence

See `LICENSE`.
