# Testing your code

For anyone who has written something on top of this client and now wants tests
that do not touch the network. This is the guide that decides whether the
library is pleasant to live with in a real codebase.

## The one design decision that makes this possible

`CodebergClient.usingBackend(config, backend)` builds a client on an sttp
`Backend[Future]` that **you** supply and **you** own. In production that is a
real connection pool; in a test it is a stub that answers from a string.

Everything else about the client is identical on that path: the same request
building, the same status mapping, the same retry engine, the same decoders,
the same two rails. A test written this way exercises the whole library except
the socket.

```scala mdoc:compile-only
import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.retry.RetryPolicy

import sttp.client4.Backend

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** A client for tests: a fixed instance, no credentials, and no retrying.
  *
  * `RetryPolicy.Off` matters. With the default policy a stub that answers `503`
  * is called three times with real sleeps in between, and a suite that should
  * take milliseconds takes seconds.
  */
def testClient(backend: Backend[Future], instance: BaseUri)(using ExecutionContext): CodebergClient =
  CodebergClient.usingBackend(
    CodebergConfig(Auth.Anonymous).copy(baseUri = instance, retry = RetryPolicy.Off),
    backend,
  )
```

Note the ownership rule: `client.close()` will **not** close a backend you passed
in. Close the backend yourself, after closing every client built on it. A
`BackendStub` holds no resources, so in most tests there is nothing to close at
all.

## Structuring your own code so it can be tested

Take the client as a parameter rather than constructing one:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** The unit under test: a small policy over the client, with no client of its own. */
final class ReleaseChecker(client: CodebergClient)(using ExecutionContext):

  def latestTag(owner: Owner, name: RepoName): Future[Option[String]] =
    client.repos
      .listReleases(owner, name, com.worxbend.codeberg4s.paging.PageParams.First)
      .map(page => page.items.headOption.map(_.tagName.value))
```

That is the whole trick. `ReleaseChecker` never decides where its client comes
from, so a test hands it one built on a stub and production hands it one built on
a real backend.

## Building a stub backend

```scala mdoc:compile-only
import sttp.client4.testing.BackendStub
import sttp.client4.testing.ResponseStub
import sttp.model.Header
import sttp.model.StatusCode

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** A backend that answers every request with the same status and body. */
def responding(status: Int, body: String)(using ExecutionContext): BackendStub[Future] =
  BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status)))

/** The same, with response headers — for asserting on pagination. */
def respondingWith(status: Int, body: String, headers: List[Header])(using
    ExecutionContext): BackendStub[Future] =
  BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(
    ResponseStub.adjust(body, StatusCode(status), headers)
  )

/** Answers a `503` first and then the real payload — for asserting that a call was repeated. */
def flakyThen(status: Int, body: String)(using ExecutionContext): BackendStub[Future] =
  BackendStub.asynchronousFuture.whenAnyRequest.thenRespondCyclic(
    ResponseStub.adjust("", StatusCode(503)),
    ResponseStub.adjust(body, StatusCode(status)),
  )
```

`BackendStub.asynchronousFuture` is sttp's `Future`-flavoured stub. Beyond
`whenAnyRequest` it also has `whenRequestMatches(predicate)`, which is how you
give different answers to different endpoints in one test.

## A complete test

This is a munit suite. The site build does not compile it, because a test
framework is not on the documentation classpath — but every line of it that
touches this library appears in a compiled block elsewhere in this guide.

```scala
package example

import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName
import com.worxbend.codeberg4s.retry.RetryPolicy

import sttp.client4.Backend
import sttp.client4.testing.BackendStub
import sttp.client4.testing.RecordingBackend
import sttp.client4.testing.ResponseStub
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

final class ReleaseCheckerSuite extends FunSuite:

  private given ExecutionContext = munitExecutionContext

  private val Instance: BaseUri = orFail(BaseUri.from("https://forge.example/api/v1"))
  private val Handle: Owner     = orFail(Owner.from("forgejo"))
  private val Name: RepoName    = orFail(RepoName.from("forgejo"))

  private val ReleasesBody: String =
    """[{"id":1,"tag_name":"v1.2.3","name":"1.2.3","draft":false,"prerelease":false}]"""

  test("the latest tag is the first release the instance returned"):
    onBackend(responding(200, ReleasesBody)): client =>
      ReleaseChecker(client).latestTag(Handle, Name).map(tag => assertEquals(tag, Some("v1.2.3")))

  test("an empty release list is None rather than a failure"):
    onBackend(responding(200, "[]")): client =>
      ReleaseChecker(client).latestTag(Handle, Name).map(tag => assertEquals(tag, None))

  test("a 404 reaches the caller as an Api failure carrying the status"):
    onBackend(responding(404, NotFoundBody)): client =>
      ReleaseChecker(client).latestTag(Handle, Name).failed.map:
        case CodebergException(CodebergError.Api(_, status, _)) => assertEquals(status, 404)
        case other                                              => fail(s"expected a CodebergException, got $other")

  test("the request goes to /repos/{owner}/{repo}/releases on the configured instance"):
    val backend = RecordingBackend(responding(200, ReleasesBody))

    onBackend(backend): client =>
      ReleaseChecker(client)
        .latestTag(Handle, Name)
        .map(_ => assertEquals(pathOf(backend), "https://forge.example/api/v1/repos/forgejo/forgejo/releases"))

  test("the first page is requested with page=1 and limit=30"):
    val backend = RecordingBackend(responding(200, ReleasesBody))

    onBackend(backend): client =>
      ReleaseChecker(client)
        .latestTag(Handle, Name)
        .map(_ => assertEquals(queryOf(backend), List("page" -> "1", "limit" -> "30")))

  // ---- helpers -------------------------------------------------------------

  private def responding(status: Int, body: String): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(ResponseStub.adjust(body, StatusCode(status)))

  private def onBackend[A](backend: Backend[Future])(use: CodebergClient => Future[A]): Future[A] =
    val client = CodebergClient.usingBackend(
      CodebergConfig(Auth.Anonymous).copy(baseUri = Instance, retry = RetryPolicy.Off),
      backend,
    )
    use(client).transform: outcome =>
      client.close()
      outcome

  private def dialled(backend: RecordingBackend): String =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.toString
      case None               => fail("no request reached the backend")

  private def pathOf(backend: RecordingBackend): String =
    val uri   = dialled(backend)
    val query = uri.indexOf('?')
    if query < 0 then uri else uri.take(query)

  private def queryOf(backend: RecordingBackend): List[(String, String)] =
    backend.allInteractions.headOption match
      case Some((request, _)) => request.uri.params.toSeq.toList
      case None               => fail("no request reached the backend")

  private def orFail[A](result: Either[ValidationError, A]): A =
    result match
      case Right(value) => value
      case Left(error)  => fail(s"invalid fixture: ${error.field} ${error.message}")

  private val NotFoundBody: String =
    """{"message":"GetRepositoryByName","url":"https://forge.example/api/swagger","errors":["repository does not exist"]}"""
```

Three habits in that suite are worth copying.

**`client.close()` in a `transform`**, so the scheduler thread is released
whether the assertion passed or failed. `transform` runs on both outcomes and
hands the outcome straight back.

**`orFail` for fixtures.** `Owner.from` returns an `Either`, and a bad fixture
should fail the suite with a clear message rather than fail the call under test
in a confusing one.

**Bodies are the smallest thing that decodes.** The library's own tests assert
decoding against captured fixtures; your tests should assert *your* behaviour,
so keep the payload down to the fields you actually read.

## Asserting on request shape

`RecordingBackend` wraps another backend and keeps every interaction:

```scala mdoc:compile-only
import sttp.client4.testing.RecordingBackend

/** The URI of the first recorded request, query string and all. */
def dialled(backend: RecordingBackend): Option[String] =
  backend.allInteractions.headOption.map((request, _) => request.uri.toString)

/** Its query parameters, in the order they were sent. */
def queryOf(backend: RecordingBackend): List[(String, String)] =
  backend.allInteractions.headOption match
    case Some((request, _)) => request.uri.params.toSeq.toList
    case None               => Nil

/** Its HTTP method, as `"GET"`, `"POST"`, and so on. */
def methodOf(backend: RecordingBackend): Option[String] =
  backend.allInteractions.headOption.map((request, _) => request.method.method)

/** Its body, as sttp renders it for display. */
def bodyOf(backend: RecordingBackend): Option[String] =
  backend.allInteractions.headOption.map((request, _) => request.body.show.stripPrefix("string: "))

/** How many requests reached the backend — the retry assertion. */
def attempts(backend: RecordingBackend): Int = backend.allInteractions.size
```

This is what you use to assert that the query you built is the query that was
sent — that a filter really became `state=closed`, that your pagination window
really became `page=3&limit=50`, that a command really serialised the field you
think it did.

Do not assert on the whole URI when you mean to assert on one parameter. A test
that pins the entire query string breaks when an unrelated parameter is added,
and tells you nothing about which part was wrong.

## Faking failures

Every failure this library reports can be produced from a stub.

**Any status.** `ResponseStub.adjust(body, StatusCode(403))` produces
`CodebergError.Api(ctx, 403, body)` with the body parsed as a Forgejo error
payload. Assert on the status and on `ApiErrorBody.errors`, not on `message` —
Forgejo's `message` is often a raw Go symbol name.

**A decoding failure.** Answer `200` with a payload that does not fit the model,
and you get `CodebergError.DecodingFailed(ctx, snippet, path, cause)`. `path`
renders as a JSONPath such as `$.id`, which is a precise thing to assert on.

**A retry.** `thenRespondCyclic(503, then the real body)` with a policy that
allows retries, and then assert `attempts(backend) == 2`. This is one of the few
places you want the retry policy *on*; keep the delays tiny:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.retry.Jitter
import com.worxbend.codeberg4s.retry.RetryPolicy

import scala.concurrent.duration.DurationInt

/** Retries promptly and predictably. The default policy's 250 ms base delay makes
  * every retry test a quarter of a second long, and full jitter makes it a
  * different quarter each time.
  */
val promptRetry: RetryPolicy = RetryPolicy(
  maxAttempts       = 3,
  baseDelay         = 1.milli,
  maxDelay          = 5.millis,
  jitter            = Jitter.None,
  respectRetryAfter = false,
)
```

**A transport failure** — the case where nothing reaches the server. Fail the
effect rather than answering:

```scala mdoc:compile-only
import sttp.client4.testing.BackendStub

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import java.net.ConnectException

/** A backend on which nothing connects, producing `CodebergError.Transport`. */
def refusing()(using ExecutionContext): BackendStub[Future] =
  BackendStub.asynchronousFuture.whenAnyRequest
    .thenRespondF(Future.failed(ConnectException("connection refused")))
```

The transport adapter classifies the exception by walking its cause chain, so a
`ConnectException` becomes `TransportCause.ConnectionFailed`, a
`SocketTimeoutException` becomes `TransportCause.Timeout`, an
`UnknownHostException` becomes `TransportCause.Dns`, and sttp's
`StreamMaxLengthExceededException` — thrown when a response body passes the
bound in `CodebergConfig` — becomes `TransportCause.ResponseTooLarge`. Assert on
the case, not on the `detail` string.

One caveat if you are testing the oversized-body path: `BackendStub` does not
apply `maxResponseBodyLength`, so failing the stub with a
`StreamMaxLengthExceededException` is how you produce that cause. A real backend
is what enforces the bound.

**A rate limit.** `StatusCode(429)`, optionally with a `Retry-After` header:

```scala mdoc:compile-only
import sttp.client4.testing.BackendStub
import sttp.client4.testing.ResponseStub
import sttp.model.Header
import sttp.model.StatusCode

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

def throttled(retryAfterSeconds: Int)(using ExecutionContext): BackendStub[Future] =
  BackendStub.asynchronousFuture.whenAnyRequest.thenRespond(
    ResponseStub.adjust(
      """{"message":"rate limit exceeded"}""",
      StatusCode(429),
      List(Header("Retry-After", retryAfterSeconds.toString)),
    )
  )
```

Only the delta-seconds form of `Retry-After` is understood; a date is treated as
absent. See [Retries and rate limits](./05-retries-and-rate-limits.md).

## Testing pagination

Pagination is decided by the `Link` header and by nothing else, so a stub that
sets one is a complete pagination fixture:

```scala mdoc:compile-only
import sttp.model.Header

/** The `Link` header of a page that has a following page, as Forgejo spells it. */
def linkWithNext(base: String, nextPage: Int, lastPage: Int, limit: Int): Header =
  Header(
    "Link",
    s"""<$base?limit=$limit&page=$nextPage>; rel="next",<$base?limit=$limit&page=$lastPage>; rel="last"""",
  )

/** The `Link` header of a last page: no `rel="next"` at all. */
def linkWithoutNext(base: String, limit: Int): Header =
  Header("Link", s"""<$base?limit=$limit&page=1>; rel="first"""")
```

Set the first on one response and the second on the next, with
`thenRespondCyclic`, and your walk-every-page code has a two-page collection to
walk. This is worth doing even for code you are sure about — it is precisely the
loop that fails silently in production. See
[Pagination](./04-pagination.md).

Add `x-total-count` when your code reads it, and — at least once — **write a test
where it is absent**, because several Forgejo endpoints omit it and `None` is not
zero.

## What not to test

Do not re-test the library. Whether `repos.get` decodes a repository correctly,
whether a `503` is retried, whether `Owner.from` rejects a slash — all of that
has tests already, against captured fixtures from the real instance. Your suite
should assert what *your* code does with the answers.

Do not test against codeberg.org from a unit suite. It is somebody else's
infrastructure, it rate-limits, and it changes. If you want an end-to-end check,
put it behind an environment variable so a normal run skips it visibly — the way
this repository's own `modules/it` does, where an unset `CODEBERG_IT` skips every
test rather than failing it.

## Next

- [Errors](./03-errors.md) — the failures you are faking.
- [Pagination](./04-pagination.md) — the behaviour most worth a test.
- [Writing data](./08-writing-data.md) — asserting that the body you built is
  the body that was sent.
