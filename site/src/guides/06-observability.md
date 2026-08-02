# Observability

For anyone who needs to see what the client is doing — in a log, in a metric, in
a trace. This library writes nothing anywhere by default; this is how you change
that.

## Why there is no logging dependency

codeberg4s has two dependencies: sttp client4 and upickle. That is the whole
list, and no logging framework will ever join it.

A published library that drags SLF4J, Logback and a configuration file behind it
is a nuisance to embed: it collides with the framework the application already
uses, it emits output nobody asked for, and it makes a diamond dependency out of
a version choice that has nothing to do with talking to a forge.

So the library defines a port and you implement it. That is one small trait
between your logging and this code, and it is the only coupling.

The other half of the same decision: **the library writes nothing to stdout or
stderr, ever.** An unconfigured client is completely silent. `Telemetry.noOp` is
the default and allocates nothing per call.

## The port

```scala
// com.worxbend.codeberg4s.core.Telemetry
trait Telemetry[F[_]]:
  def onRequest(ctx: CallContext): F[Unit]
  def onResponse(ctx: CallContext, status: Int): F[Unit]
  def onError(ctx: CallContext, error: CodebergError): F[Unit]
```

That block is the declaration as it stands in the sources, quoted rather than
compiled — a bare `trait` body with no implementations is not something the site
build can compile on its own. A working implementation follows below.

`F` is the effect the client runs in. On the published API that is always
`scala.concurrent.Future`, so you implement `Telemetry[Future]`.

## A complete implementation

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CallContext
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.core.Telemetry

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Reports every attempt to whatever the application already logs with.
  *
  * `record` stands in for your logger. Substitute the real call — an SLF4J
  * `Logger`, a `java.util.logging` handler, a metrics counter — and nothing else
  * about this class changes.
  */
final class LoggingTelemetry(record: String => Unit) extends Telemetry[Future]:

  override def onRequest(ctx: CallContext): Future[Unit] =
    Future.successful(record(s"-> ${ctx.operation} ${ctx.method.wireName} ${ctx.uri}"))

  override def onResponse(ctx: CallContext, status: Int): Future[Unit] =
    Future.successful(record(s"<- ${ctx.operation} $status in ${ctx.durationMs}ms"))

  override def onError(ctx: CallContext, error: CodebergError): Future[Unit] =
    Future.successful(record(s"!! ${ctx.operation}: ${error.describe}"))

def observedClient(record: String => Unit)(using ExecutionContext): CodebergClient =
  CodebergClient(CodebergConfig(Auth.Anonymous), LoggingTelemetry(record))
```

`CodebergClient.usingBackend(config, backend, telemetry)` is the same thing for a
backend you own.

## What `CallContext` carries

| Field | Type | What it is |
| --- | --- | --- |
| `operation` | `String` | a stable id such as `"repos.get"`, `"issues.list"`, `"version.get"` — one per endpoint, and it never changes |
| `method` | `HttpMethod` | the method used; `method.wireName` is `"GET"`, `"POST"`, … |
| `uri` | `String` | the request URI, **already redacted** |
| `requestId` | `Option[String]` | the instance's `x-request-id` header, when it sent one |
| `durationMs` | `Long` | wall-clock duration of the attempt |

`operation` is the field to build metrics and alerts on. It is a promise: the id
for an endpoint does not change between releases, so a dashboard keyed on
`"issues.list"` keeps working. Do not derive a label from `uri` instead — it
contains path parameters and will explode your metric cardinality.

`requestId` is what you quote when reporting a problem to an instance's
administrators. It is the instance's own correlation id, so it is the only thing
that lets them find your request in their logs.

`durationMs` is measured by the transport on both sides of the send, so it is the
time that one attempt took and not the time the whole retried call took.

## The URI you are handed is already redacted

This matters more than it may seem, because a telemetry implementation is
exactly the place a credential leaks in most codebases: somebody logs the
request, the request holds an `Authorization` header, and the token is now in a
log aggregator.

That cannot happen here. Credentials never reach the request URI at all — they
are applied as a header, in the transport adapter, at the one call site that
touches `ApiToken.reveal`. The URI in a `CallContext` is redacted before the
context is constructed, and nothing downstream reconstructs a URI from the
configuration.

Likewise, `CodebergError.describe` is built only from that redacted context and
from server-supplied text, and every free-form fragment is truncated at 512
characters. So `error.describe` is safe to log in full, and is also bounded:
a `422` carrying hundreds of field errors is elided with a count rather than
turned into a megabyte of log.

The practical rule: **log what you are handed.** Do not reach around the port for
the configuration, and do not build your own message out of the raw error case
fields without bounding them.

## When each callback fires

For one attempt, in order:

1. `onRequest`, before the request leaves;
2. the send;
3. `onResponse`, if a response arrived — whatever the status, including `500`;
4. `onError`, if the attempt failed.

The retry engine repeats that whole sequence, so **a retried call produces one
set per attempt**. That is how you see a retry storm: three `onRequest` calls
with the same `operation` inside a second.

`onError` is then called **once more** with the failure the caller finally
receives — which is `RetriesExhausted` when more than one attempt was made. So a
call that failed three times produces four `onError` calls: three attempt
failures and one final.

If you are counting failures for a metric, count the final one and ignore the
per-attempt ones, or you will report three failures for one failed call:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CallContext
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.core.Telemetry

import scala.concurrent.Future

/** Counts one failure per failed call rather than one per attempt.
  *
  * `RetriesExhausted` is only ever produced as the final failure of a retried
  * call, so treating it as the terminal event and every other case as terminal
  * *unless* a retry follows would need state. This version takes the simpler,
  * honest route: it counts attempts separately from calls, and lets the caller
  * decide which number it wanted.
  */
final class CountingTelemetry(attemptFailed: () => Unit, callGaveUp: () => Unit) extends Telemetry[Future]:

  private val done: Future[Unit] = Future.unit

  override def onRequest(ctx: CallContext): Future[Unit] = done

  override def onResponse(ctx: CallContext, status: Int): Future[Unit] = done

  override def onError(ctx: CallContext, error: CodebergError): Future[Unit] =
    error match
      case CodebergError.RetriesExhausted(_, _, _) => Future.successful(callGaveUp())
      case _                                       => Future.successful(attemptFailed())
```

That implementation is honest about its own limits: a call that fails once
without being retried produces a single non-`RetriesExhausted` `onError`, which
this counts as an attempt failure and not as a call failure. If you need exactly
one event per call, correlate on `ctx.operation` and `ctx.requestId` in your own
aggregation, or count successes and derive failures from the request count.

## Three guarantees

**A telemetry failure never fails the call it was observing.** Every callback's
result is discarded, exception and all. Instrumentation that breaks must not
break the application it instruments.

**Everything a callback receives is already redacted**, as above.

**`onRequest` and `onResponse` fire once per attempt**, so retries are visible
rather than hidden.

## One warning about blocking

Callbacks run on the client's execution path. An implementation that blocks —
writing synchronously to a slow appender, taking a lock, doing a network call of
its own — slows down every request the client makes.

Hand the work to your application's own executor instead:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CallContext
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.core.Telemetry
import com.worxbend.codeberg4s.syntax.discard

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Hands every observation to `elsewhere` and returns immediately.
  *
  * The client waits on the `Future` a callback returns — it flat-maps on it
  * before continuing — so returning an already-completed one is what keeps the
  * appender off the request path. The work itself runs on `elsewhere`.
  */
final class OffloadingTelemetry(sink: String => Unit)(using elsewhere: ExecutionContext) extends Telemetry[Future]:

  override def onRequest(ctx: CallContext): Future[Unit] =
    offload(s"-> ${ctx.operation}")

  override def onResponse(ctx: CallContext, status: Int): Future[Unit] =
    offload(s"<- ${ctx.operation} $status")

  override def onError(ctx: CallContext, error: CodebergError): Future[Unit] =
    offload(s"!! ${error.describe}")

  private def offload(line: String): Future[Unit] =
    // Deliberately not returned: the client must not wait for the appender. A
    // failure inside `sink` therefore reaches `elsewhere`'s reporter rather than
    // this method's caller, which is the trade this pattern makes.
    Future(sink(line)).discard
    Future.unit
```

`discard` there is `com.worxbend.codeberg4s.syntax.discard`, an extension the
library exposes for exactly this situation: a value that is deliberately thrown
away, said out loud at the call site rather than by silencing a compiler warning
project-wide.

## Turning it off

`Telemetry.noOp[Future]` is the default. Constructing a client without a
telemetry argument gives you exactly that, and it allocates nothing per call, so
there is no cost to leaving observation unconfigured.

## Next

- [Retries and rate limits](./05-retries-and-rate-limits.md) — what those extra
  `onRequest` calls mean.
- [Errors](./03-errors.md) — what is inside the `CodebergError` you are handed.
- [Testing your code](./07-testing-your-code.md) — a telemetry implementation is
  itself easy to test, because `CallContext` is a plain case class.
