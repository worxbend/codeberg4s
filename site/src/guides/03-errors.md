# Errors

For anyone deciding how their code should react when a call does not work: what
each of the five failures means, which of the two error rails to use, and how a
`404` and a rate limit actually arrive.

## Everything that can go wrong, as one closed family

`com.worxbend.codeberg4s.CodebergError` is a Scala 3 `enum` with exactly six
cases. There is no seventh, and the compiler will tell you if you forget one in
a match.

| Case | What happened | What to do |
| --- | --- | --- |
| `Transport(ctx, cause)` | nothing reached the server at all | a safe read may be retried; the library already did, unless you turned retries off |
| `Api(ctx, status, body)` | the server answered with a non-2xx status | branch on `status` |
| `DecodingFailed(ctx, snippet, path, cause)` | a 2xx payload did not match the model | retrying will not help; `path` and `snippet` are what a bug report needs |
| `Validation(field, message)` | a smart constructor rejected an argument, before any request was built | fix the argument |
| `RetriesExhausted(ctx, attempts, last)` | the retry engine gave up | react to `last`; it is preserved verbatim |
| `WalkTruncated(pagesVisited, resumeFrom)` | a walk over every page hit its page cap while the server was still offering another | walk again from `resumeFrom`, or narrow the query |

Two things are deliberately absent, and getting this wrong is the single most
common mistake when writing against this library:

> **There is no `NotFound` case and no `RateLimited` case.** A `404` is
> `Api(ctx, 404, body)`. A `429` is `Api(ctx, 429, body)`, or
> `RetriesExhausted` wrapping one.

The status code is preserved verbatim rather than being sorted into named cases,
because Forgejo's own vocabulary is not consistent enough to name: it answers
`400` for some input validation and `422` for other input validation, both from
read-only endpoints. A closed set of named cases would have had to guess, and
guessing wrong in a library is worse than handing you the number.

### `TransportCause`

`Transport` carries why no usable response came back: `ConnectionFailed`,
`Timeout`, `Tls`, `Dns`, `Interrupted`, `ResponseTooLarge`, `Unknown`. Each
holds a short `detail` string taken from the underlying exception. Branch on the
case, never on the text.

A status code — including `500` — is never a transport cause. If the server
answered anything at all, you get `Api`.

`ResponseTooLarge` is the one case where something did begin to arrive. This
library reads whole bodies into memory, so every request carries a byte bound
(`CodebergConfig.maxResponseBodyBytes`, and `maxDownloadBodyBytes` for the ZIP
downloads); a body that passes it is abandoned part-read, which leaves no status
to map and no body to decode. It is also the one transport cause besides `Tls`
and `Interrupted` that is never retried — repeating the call would download the
oversized body again on every attempt.

## The two rails

Every operation exists twice.

**The convenience rail** is the method on the group itself. It returns
`Future[A]`, and on failure the `Future` fails with a `CodebergException`
carrying the whole `CodebergError`:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.repositories.Repository

import scala.concurrent.Future

def viaConvenience(client: CodebergClient, owner: Owner, name: RepoName): Future[Repository] =
  client.repos.get(owner, name)
```

**The typed rail** is the same operation under `.attempt`. It returns
`Future[Either[CodebergError, A]]` and the `Future` never fails:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.repositories.Repository

import scala.concurrent.Future

def viaTyped(client: CodebergClient, owner: Owner, name: RepoName): Future[Either[CodebergError, Repository]] =
  client.repos.attempt.get(owner, name)
```

`.attempt` is the convenience rail with its failure channel materialised — the
same call, the same implementation, the same values. Nothing is lost by picking
either one.

### Which to pick

Pick per call site, not per project.

Use the **convenience rail** when a failure at this point is exceptional and
should propagate: the enclosing operation cannot continue anyway, and something
further out will log it or turn it into a `500`. This is the idiomatic shape for
`Future`-based Scala, and it composes cleanly in a `for` comprehension because
you are not unpacking an `Either` inside a `Future` at every step.

Use the **typed rail** when a failure at this point is an expected outcome you
intend to branch on — a `404` that means "not synced yet", a `409` that means
"someone edited it first" — or when your codebase already models errors as
values and you do not want a second, exception-shaped channel next to it.

A useful default: the typed rail at the boundaries of your own domain logic,
where you are deciding something; the convenience rail in the plumbing that only
forwards results.

## How a `404` actually arrives

On the convenience rail, as a failed `Future` holding
`CodebergException(CodebergError.Api(ctx, 404, body))`. `CodebergException` is a
case class, so it pattern-matches directly:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.repositories.Repository

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

def repositoryOrNone(client: CodebergClient, owner: Owner, name: RepoName)(using
    ExecutionContext): Future[Option[Repository]] =
  client.repos
    .get(owner, name)
    .map(Some.apply)
    .recover:
      case CodebergException(CodebergError.Api(_, 404, _)) => None
```

Any case you do not handle stays a failed `Future`, carrying the same value, so
a `recover` block that only names `404` has not silently swallowed a `503`.

On the typed rail, as a `Left`:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.repositories.Repository

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

def typedRepositoryOrNone(client: CodebergClient, owner: Owner, name: RepoName)(using
    ExecutionContext): Future[Either[CodebergError, Option[Repository]]] =
  client.repos.attempt.get(owner, name).map:
    case Right(repository)                     => Right(Some(repository))
    case Left(CodebergError.Api(_, 404, _))    => Right(None)
    case Left(other)                           => Left(other)
```

### The body of a `404` is not what you expect

Measured against codeberg.org, `GET /repos/definitely/nonexistent-xyz` answers:

```json
{"message":"GetUserByName","url":"https://codeberg.org/api/swagger","errors":["user redirect does not exist [name: definitely]"]}
```

`message` is the name of the Go function that failed. `url` is a constant
pointing at the Swagger UI and is useless. The one human-readable sentence is in
`errors[0]`, and on a `401` there is no `errors` array at all.

So `ApiErrorBody` is `(message: Option[String], url: Option[String], errors: List[String])`,
every part defensive, and **never surface `message` alone to an end user**. If
you must render something, prefer `errors` and fall back to the status code.

## Rate limits

Forgejo reports rate limiting as an ordinary `429`. There is no special case for
it, and there is no `Retry-After` guarantee.

What you will see depends on your retry policy. With the default policy, the
retry engine treats `429` as retryable, honours `Retry-After` when the instance
sent one, waits, and tries again. By the time a rate limit reaches your code,
the library has usually already given up — so the value you receive is
`RetriesExhausted(ctx, attempts, Api(ctx2, 429, body))`, not a bare `Api`.

Handle both, because a `429` on a call that was not eligible for retry — most
writes are not — arrives unwrapped:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergError

def isRateLimited(error: CodebergError): Boolean =
  error match
    case CodebergError.Api(_, 429, _)                                 => true
    case CodebergError.RetriesExhausted(_, _, CodebergError.Api(_, 429, _)) => true
    case _                                                            => false
```

[Retries and rate limits](./05-retries-and-rate-limits.md) covers what to do
about it, and why Codeberg's rate-limit headers are not the ones you may be
expecting.

## What `RetriesExhausted` preserves

Three things, all of them:

- `ctx` — the `CallContext` of the attempt that ended the loop, so the operation
  and URI are still identifiable;
- `attempts` — how many were made, which is what tells you the difference
  between "the instance is briefly unhappy" and "the instance is down";
- `last` — the final underlying `CodebergError`, verbatim and undegraded.

A call that failed once and was *not* repeated — a `404`, a `POST` that is never
retried, a policy of `RetryPolicy.Off` — returns its failure unwrapped, because
there is nothing to explain. So `RetriesExhausted` genuinely means "we tried
more than once".

Unwrapping it in one place is usually worth doing:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergError

def underlying(error: CodebergError): CodebergError =
  error match
    case CodebergError.RetriesExhausted(_, _, last) => underlying(last)
    case other                                      => other
```

The recursion is not paranoia about deep nesting — the engine never nests more
than one level — but it makes the function total without a comment explaining
why one level is enough.

## `CallContext`: which call failed

Every remote failure carries one:

| Field | What it is |
| --- | --- |
| `operation` | a stable id such as `"repos.get"` or `"issues.list"`, one per endpoint; it never changes, so it is safe to alert on |
| `method` | the `HttpMethod` used |
| `uri` | the request URI, **already redacted** |
| `requestId` | the `x-request-id` response header, when the instance sent one |
| `durationMs` | wall-clock duration of the attempt |

`Validation` is the one case with no context, because there is no call yet: the
argument was rejected before a request was built.

`ValidationError` is another name for that same case — the name smart
constructors use in their result type, so `Owner.from(raw)` reads as
`Either[ValidationError, Owner]`. Because it *is* a `CodebergError`, a smart
constructor and a client call sequence in one `for`-comprehension:
`Either`'s `flatMap` widens the left type to `CodebergError` on its own, and
nothing has to be mapped from one error type to another in between.

`error.describe` renders all of it as one bounded, secret-free line — every
free-form fragment is truncated at 512 characters, and a `422` carrying hundreds
of field errors is elided with a count rather than logged in full. That is also
what `CodebergException.getMessage` returns.

## A complete worked recovery

A function that reads a repository and reacts to each kind of failure
differently: `404` is a legitimate absence, a rate limit is worth reporting as
such, a decoding failure is a bug in this library and should be surfaced loudly,
and anything else propagates.

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.repositories.Repository

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** What this application wants to know about a repository lookup. */
enum LookupOutcome:
  case Found(repository: Repository)
  case Absent
  case Throttled(afterAttempts: Int)
  case Broken(explanation: String)

def lookUp(client: CodebergClient, owner: Owner, name: RepoName)(using
    ExecutionContext): Future[LookupOutcome] =
  client.repos
    .get(owner, name)
    .map(repository => LookupOutcome.Found(repository))
    .recover:
      case CodebergException(CodebergError.Api(_, 404, _)) =>
        LookupOutcome.Absent

      case CodebergException(CodebergError.Api(_, 429, _)) =>
        LookupOutcome.Throttled(1)

      case CodebergException(CodebergError.RetriesExhausted(_, attempts, CodebergError.Api(_, 429, _))) =>
        LookupOutcome.Throttled(attempts)

      case CodebergException(failure @ CodebergError.DecodingFailed(_, _, _, _)) =>
        // A 2xx the model could not read is a defect in this library, not in the
        // instance. `describe` carries the JSON path and a bounded excerpt, which
        // is exactly what a bug report needs, and it cannot contain a credential.
        LookupOutcome.Broken(failure.describe)
```

Three things worth noticing about that block.

`recover` takes a `PartialFunction`, so anything it does not name — a
`Transport` failure, a `500`, a `403` — stays a failed `Future` and reaches
whoever called `lookUp`. That is deliberate: the four outcomes above are the
ones this function has an opinion about.

The `429` case appears twice because, as above, a rate limit arrives wrapped or
unwrapped depending on whether the call was retried.

`failure.describe` is used rather than the raw case fields, because `describe`
is the rendering with the length bounds and the redaction guarantees on it. Do
not build your own message out of `snippet` without bounding it; a remote party
chooses how long that string is.

### The same thing on the typed rail

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.Owner
import com.worxbend.codeberg4s.RepoName
import com.worxbend.codeberg4s.repositories.Repository

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

def typedLookUp(client: CodebergClient, owner: Owner, name: RepoName)(using
    ExecutionContext): Future[Either[String, Option[Repository]]] =
  client.repos.attempt.get(owner, name).map:
    case Right(repository)                  => Right(Some(repository))
    case Left(CodebergError.Api(_, 404, _)) => Right(None)
    case Left(other)                        => Left(other.describe)
```

Shorter, and every branch is checked by the compiler for exhaustiveness — which
is the argument for the typed rail in one line.

## Next

- [Pagination](./04-pagination.md).
- [Retries and rate limits](./05-retries-and-rate-limits.md).
- [Troubleshooting](./10-troubleshooting.md) — symptoms to causes.
