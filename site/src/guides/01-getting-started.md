# Getting started

For a developer who has never used codeberg4s, and who may be new to Scala: how
to add the library, build a client, make one anonymous call, and shut the client
down again.

## What this library is

codeberg4s is a client for the [Codeberg](https://codeberg.org) and
[Forgejo](https://forgejo.org) REST API v1. You give it a base URI and some
credentials; it gives you typed Scala values for repositories, issues, pull
requests, organisations and the rest. It does not shell out to `git`, and it is
not a Git implementation — it talks to the forge's HTTP API and nothing else.

Two things shape everything else in these guides:

- **Every call returns a `scala.concurrent.Future`.** No effect system is
  required of you, and none is added to your classpath.
  ([ADR-0005](../project/adr/0005-future-public-api.md) explains why.)
- **Identifiers are validated types, not `String`s.** `Owner.from("forgejo")`
  returns an `Either`, and there is no way to build an `Owner` that would forge
  a request path.

## Adding the dependency

**Nothing is published to Maven Central yet.** The build is configured for it —
five artifacts under the `com.worxbend` organisation, currently at
`0.1.0-SNAPSHOT` — but until the `0.1.0` tag is cut, the coordinates below
resolve only against a local publish. This is the first thing to know, because
a dependency line that does not resolve is where most people stop.

```scala
// Mill
def mvnDeps = Seq(mvn"com.worxbend::codeberg4s-client:0.1.0")
```

```scala
// sbt
libraryDependencies += "com.worxbend" %% "codeberg4s-client" % "0.1.0"
```

`codeberg4s-client` is the artifact you want. It pulls in `codeberg4s-transport`,
`codeberg4s-codec`, `codeberg4s-core` and `codeberg4s-domain` transitively.

If you only want to *handle* errors from this library in a module that does not
itself make requests — say, a shared error-rendering module — depend on
`codeberg4s-domain` instead. It contains the models and the error type and has
no dependencies at all, not even an HTTP client.

## `ExecutionContext`, for readers coming from another language

Every method on the client returns a `Future[A]`: a value that is not there yet
and that will either hold an `A` or hold a failure. That is Scala's equivalent
of a JavaScript `Promise`, a Python `awaitable`, or a Java `CompletableFuture`.

Unlike those, Scala's `Future` does not carry a built-in idea of *where* the
continuation runs. When you write `future.map(f)`, the runtime has to put `f`
on some thread. `ExecutionContext` is that decision, and Scala makes you state
it rather than picking a global default for you. It appears as a `using`
parameter — Scala 3's term for an argument the compiler supplies from a value
you have marked as available:

```scala
given ExecutionContext = ExecutionContext.global
```

That line makes one `ExecutionContext` available for the rest of the scope; every
`.map`, `.flatMap` and client constructor that needs one now finds it without
your writing it out. If you forget it, the compiler tells you it could not find
an `ExecutionContext` — that error means "say which thread pool", not "something
is broken".

`ExecutionContext.global` is a work-stealing pool sized to your CPU count and is
a reasonable place to start. In a real application you usually already have one,
from Akka/Pekko, from Play, or from a pool you created yourself; pass that one
instead. Because the client only ever waits on network I/O and never blocks a
thread on your behalf, it does not need a pool of its own.

## The whole program

Here is a complete file. It reads the star count of `forgejo/forgejo` from
codeberg.org without any credentials and prints it.

```scala
//> using scala 3.8.4
package example

import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object HelloCodeberg:

  def main(args: Array[String]): Unit =
    given ExecutionContext = ExecutionContext.global

    // The client owns an HTTP connection pool and one scheduler thread.
    // Build one per instance you talk to, for the lifetime of the program.
    val client: CodebergClient = CodebergClient(CodebergConfig(Auth.Anonymous))

    try
      val stars: Either[ValidationError, Future[Long]] =
        for
          owner <- Owner.from("forgejo")
          name  <- RepoName.from("forgejo")
        yield client.repos.get(owner, name).map(_.starsCount)

      stars match
        case Left(problem) => report(s"bad argument: ${problem.field} ${problem.message}")
        case Right(count)  => report(s"forgejo/forgejo has ${Await.result(count, 30.seconds)} stars")
    finally client.close()

  /** Writes one line to standard output.
    *
    * The library itself never writes anywhere and has no logging dependency, and this project's Scalafix
    * configuration bans the standard println across the repository for that reason. An example program is the one
    * place where showing the result *is* the point, so the exception is funnelled through this single named helper
    * rather than scattered through the code.
    */
  private def report(text: String): Unit =
    System.out.print(s"$text${System.lineSeparator()}")
```

That block is a whole compilable file, which is exactly why the site build does
not compile it: the documentation tool wraps each snippet in an enclosing scope,
and a top-level `object` with a `main` method cannot live inside one. Every line
in it that touches this library appears again below, in blocks the site build
does compile.

## Reading it back, piece by piece

### Building the client

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.auth.Auth

import scala.concurrent.ExecutionContext

given ExecutionContext = ExecutionContext.global

val client: CodebergClient = CodebergClient(CodebergConfig(Auth.Anonymous))
```

`CodebergConfig(Auth.Anonymous)` is a one-argument overload that fills in
everything else: `https://codeberg.org/api/v1` as the base URI, the default
retry policy, the `codeberg4s` user agent, a page size of 30, a 10-second
connect timeout and a 30-second read timeout. Copy the result to change one
field. [Self-hosted instances](./09-self-hosted.md) covers pointing it
elsewhere; [Authentication](./02-authentication.md) covers the credentials.

`CodebergClient(config)` creates its own HTTP backend. If your application
already has an sttp backend, hand it over with `CodebergClient.usingBackend` and
keep owning it — see [Testing your code](./07-testing-your-code.md), where that
is how a test substitutes a stub.

### Naming a repository

```scala mdoc:compile-only
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName

val target: Either[ValidationError, (Owner, RepoName)] =
  for
    owner <- Owner.from("forgejo")
    name  <- RepoName.from("forgejo")
  yield (owner, name)
```

`Owner.from` and `RepoName.from` return `Either[ValidationError, …]`. There is no
`Owner("forgejo")` constructor — that line does not compile — because a value
that reached a request path unchecked could contain a `/` and address a
different endpoint than the one you meant. The `for` comprehension above is
Scala's way of chaining several such checks: if any one of them fails, the whole
expression is the first `Left` and nothing further runs.

The types are *opaque*: at run time an `Owner` is a `String`, so there is no
wrapper object and no allocation, but at compile time it is a distinct type that
a bare `String` cannot be passed for. [Glossary](../reference/glossary.md)
defines the term.

### Making the call

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

def starsOf(client: CodebergClient, owner: Owner, name: RepoName)(using ExecutionContext): Future[Long] =
  client.repos.get(owner, name).map(_.starsCount)
```

`client.repos.get` returns a `Future[Repository]`, and `.map(_.starsCount)`
turns it into a `Future[Long]` without waiting for it.

The returned `Future` fails — that is, holds a failure rather than a value — when
the call does not succeed, carrying a `CodebergException` around the actual
error. There is a second rail, `client.repos.attempt.get`, that returns
`Future[Either[CodebergError, Repository]]` and never fails. Which to pick, and
what the failures mean, is [Errors](./03-errors.md).

### Waiting, once, at the edge

`Await.result` blocks the calling thread until the `Future` completes. It is the
right thing to do exactly once, at the outermost edge of a program that has
nothing else to do — a `main` method, a script. Inside a server or any code that
is itself asynchronous, do not use it: compose with `.map` and `.flatMap`
instead, and let the framework you are in wait at its own boundary.

### Closing the client

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.auth.Auth

import scala.concurrent.ExecutionContext

def usingAClient(work: CodebergClient => Unit)(using ExecutionContext): Unit =
  val client = CodebergClient(CodebergConfig(Auth.Anonymous))
  try work(client)
  finally client.close()
```

`close()` releases what the client owns: always the scheduler thread behind the
retry backoff, and the HTTP backend as well when the client created it. It is
idempotent and safe from any thread — the second and later calls do nothing —
so a `finally` block is the natural place for it.

A client you built with `CodebergClient.usingBackend` does **not** close that
backend; you passed it in, so you close it, after closing every client built on
it.

Do not build a client per request. Each one creates a scheduler thread, and
`CodebergClient(config)` creates a connection pool as well. A client is
immutable apart from its closed flag and is meant to be shared across your whole
application.

## Where to go next

- [Authentication](./02-authentication.md) — tokens, scopes, and why
  `ApiToken.from` returns an `Either`.
- [Errors](./03-errors.md) — the five failures, and how a `404` actually
  arrives.
- [Pagination](./04-pagination.md) — the most important guide here. Read it
  before you write a loop over a listing.
- [API groups](../reference/api-groups.md) — what lives where on the client.
