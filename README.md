# codeberg4s

A Scala 3 client for the [Codeberg](https://codeberg.org) / [Forgejo](https://forgejo.org)
REST API v1.

- **`Future`-based public API** — no effect system leaks into your code, and no
  effect system is added to your classpath.
- **Works against any Forgejo or Gitea-compatible instance.** Codeberg is the
  default base URI, not a hardcoded one.
- **Two error rails.** Use exceptions if that suits your codebase, or typed
  `Either` values if it does not. Same implementation underneath.
- **Illegal requests are unrepresentable.** Owners, repository names, branches,
  labels and page sizes are validated types with `Either`-returning
  constructors, so a value that would forge a request path is rejected before a
  client is involved.
- **Pagination you cannot get wrong by accident.** No operation returns an
  unbounded `List`; every listing hands back a `Page[A]` that says whether
  another page exists.
- **Small dependency footprint** — sttp client4 and jsoniter-scala. That is the list.

> Status: pre-release, `0.1.0` in progress. **All 439 in-scope operations are
> implemented** on both rails — the whole Forgejo v1 API except `admin`,
> `activitypub` and `package`, which `PLAN.md` puts out of scope for v1.
> [`docs/ROADMAP.md`](docs/ROADMAP.md) tracks phases;
> [`docs/API_INVENTORY.md`](docs/API_INVENTORY.md) has the endpoint-level
> checklist and the honest percentage. Nothing is published to Maven Central
> yet — see "Install".

## Install

**Nothing is published to Maven Central yet.** The build is configured for it —
five artifacts under `com.worxbend`, currently at `0.1.0-SNAPSHOT` — but until
the `0.1.0` tag is cut these coordinates resolve only against a local publish.

```scala
// Mill
def mvnDeps = Seq(mvn"com.worxbend::codeberg4s-client:0.1.0")

// sbt
libraryDependencies += "com.worxbend" %% "codeberg4s-client" % "0.1.0"
```

### Requires a Java 25 runtime

**The jars are compiled for Java 25** (class-file major version 69), the current
long-term-support release. A Java 21 or Java 17 JVM cannot load them: it fails
at class-load time with an `UnsupportedClassVersionError` naming "class file
version 69.0", which says nothing about which library caused it. Check what you
are on with `java -version` before adding the dependency.

This is deliberate, and it does narrow who can adopt the library — see
[`CONTRIBUTING.md`](CONTRIBUTING.md#getting-set-up) for the same requirement on
the build side. Java 25 is a policy floor, not a technical one: the lowest
release the source actually compiles against is Java 21, because
`SttpHttpPort` calls `java.net.http.HttpClient.shutdown()` and that method was
added in Java 21. If a Java 21 baseline would unblock you, open an issue and
say so — moving the floor down is a one-line change to `build.mill`.

`codeberg4s-client` pulls in `-transport`, `-codec`, `-core` and `-domain`
transitively. Depend on a narrower one if you want less: `codeberg4s-domain` is
the models and the error ADT with no dependencies at all, which is enough to
write code that *handles* a `CodebergError` without linking a HTTP client.

## Quick start

```scala
import com.worxbend.codeberg4s.*

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

given ExecutionContext = ExecutionContext.global

// The client owns an HTTP connection pool and a scheduler thread.
// Build one per instance you talk to, for the lifetime of the application.
val client: CodebergClient = CodebergClient(CodebergConfig(Auth.Anonymous))

val stars: Either[ValidationError, Future[Long]] =
  for
    owner <- Owner.from("forgejo")
    name  <- RepoName.from("forgejo")
  yield client.repos.get(owner, name).map(_.starsCount)

// ... and at shutdown:
client.close()
```

The single wildcard import works because the root package re-exports the
everyday surface — `Auth`, `Page`, `PageParams`, and `PageSize` — next to the
types that already live there (`CodebergClient`, `CodebergConfig`, `Owner`,
`RepoName`, `ValidationError`, …). The re-export list is deliberately short:
more specialised types keep one canonical import from their own sub-package,
as the examples below show.

Authenticating is a different `Auth` and nothing else. A token is validated on
the way in, so a blank or control-character-bearing string never reaches a
request header:

```scala
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.ApiToken
import com.worxbend.codeberg4s.auth.Auth

val config: Either[ValidationError, CodebergConfig] =
  ApiToken
    .from(sys.env.getOrElse("CODEBERG_TOKEN", ""))
    .map(token => CodebergConfig(Auth.Token(token)))
```

`CodebergConfig(auth)` fills in Codeberg's base URI, the default retry policy,
the default user agent, a page size of 30 and 10 s / 30 s timeouts. Copy the
result to change one field — see [Configuration](#configuration).

## The nine resource groups

Everything is grouped the way the API's own tags are.

| Accessor                | Group                                                                |
| ----------------------- | -------------------------------------------------------------------- |
| `client.version`        | `GET /version` — what software the instance runs                      |
| `client.repos`          | repositories, branches, tags, commits, releases, topics, forks, contents |
| `client.users`          | the current account, accounts by name, search, follows, keys          |
| `client.issues`         | issues, comments, labels, milestones                                  |
| `client.pulls`          | pull requests, merge, reviews, commits, changed files                 |
| `client.organizations`  | organisations, teams, membership                                      |
| `client.notifications`  | the notification inbox, per-thread and per-repository                 |
| `client.misc`           | markdown rendering, instance settings, the signing key                |

The examples in this section all assume the following are in scope:

```scala
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName

import scala.concurrent.ExecutionContext

given ExecutionContext = ExecutionContext.global

def client: CodebergClient
def owner: Owner   // Owner.from("forgejo")
def name: RepoName // RepoName.from("forgejo")
```

### `client.version`

```scala
import com.worxbend.codeberg4s.ServerVersion

import scala.concurrent.Future

val version: Future[String] = client.version.get().map((v: ServerVersion) => v.raw)
```

Useful as a liveness probe: it is the one endpoint every Forgejo answers
anonymously.

### `client.repos`

Eleven operations. Listings take a `PageParams` and return one `Page`.

```scala
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.Release

import scala.concurrent.Future

val latestTags: Future[Vector[String]] =
  client.repos
    .releases(owner, name, PageParams.First)
    .map(page => page.items.map((release: Release) => release.tagName.value))
```

`getContents` is the one union in the API: the same path returns a file object
or an array of directory entries, so it decodes to an ADT rather than to a
nullable record. `docs/HAZARDS.md` §3 has the captured payloads.

```scala
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.ContentEntry
import com.worxbend.codeberg4s.repositories.ContentPath
import com.worxbend.codeberg4s.repositories.RepositoryContent

import scala.concurrent.Future

val readme: Either[ValidationError, Future[Option[String]]] =
  ContentPath
    .from("README.md")
    .map: path =>
      client.repos.getContents(owner, name, path).map:
        case RepositoryContent.File(ContentEntry.File(_, content, _)) => content.flatMap(_.text)
        case RepositoryContent.File(_)                                => None
        case RepositoryContent.Directory(_)                           => None
```

### `client.users`

Eight operations, in two families. `/user/…` means "whoever the configured
credentials are" and needs a token; `/users/{username}/…` names an account.

```scala
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.paging.Page
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.Repository
import com.worxbend.codeberg4s.users.Username

import scala.concurrent.Future

val me: Future[String] = client.users.current().map(_.login)

val theirRepos: Either[ValidationError, Future[Page[Repository]]] =
  Username.from("earl-warren").map(who => client.users.repositories(who, PageParams.First))
```

Do not assume the anonymous paths are anonymous. The pinned spec carries no
per-endpoint security information at all, and codeberg.org answers `401` to an
anonymous `GET /users/{username}/followers` — measured, not guessed
(`docs/HAZARDS.md` §2).

### `client.issues`

Ten operations. Filters are a value, not a pile of `Option` parameters:

```scala
import com.worxbend.codeberg4s.issues.IssueQuery
import com.worxbend.codeberg4s.issues.StateFilter
import com.worxbend.codeberg4s.paging.PageParams

import scala.concurrent.Future

val query: IssueQuery = IssueQuery.Empty.withState(StateFilter.Open).authoredBy("earl-warren")

val titles: Future[Vector[String]] =
  client.issues.list(owner, name, query, PageParams.First).map(page => page.items.map(_.title))
```

Creating goes through a validated command, so an empty title is a
`ValidationError` rather than a `422` from the server:

```scala
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.issues.CreateIssue
import com.worxbend.codeberg4s.issues.Issue

import scala.concurrent.Future

val filed: Either[ValidationError, Future[Issue]] =
  CreateIssue
    .of("Retry storm on 429")
    .map(_.withBody("Backoff ignores Retry-After when the header is a date."))
    .map(command => client.issues.create(owner, name, command))
```

An issue's `state` is `LifecycleState`, an ADT — `Closed` carries the closing
timestamp, so "closed" and "when" cannot get out of step.

### `client.pulls`

Eight operations. `head` is `PullRequestHead`, which is either a branch in this
repository or the `owner:branch` form a fork needs; there is no way to pass one
where the other was meant:

```scala
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.pulls.CreatePullRequest
import com.worxbend.codeberg4s.pulls.PullRequest
import com.worxbend.codeberg4s.pulls.PullRequestHead
import com.worxbend.codeberg4s.repositories.BranchName

import scala.concurrent.Future

val opened: Either[ValidationError, Future[PullRequest]] =
  for
    from    <- BranchName.from("feature/stream-pages").map(PullRequestHead.branch)
    into    <- BranchName.from("main")
    command <- CreatePullRequest.of("Stream pages instead of buffering", from, into)
  yield client.pulls.create(owner, name, command)
```

Merging is a command too, and `merge` returns `Future[Unit]`: Forgejo answers
`200` with no body, and inventing a `PullRequest` to return would mean guessing
at post-merge state the server did not send.

```scala
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.pulls.MergePullRequest
import com.worxbend.codeberg4s.pulls.MergeStyle
import com.worxbend.codeberg4s.pulls.PullRequestNumber

import scala.concurrent.Future

val merged: Either[ValidationError, Future[Unit]] =
  PullRequestNumber
    .from(1234L)
    .map: number =>
      client.pulls.merge(
        owner,
        name,
        number,
        MergePullRequest.using(MergeStyle.Squash).deletingSourceBranch,
      )
```

`PullRequestState` is `Open | Closed | Merged`, folded from Forgejo's `state`
string *and* its separate `merged` boolean. Reading a merged pull request as
merely closed is the bug that shape prevents.

### `client.organizations`

Ten operations, covering organisations, their repositories and members, and
teams. Teams are rooted at `/teams/{id}` rather than under the organisation,
which is why `getTeam` takes only an id:

```scala
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.organizations.OrgName
import com.worxbend.codeberg4s.organizations.Team
import com.worxbend.codeberg4s.paging.PageParams

import scala.concurrent.Future

val teamNames: Either[ValidationError, Future[Vector[String]]] =
  OrgName
    .from("forgejo")
    .map: org =>
      client.organizations
        .teams(org, PageParams.First)
        .map(page => page.items.map((team: Team) => team.name))
```

A team's permission is `TeamPermission`, ordered, so authorisation checks read
as `permission.allows(TeamPermission.Write)` rather than as string comparison.

### `client.notifications`

Seven operations. All of them require a token — there is no anonymous inbox.

```scala
import com.worxbend.codeberg4s.notifications.NotificationQuery
import com.worxbend.codeberg4s.notifications.NotificationSubjectFilter
import com.worxbend.codeberg4s.notifications.NotificationThread
import com.worxbend.codeberg4s.paging.PageParams

import scala.concurrent.Future

val unread: Future[Long] = client.notifications.unreadCount().map(_.value)

val pullThreads: Future[Vector[NotificationThread]] =
  client.notifications
    .list(
      NotificationQuery.Empty.withSubjects(Vector(NotificationSubjectFilter.Pull)),
      PageParams.First,
    )
    .map(_.items)
```

`NotificationQuery.Empty` is unread-only, matching the endpoint's own default.
Marking read returns `Future[Unit]`:

```scala
import scala.concurrent.Future

val cleared: Future[Unit] = client.notifications.markRepositoryRead(owner, name)
```

A notification's subject type is an *open* enum: a type this library has not
seen decodes to `NotificationSubjectType.Other(raw)` instead of failing the
page, because Forgejo adds subject types between releases.

### `client.misc`

Six operations: markdown rendering in two forms, the three `settings/*`
endpoints, and the instance signing key.

```scala
import com.worxbend.codeberg4s.miscellaneous.MarkdownMode
import com.worxbend.codeberg4s.miscellaneous.MarkdownRenderRequest
import com.worxbend.codeberg4s.miscellaneous.SigningKey

import scala.concurrent.Future

val html: Future[String] =
  client.misc
    .renderMarkdown(MarkdownRenderRequest.of("# codeberg4s").copy(mode = MarkdownMode.Gfm))
    .map(_.html)

val pageSizeCeiling: Future[Long] = client.misc.apiSettings().map(_.maxResponseItems)

// None means the instance does not sign commits, which is a legitimate answer
// and not an error — hence Option rather than a 404.
val key: Future[Option[SigningKey]] = client.misc.signingKey()
```

## The two error rails

Every operation exists twice. The convenience rail fails the `Future` with a
`CodebergException`, which carries the full `CodebergError` ADT — so nothing is
lost by using it:

```scala
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.repositories.Repository

import scala.concurrent.Future

def fallback: Repository

val repository: Future[Repository] =
  client.repos.get(owner, name).recover:
    case CodebergException(CodebergError.Api(_, 404, _, _)) => fallback
```

Any case you do not handle stays a failed `Future`, carrying the same value.

The typed rail never fails the `Future`:

```scala
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.repositories.Repository

import scala.concurrent.Future

val attempted: Future[Either[CodebergError, Repository]] =
  client.repos.attempt.get(owner, name)
```

Pick one per call site. `.attempt` is the convenience rail with its failure
channel materialised, so the two cannot drift.

`CodebergError` is a closed family of six:

| Case                | Means                                                           | Reaction |
| ------------------- | ---------------------------------------------------------------- | -------- |
| `Transport`         | nothing reached the server                                       | safe to retry a safe method |
| `Api`               | the server answered non-2xx; carries `status` and the parsed body | branch on `status` |
| `DecodingFailed`    | a 2xx payload did not match the model                            | retrying will not help; `path` and `snippet` are what a bug report needs |
| `Validation`        | a smart constructor rejected an argument                         | fix the argument |
| `RetriesExhausted`  | the retry engine gave up; `last` is preserved                    | surface `last` |
| `WalkTruncated`     | a `PageWalk` hit its page cap with pages still to come            | walk again from `resumeFrom`, or narrow the query |

There is **no** `RateLimited` case. Forgejo reports rate limiting as an
ordinary `429`, so it arrives as `Api(ctx, 429, body)` — and the retry engine
has usually already honoured `Retry-After` and given up before you see it,
which arrives as `RetriesExhausted` wrapping that `Api`.

Every remote case carries a `CallContext` — operation id, method, redacted URI,
optional request id, elapsed milliseconds — so you can tell *which* call failed
without correlating logs. `error.describe` renders it, bounded and secret-free.
`Validation` and `WalkTruncated` carry none, because neither of them is a
request that reached a server.

## Pagination

A repository can hold tens of thousands of issues, so no operation returns an
unbounded collection by accident. One page at a time:

```scala
import com.worxbend.codeberg4s.issues.Issue
import com.worxbend.codeberg4s.issues.IssueQuery
import com.worxbend.codeberg4s.paging.Page

import scala.concurrent.Future

val first: Future[Page[Issue]] =
  client.issues.list(owner, name, IssueQuery.Empty, client.firstPage)
```

`client.firstPage` is page 1 at the client's configured `defaultPageSize`;
`PageParams.First` is the same window at the library-wide default size, for
code that has no client in hand. A `Page[A]` carries `items`, the `params`
that produced it, an optional `totalCount` from the `x-total-count` header,
and `nextPage` / `prevPage`.

### The clamp hazard — why `items.size` is the wrong end-of-pages test

The obvious loop is wrong:

```scala
// WRONG. Do not do this.
// if (page.items.size < requestedSize) then "this was the last page"
```

Forgejo **clamps `limit` to the instance's own maximum while echoing the value
you asked for**. Ask for 100 on an instance capped at 50 and you get 50 items
back, with nothing in the body saying so. `items.size < requested` is then true
on *every* page, and a loop written that way stops after the first one and
silently reports a truncated result as complete. That is the worst kind of bug
in a client library: it does not fail, it under-reports.

`PageSize` refuses anything above 50 for exactly this reason, but the instance
maximum is configurable and `client.misc.apiSettings().map(_.maxResponseItems)`
is where the real ceiling lives — so the guard is necessary, not sufficient.

The library decides "is there another page" from the response's `rel="next"`
`Link` header and never from how many items came back. Use the same signal:

```scala
import com.worxbend.codeberg4s.issues.Issue
import com.worxbend.codeberg4s.issues.IssueQuery
import com.worxbend.codeberg4s.paging.PageParams

import scala.concurrent.Future

def walk(params: PageParams, seen: Vector[Issue]): Future[Vector[Issue]] =
  client.issues.list(owner, name, IssueQuery.Empty, params).flatMap: page =>
    page.nextPage match
      case Some(following) if page.items.nonEmpty => walk(params.at(following), seen ++ page.items)
      case _                                      => Future.successful(seen ++ page.items)
```

`Page.isLast` is `nextPage.isEmpty` and says the same thing more briefly. The
`page.items.nonEmpty` guard is not decoration: some instances advertise a next
page forever, and without it the loop runs until the rate limit stops it.

Two more traps worth naming:

- **`totalCount` is `Option`, and `None` is not zero.** Several Forgejo
  endpoints omit `x-total-count` entirely. Treat `None` as "unknown".
- **`page` and `limit` travel together.** This library always sends both,
  because list endpoints that receive a lone `limit` have been observed to
  ignore it and return the entire collection — 862 forks, 5233 stargazers in
  the captured fixtures.

Or let `PageWalk` drive the loop, on any listing in the library. Start it from
`client.firstPage` — page 1 at the client's configured `defaultPageSize` —
rather than the config-free constant `PageParams.First`:

```scala
import com.worxbend.codeberg4s.paging.PageWalk

PageWalk.all(client.firstPage): params =>
  client.issues.list(owner, name, IssueQuery.Empty, params)
```

`PageWalk.fold` and `PageWalk.foreach` are the bounded-memory forms — reach for
those on a repository with tens of thousands of issues.

A walk visits at most `PageWalk.MaxPages` (10 000) pages, so an instance that
offers a next page forever cannot hang your process. Reaching that cap with the
server still offering another page **fails** the `Future` with
`WalkTruncated(pagesVisited, resumeFrom)` rather than handing back what it had
gathered: a short answer shaped exactly like a complete one is the failure mode
this whole section exists to prevent. `resumeFrom` is the window the walk was
about to request, page size included, so continuing is `PageWalk.all(resumeFrom)`.
A listing whose last page happens to be the ten-thousandth and offers nothing
further has ended naturally and succeeds.

## Configuration

```scala
import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.UserAgent
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.paging.PageSize
import com.worxbend.codeberg4s.retry.RetryPolicy

import scala.concurrent.duration.DurationInt

val selfHosted: Either[ValidationError, CodebergConfig] =
  for
    base  <- BaseUri.from("https://my-forgejo.example/api/v1")
    agent <- UserAgent.from("my-app/1.0")
    size  <- PageSize.from(50)
  yield CodebergConfig(
    baseUri              = base,
    auth                 = Auth.Anonymous,
    retry                = RetryPolicy.Default,
    userAgent            = agent,
    defaultPageSize      = size,
    connectTimeout       = 10.seconds,
    readTimeout          = 30.seconds,
    maxResponseBodyBytes = CodebergConfig.DefaultMaxResponseBodyBytes,
    maxDownloadBodyBytes = CodebergConfig.DefaultMaxDownloadBodyBytes,
  )
```

`defaultPageSize` surfaces on the built client as `client.firstPage` — page 1
at that size — which is what listings and `PageWalk` should start from.

Every field naming a domain concept is a validated type, so a misconfigured
client fails at construction rather than on its first call. The timeouts and the
two byte bounds are plain quantities and are taken as given.
`CodebergConfig.toString` is safe to log: the credential types redact
themselves.

`Auth` is `Anonymous`, `Token(ApiToken)` or `Basic(username, Password)`.

### Response size

This library reads a whole response into memory; it does not stream. So every
request carries a byte bound, and a body that passes it is abandoned part-read
as `CodebergError.Transport(ctx, TransportCause.ResponseTooLarge(detail))`.

- `maxResponseBodyBytes` — 16 MiB, applied to every textual response. The
  largest JSON body Forgejo produces is a file's contents, a blob capped by the
  instance's `default_max_blob_size` (10 MiB on codeberg.org) and then
  base64-encoded, which costs four bytes per three; 16 MiB clears that.
- `maxDownloadBodyBytes` — 50 MiB, applied only to `client.downloads`, which
  fetches ZIP archives. An artifact is whatever a workflow uploaded, so nothing
  about `default_max_blob_size` bounds it, and one shared number would have had
  to be either too small for ordinary artifacts or too large to bound JSON
  usefully.

Exceeding either bound is **not** retried. Repeating the call would download the
oversized body once per attempt, which turns one oversized response into
`maxAttempts` of them.

### Retries

`RetryPolicy.Default` is 3 attempts, 250 ms base delay, 8 s ceiling, full
jitter, and it honours `Retry-After`. `RetryPolicy.Off` disables retrying
entirely.

Eligibility is decided per operation, not per policy:

- Every `GET` is retried — they are safe.
- `POST` and `PATCH` that create or edit something (`issues.create`,
  `pulls.merge`, `issues.createComment`, …) are **never** retried. Repeating
  them could file the same issue twice.
- The three mark-read calls (`notifications.markAllRead`,
  `markThreadRead`, `markRepositoryRead`) *are* retried despite being `PUT` and
  `PATCH`: they carry no body and no query, and marking an already-read thread
  read again is a no-op.

### Sharing an sttp backend

`CodebergClient(config)` creates and owns a backend, and `close()` shuts it
down. If your application already has one:

```scala
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergConfig

import sttp.client4.Backend

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

def sharedBackend: Backend[Future]
def config: CodebergConfig
given ExecutionContext = ExecutionContext.global

// close() will NOT close sharedBackend — you own it.
val shared: CodebergClient = CodebergClient.usingBackend(config, sharedBackend)
```

sttp models the connect timeout as a property of the backend rather than of a
request, so `connectTimeout` is ignored on this path — configure it on the
backend. `readTimeout` is per request and is honoured either way.

## Tokens are not logged, ever

`ApiToken` is a redacting type. Its `toString` is `***`, string interpolation of
it is `***`, and `reveal` is the only way to get the material out — a method
name you will notice in review. No `CodebergError` can contain a credential:
the URI inside `CallContext` is redacted before the context is built, and
`CodebergException`'s message is `CodebergError.describe`, which is assembled
only from that redacted context and from server-supplied text.

There are tests that assert exactly this, because a leaked token in an exception
message is the failure mode that matters most in a library like this one.

## Telemetry

The library has no logging dependency and writes nothing to stdout. If you want
request visibility, implement the `Telemetry` port and pass it at construction:

```scala
import com.worxbend.codeberg4s.CallContext
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.core.Telemetry

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

given ExecutionContext = ExecutionContext.global

final class PrintingTelemetry extends Telemetry[Future]:

  override def onRequest(ctx: CallContext): Future[Unit] =
    Future.successful(println(s"-> ${ctx.operation} ${ctx.method.wireName} ${ctx.uri}"))

  override def onResponse(ctx: CallContext, status: Int): Future[Unit] =
    Future.successful(println(s"<- ${ctx.operation} $status in ${ctx.durationMs}ms"))

  override def onError(ctx: CallContext, error: CodebergError): Future[Unit] =
    Future.successful(println(s"!! ${ctx.operation}: ${error.describe}"))

val observed: CodebergClient =
  CodebergClient(CodebergConfig(Auth.Anonymous), PrintingTelemetry())
```

Three guarantees worth knowing:

- **A telemetry failure never fails the call it was observing.**
  Instrumentation that breaks must not break the application it instruments.
- **Everything a callback receives is already redacted**, so an implementation
  cannot leak a credential by logging what it is handed.
- **`onRequest` and `onResponse` fire once per *attempt*.** A retried call
  produces several of each, which is how you see a retry storm.
  `onError` fires once per failed attempt and once more for the failure the
  caller finally receives.

`Telemetry.noOp` is the default and allocates nothing per call, so an
unconfigured client is completely silent.

## Development

```bash
./mill modules.__.compile      # warnings are errors
./mill modules.__.test         # unit tests (1072 today)
./mill modules.__.reformat     # scalafmt
./mill modules.__.fix          # scalafix
./verify.sh                    # the pre-handoff gate
./verify.sh --with-slow        # plus duplication and CRAP analysis
./verify.sh --nightly          # plus mutation testing
```

Every Scala block in this file compiles against the current sources under the
project's own flags (`-deprecation -feature -Wunused:all -Wvalue-discard
-Wnonunit-statement -Werror`). That check is manual today; wiring mdoc so the
build enforces it is a Phase 4 line in
[`docs/ROADMAP.md`](docs/ROADMAP.md).

`verify.sh` runs format check, lint, a zero-warning compile, the unit suite, an
architecture-boundary check and coverage, in that order. Its slow and nightly
steps skip with a printed notice when their runner script is absent, so read
the step output rather than trusting the exit code — see
[`docs/CONSTITUTION_MAPPING.md`](docs/CONSTITUTION_MAPPING.md) for what is
actually proven today.

### Integration tests

`modules/it` is the environmentally-unsuitable boundary: it needs Docker or the
live network, so `verify.sh` never runs it. Both suites tag every test
`Integration`.

```bash
# Container suite — starts codeberg.org/forgejo/forgejo:12, bootstraps an admin,
# a repository and a token, then exercises the client against it. Needs Docker.
./mill modules.it.test.testOnly com.worxbend.codeberg4s.it.ForgejoContainerSuite

# Override the image, e.g. to match what CI has cached:
FORGEJO_IT_IMAGE=codeberg.org/forgejo/forgejo:12 \
  ./mill modules.it.test.testOnly com.worxbend.codeberg4s.it.ForgejoContainerSuite

# Live read-only smoke against https://codeberg.org. Opt-in; without the
# variable every test is *skipped*, not failed, so a disabled run is visibly
# different from a run with nothing to do.
CODEBERG_IT=1 \
  ./mill modules.it.test.testOnly com.worxbend.codeberg4s.it.CodebergLiveSmokeSuite

# A token only widens the rate limit; the suite asserts nothing that needs one.
CODEBERG_IT=1 CODEBERG_IT_TOKEN=... \
  ./mill modules.it.test.testOnly com.worxbend.codeberg4s.it.CodebergLiveSmokeSuite

# Both, plus everything else in the module:
CODEBERG_IT=1 ./mill modules.it.test
```

The live suite is read-only by construction — six `GET`s against a repository
it does not own — and must stay that way.

## Design decisions

Recorded in [`docs/adr/`](docs/adr/). Start with
[ADR-0005](docs/adr/0005-future-public-api.md), which explains why the API is
`Future`-based, and [ADR-0001](docs/adr/0001-curated-models-over-codegen.md),
which explains why the models are hand-written rather than generated from the
Swagger spec.

The measured divergences between the pinned Swagger spec and what Codeberg
actually returns are in [`docs/HAZARDS.md`](docs/HAZARDS.md). Two of the
project's original assumptions turned out to be wrong there, which is why the
models are built from captured fixtures rather than from the spec.

## Changes

[`CHANGELOG.md`](CHANGELOG.md), Keep-a-Changelog format, semantic versioning.

## Licence

MIT. See [`LICENSE`](LICENSE).
