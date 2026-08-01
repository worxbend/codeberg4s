---
title: Scala Code Style — codeberg4s
---

## Overview

This guide defines how we write Scala in **codeberg4s**. It is the Worxbend
[Scala Code Style](https://github.com/worxbend/worxbend/blob/main/SCALA_CODE_STYLE.md)
guide adapted to this repository: same rules and same reasoning, with examples
rewritten in this project's domain and the tooling section pinned to what this
build actually uses.

It is intentionally more than formatter rules: it describes API design, package
boundaries, error handling, concurrency, testing, and the small naming choices
that keep a codebase understandable after the original author has moved on.

The stack is Scala 3 on Mill. Code uses the `com.worxbend.codeberg4s` package
prefix, direct-style Scala, explicit boundaries, immutable domain models, and
boring JVM engineering discipline. Prefer code that a senior engineer from
another JVM language can read without learning local folklore.

> **Domain assumption.** This guide's examples treat codeberg4s as a Scala 3
> client for the Codeberg / Forgejo HTTP API — repositories, issues, pull
> requests, labels, paginated collections, token auth. If the project's scope is
> different, the rules all still hold; only the example nouns need swapping.

## Non-Negotiables

These rules are deliberately strict because they prevent broad classes of
production defects.

| Area        | Rule                                                                                 |
| ----------- | ------------------------------------------------------------------------------------ |
| Formatting  | Let Scalafmt format Scala, Mill, and Scala CLI files.                                |
| Packages    | Code lives under `com.worxbend.codeberg4s.<concept>`.                                |
| Syntax      | Scala 3 indentation syntax. Avoid braces unless required by syntax.                  |
| Types       | Public and protected members have explicit result types.                             |
| Mutation    | No shared mutable state. Local mutation needs a narrow, measured reason.             |
| Errors      | Recoverable failures are values, usually `Either[CodebergError, A]`.                 |
| Exceptions  | Throw only for defects or unrecoverable boundaries. Do not throw for normal control. |
| Nulls       | Do not use `null`; use `Option`, an ADT, or a validated domain type.                 |
| Concurrency | Use Ox scopes, Flow, channels, actors, or atomic values. Do not invent primitives.   |
| Resources   | Ownership and release order must be explicit.                                        |
| Pagination  | Never fetch an unbounded API collection eagerly. Page or stream it.                  |
| Secrets     | API tokens are wrapped in a redacting type and never logged.                         |
| Tests       | Each test verifies one behavior and avoids unrelated setup.                          |
| Warnings    | The codebase must compile with warnings treated as failures.                         |

## Tooling

The build is the authority. Local editor settings are useful only when they
match the repository configuration.

### Build And Format

Use Mill from the repository root for normal work:

```bash
./mill __.compile
./mill __.test
./mill __.reformat          # or: ./mill mill.scalalib.scalafmt/
./mill __.fix               # Scalafix
```

Pinned versions for this repository:

| Component | Version   | Notes                                                     |
| --------- | --------- | --------------------------------------------------------- |
| Scala     | 3.8.4     | Latest stable; pin in `build.mill`, do not float.         |
| Mill      | 0.12.x    | Pinned in `.mill-version`, which is committed.            |
| Scalafmt  | 3.11.4    | Pinned in `.scalafmt.conf`; matches the installed binary. |
| Ox        | 1.0.6     | Direct-style concurrency.                                 |

Resolve versions from the canonical resolver —
`https://repo1.maven.org/maven2/<group>/<artifact>_3/maven-metadata.xml` — and
take the latest stable, skipping `RC`, `M<n>`, `SNAP`, `alpha`, `beta`, and
`NIGHTLY`. Do not use `search.maven.org/solrsearch`; its index goes stale by
months. For discovery by name, use
`https://index.scala-lang.org/api/autocomplete?q=<name>` and confirm exact
coordinates on repo1.

Compiler settings are warning-heavy on purpose:

```scala
def scalacOptions = Seq(
  "-deprecation",
  "-feature",
  "-explain",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-Werror",
)
```

Do not weaken compiler options to make a warning disappear; fix the code, or
document a narrow, reviewed `@nowarn` with a comment explaining why.

### Formatter Rules

Scalafmt owns whitespace, import sorting, trailing commas, redundant braces, and
most line breaking. Do not hand-format against Scalafmt.

| Formatter setting          | Local policy                                                                  |
| -------------------------- | ----------------------------------------------------------------------------- |
| `maxColumn = 120`          | Hard limit. Prefer shorter expressions when the code becomes dense.           |
| `align.preset = most`      | Vertical alignment is on. Do not fight it; reformat instead of hand-aligning. |
| Trailing commas            | Required in multiline parameter, argument, and collection lists.              |
| Imports                    | Scalafmt and Scalafix organize imports. Never reorder by hand.                |
| Infix syntax               | `AvoidInfix` is on. Test DSLs and a small allow-list are the exceptions.      |
| Braces                     | `removeOptionalBraces = yes` — indentation syntax is enforced by the formatter.|

Because alignment is on, an unformatted commit produces noisy realignment diffs
later. Always reformat before committing.

### Scalafix Rules

Scalafix encodes several semantic rules in `.scalafix.conf`. Treat those rules
as design feedback, not bureaucracy.

| Rule family                   | Practical meaning                                                                     |
| ----------------------------- | ------------------------------------------------------------------------------------- |
| `ExplicitResultTypes`         | Public and protected API signatures must stay stable during refactors.                |
| `DisableSyntax.noVars`        | Avoid mutation by default; local mutation only where the rule is intentionally relaxed.|
| `DisableSyntax.noThrows`      | Recoverable failures belong in return types.                                          |
| `DisableSyntax.noNulls`       | `null` is not a domain value.                                                         |
| `DisableSyntax.noReturns`     | A method result is the final expression.                                              |
| `DisableSyntax.noDefaultArgs` | Prefer explicit overloads, configuration types, or options over hidden defaults.      |
| `NoValInForComprehension`     | Extract named steps before the comprehension.                                         |
| `OrganizeImports`             | Imports converge automatically across IDEs and CI.                                    |

## Packages And Files

Package structure is design. A package name tells readers what concept they are
inside and where a boundary begins.

### Package Names

All code lives under `com.worxbend.codeberg4s`:

```scala
package com.worxbend.codeberg4s.issues

import java.time.Instant

final case class IssueOpened(issueId: IssueId, openedAt: Instant)
```

Package names are lowercase words separated by dots. Use concept names that
match the API surface being modelled — `repositories`, `issues`, `pulls`,
`labels`, `users`, `auth`, `http`, `paging`. Avoid mechanism-only names such as
`common`, `core`, `helpers`, `utils`, or `misc` unless the package is tiny and
genuinely cross-cutting.

Suggested top-level shape:

```
com.worxbend.codeberg4s
├── auth          — tokens, credential types
├── http          — transport, request building, rate-limit handling
├── paging        — page/limit types, Flow-based pagination
├── repositories  — repository domain + client
├── issues        — issue domain + client
├── pulls         — pull-request domain + client
└── model         — shared domain types crossing several concepts
```

### File Names

For a primary type named `IssueClient`, use `IssueClient.scala`. A sealed family
may live in one file when the family is normally read as one concept.

Use one file for this kind of small ADT:

```scala
package com.worxbend.codeberg4s.issues

import java.time.Instant

sealed trait IssueState

object IssueState:
  final case class Open(openedAt: Instant) extends IssueState
  final case class Closed(closedAt: Instant, reason: CloseReason) extends IssueState
```

Do not create files named `Types.scala`, `Models.scala`, or `Helpers.scala`. If
the only shared property is "these are types", split the file by concept.

### Visibility

Choose visibility when creating a type. Public is a promise; private is a design
tool. This matters more than usual here: codeberg4s is a **library**, so every
public type is an API commitment to downstream users.

```scala
package com.worxbend.codeberg4s.issues

import java.time.Instant

private[issues] final case class IssueRow(number: IssueNumber, title: String, updatedAt: Instant)

private[codeberg4s] trait IssueTransport:
  def fetchPage(slug: RepoSlug, page: Page): Either[CodebergError, List[IssueRow]]

final class IssueClient(transport: IssueTransport):
  def list(slug: RepoSlug, page: Page): Either[CodebergError, List[Issue]] =
    transport.fetchPage(slug, page).map(_.map(Issue.fromRow))
```

Use default public visibility only for APIs intended to be used by library
consumers. Start narrow, then widen after seeing a real call site. Widening is
cheap; narrowing is a breaking change.

## Naming

Names are part of the API. Optimizing for a few fewer characters is rarely worth
the loss in searchability and review clarity.

### Type Names

Use `UpperCamelCase` for classes, traits, enums, objects, and type aliases.
Names should be nouns or noun phrases unless the trait represents a capability
such as `Readable`.

Prefer specific names:

| Prefer                     | Avoid                            |
| -------------------------- | -------------------------------- |
| `ForgejoIssueClient`       | `FIssueCli`                      |
| `SttpCodebergTransport`    | `HttpTransportImpl`              |
| `IssueState`               | `State` outside a package.       |
| `RateLimitPolicy`          | `RateLimitHelper`                |
| `Clock`                    | `ClockTrait`                     |

Do not suffix names with `Class`, `Trait`, or `Object`. The language construct
should not leak into the domain name.

### Methods And Values

Use `lowerCamelCase` for methods, values, parameters, and fields. Avoid
JavaBean-style getters.

| Intent                     | Prefer                            | Avoid                             |
| -------------------------- | --------------------------------- | --------------------------------- |
| Accessor                   | `issue.title`                     | `issue.getTitle`                  |
| Predicate                  | `issue.isClosed`                  | `issue.closed`                    |
| Side effect                | `client.close(issueNumber)`       | `client.setClosed(issueNumber)`   |
| Domain query               | `issues.findByNumber(number)`     | `issues.getIssueByNumber(number)` |
| Public collection variable | `issues`                          | `issueList`                       |

Use active verbs for side-effecting operations. Use noun-like names for pure
values. Do not repeat the receiver in the method name: prefer
`issueClient.findByNumber(n)` over `issueClient.findIssueByNumber(n)`.

### Constants

Constants live in the companion of the type that owns the concept. Use
`UpperCamelCase`, not Java-style all caps:

```scala
package com.worxbend.codeberg4s.http

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

final class RetryPolicy(maxAttempts: Int, baseDelay: FiniteDuration)

object RetryPolicy:
  val DefaultMaxAttempts: Int = 3
  val DefaultBaseDelay: FiniteDuration = 250.millis
```

If a constant is API-specific (a default page size, an endpoint path segment),
put it near the concept it belongs to. If it is deployment-specific, put it in
typed configuration.

### Acronyms And Abbreviations

Treat acronyms as words: `HttpClient`, `JsonCodec`, `ApiToken`. Short JVM terms
such as `IO`, `DB`, and `JVM` are acceptable when they are clearer than the
expanded form. `PR` is acceptable in local scopes; prefer `PullRequest` in
public API.

Avoid abbreviations in public API unless the abbreviation is more recognizable
than the full word. Local variables in tiny scopes may be short when the meaning
is obvious.

## Types And Domain Modeling

Scala code should make invalid states hard to represent. If a reviewer needs to
ask "can this be empty?" or "what does true mean?", the type probably needs
work.

### Explicit Result Types

Public and protected members need explicit result types:

```scala
package com.worxbend.codeberg4s.repositories

import java.time.Instant

final class RepositoryClient(transport: RepositoryTransport):
  def find(slug: RepoSlug): Either[CodebergError, Repository] =
    transport.fetch(slug).toRight(CodebergError.NotFound(slug.value))

  def lastPushedAt(repository: Repository): Option[Instant] =
    repository.pushedAt
```

Local values may rely on inference when the type is obvious. Add a type when the
inferred type is wide, structural, path-dependent, or part of the reader's
understanding.

### Opaque Types

Wrap primitive domain values. A raw `String`, `Int`, or `Boolean` rarely tells
the truth at a boundary — and every one of these arrives as a raw JSON field:

```scala
package com.worxbend.codeberg4s.repositories

opaque type Owner = String

object Owner:
  def from(value: String): Either[OwnerError, Owner] =
    val normalized = value.trim
    if normalized.isEmpty then Left(OwnerError.Empty)
    else if normalized.contains('/') then Left(OwnerError.ContainsSlash(normalized))
    else Right(normalized)

  extension (owner: Owner) def value: String = owner

enum OwnerError:
  case Empty
  case ContainsSlash(value: String)
```

Use opaque types for owners, repository names, issue and pull-request numbers,
label names, commit SHAs, API tokens, page numbers, and page sizes. Validate at
construction and keep invalid data out of the core domain.

A composite identifier deserves a real type rather than two loose strings:

```scala
package com.worxbend.codeberg4s.repositories

final case class RepoSlug(owner: Owner, name: RepoName):
  def value: String = s"${owner.value}/${name.value}"
```

### Enums And ADTs

Use Scala 3 enums or sealed traits for closed sets. Do not use
`scala.Enumeration`:

```scala
package com.worxbend.codeberg4s.pulls

import java.time.Instant

enum PullRequestState:
  case Open(openedAt: Instant)
  case Merged(mergedAt: Instant, mergedBy: Username)
  case Closed(closedAt: Instant)
```

Use separate cases or separate types for different lifecycle states. Do not
model lifecycle with many optional fields — `mergedAt: Option[Instant]` beside
`closedAt: Option[Instant]` lets a caller construct a pull request that is both
and neither.

### Boolean Blindness

Avoid Boolean parameters and Boolean return values when the meaning is
domain-specific.

Use a named enum instead:

```scala
package com.worxbend.codeberg4s.issues

enum IssueFilter:
  case OpenOnly
  case ClosedOnly
  case All

final class IssueQuery:
  def list(slug: RepoSlug, filter: IssueFilter): Either[CodebergError, List[Issue]] =
    ???
```

`list(slug, includeClosed = true)` forces every caller and reviewer to remember
what `true` meant. Boolean is fine for simple predicates such as
`Issue#isClosed`. It is not fine for method arguments.

### Option, Either, And Errors

Use `Option[A]` for absence when absence needs no extra explanation. Use
`Either[E, A]` when the caller needs to know why an operation failed. Use a
domain ADT for `E` once there is more than one meaningful reason.

Name nested values by shape when it improves readability:

| Shape                  | Naming pattern    |
| ---------------------- | ----------------- |
| `Option[Issue]`        | `maybeIssue`      |
| `Either[E, Issue]`     | `issueOrError`    |
| `List[Issue]`          | `issues`          |
| `NonEmptyList[Issue]`  | `nonEmptyIssues`  |

Avoid deep nesting such as `Either[CodebergError, Option[A]]` unless the two
dimensions mean different things. For this library they usually do not: a 404
from the API is an error case (`NotFound`), not a `Right(None)`. Pick one and
document it.

## Functions And Methods

A function should either perform one logical operation or orchestrate a short
sequence of named steps. Long functions often hide missing concepts.

### One Concern Per Function

Prefer an orchestration method whose body reads as a process:

```scala
package com.worxbend.codeberg4s.issues

import java.time.Clock
import java.time.Instant

final class IssueService(client: IssueClient, labels: LabelClient, clock: Clock):
  def openWithLabels(slug: RepoSlug, command: OpenIssue): Either[CodebergError, Issue] =
    for
      validated <- validateCommand(command)
      created   <- client.create(slug, validated)
      labelled  <- applyLabels(slug, created, command.labels)
      recorded  <- recordOpened(labelled, clock.instant())
    yield recorded

  private def validateCommand(command: OpenIssue): Either[CodebergError, ValidOpenIssue] =
    ValidOpenIssue.from(command)

  private def applyLabels(
      slug: RepoSlug,
      issue: Issue,
      names: List[LabelName],
  ): Either[CodebergError, Issue] =
    labels.attach(slug, issue.number, names).map(_ => issue)
```

Extract a step when naming the step makes the workflow easier to audit, even if
the step has one call site.

### Accessors And Parentheses

Use no parentheses for pure accessor-like methods. Use parentheses for methods
that compute, allocate, perform effects, or depend on time or the network:

```scala
package com.worxbend.codeberg4s.issues

final class Issue(private val comments: List[Comment], val number: IssueNumber):
  def commentCount: Int =
    comments.size

  def refresh(client: IssueClient, slug: RepoSlug): Either[CodebergError, Issue] =
    client.findByNumber(slug, number)
```

Keep this distinction consistent because it communicates whether calling the
method is observational or operational. In an API client this is load-bearing:
a reader must be able to tell at a glance which member calls the network.

### For-Comprehensions

Use for-comprehensions when sequencing the same effect or result type. Keep
branches and calculations out of the generator list.

Extract conditional work before the comprehension:

```scala
package com.worxbend.codeberg4s.pulls

final class MergeService(pulls: PullRequestClient, audit: AuditLog):
  def merge(slug: RepoSlug, command: MergeCommand): Either[CodebergError, MergedPullRequest] =
    val strategy = normalizeStrategy(command.strategy)

    for
      pull   <- pulls.findByNumber(slug, command.number)
      merged <- pulls.merge(slug, pull, strategy)
      _      <- audit.recordMerged(merged)
    yield merged

  private def normalizeStrategy(strategy: MergeStrategy): MergeStrategy =
    strategy.canonical
```

Do not flatten nested monadic values after the fact. Sequence the operation that
produces the inner value inside the comprehension.

### No Return Keyword

Do not use `return`. A method returns its final expression. Early exits should
be modeled with `Either`, small extracted methods, or pattern matching.

## Imports

Imports should converge automatically. Manual import style fights are wasted
review time.

### Ordering

Scalafmt and Scalafix own import order. The effective order is: `com.worxbend`
imports, then ecosystem libraries (Ox, sttp, Tapir, jsoniter), then other
third-party libraries, then `scala`, then `java`.

Prefer explicit imports for normal APIs:

```scala
package com.worxbend.codeberg4s.http

import com.worxbend.codeberg4s.auth.ApiToken

import sttp.client4.SyncBackend

import java.time.Instant

final class CodebergTransport(backend: SyncBackend, token: ApiToken):
  def get(path: String, at: Instant): Either[CodebergError, String] =
    ???
```

Wildcard imports need a reason: syntax modules (`import ox.*`), enum cases in
tiny scopes, or libraries whose documented style depends on a prelude import.
Do not wildcard-import broad application packages.

### Aliases

Use aliases when two libraries expose the same short name or when a Java type
would confuse a Scala collection type:

```scala
package com.worxbend.codeberg4s.interop

import java.util.List as JList

final class JavaCompatClient:
  def listIssues(): JList[Issue] =
    ???
```

Keep aliases local to the file. A package-wide alias usually means the boundary
type needs a better name.

## Error Handling

Error handling is API design. It controls what callers can recover from and what
operations can be retried safely. For a client library this is the most visible
part of the API — get it right before anything else.

### The Error ADT

One sealed family covers everything a caller can act on:

```scala
package com.worxbend.codeberg4s

import scala.concurrent.duration.FiniteDuration

enum CodebergError:
  case NotFound(resource: String)
  case Unauthorized
  case Forbidden(reason: String)
  case RateLimited(retryAfter: Option[FiniteDuration])
  case Validation(errors: List[String])
  case Conflict(reason: String)
  case Transport(cause: String)
  case Malformed(detail: String)
```

Map HTTP status codes onto this ADT once, at the transport boundary, and never
leak a raw status code or response body into the domain:

```scala
package com.worxbend.codeberg4s.http

private[codeberg4s] object StatusMapping:
  def toError(status: Int, body: String, retryAfter: Option[FiniteDuration]): CodebergError =
    status match
      case 401       => CodebergError.Unauthorized
      case 403       => CodebergError.Forbidden(body)
      case 404       => CodebergError.NotFound(body)
      case 409       => CodebergError.Conflict(body)
      case 422       => CodebergError.Validation(List(body))
      case 429       => CodebergError.RateLimited(retryAfter)
      case other     => CodebergError.Transport(s"unexpected status $other")
```

Use error ADTs rather than strings when callers branch on the error. Strings are
for human messages at the edge.

`RateLimited` carries `retryAfter` because a caller cannot implement backoff
without it. This is the general rule: an error case must carry whatever the
caller needs to recover.

### Exceptions

Exceptions are for defects, violated invariants, failed application startup, or
third-party APIs that cannot express failure as values. Catch the most specific
exception at the boundary and convert it immediately.

Use a value boundary around exception-throwing libraries:

```scala
package com.worxbend.codeberg4s.http

import scala.util.control.NonFatal

final class SttpTransport(backend: SyncBackend):
  def send(request: CodebergRequest): Either[CodebergError, CodebergResponse] =
    try Right(CodebergResponse.from(backend.send(request.underlying)))
    catch
      case NonFatal(error) => Left(CodebergError.Transport(error.getMessage))
```

Do not log and rethrow except at a top-level process boundary. Duplicate logging
produces noisy incidents and hides the first useful failure.

### Pattern Matching

Pattern match sealed families instead of probing with unsafe accessors.
Exhaustiveness is one of Scala's main advantages over Java-style status codes:

```scala
package com.worxbend.codeberg4s.issues

final class IssuePresenter:
  def label(state: IssueState): String =
    state match
      case IssueState.Open(_)     => "Open"
      case IssueState.Closed(_, _) => "Closed"
```

Never use `Option#get` or `Either#getOrElse(throw …)`. If absence is impossible,
use a type that proves it before reaching that code path.

## Collections And Data Flow

Choose collections by semantics. The type should tell readers how the data is
used.

### Collection Choice

| Need                                          | Prefer                                                  |
| --------------------------------------------- | ------------------------------------------------------- |
| Ordered finite values                         | `List[A]`                                               |
| Indexed access or append-heavy immutable data | `Vector[A]`                                             |
| Optional value                                | `Option[A]`                                             |
| At least one value                            | A non-empty collection type                             |
| Binary data (release assets, raw blobs)       | `Array[Byte]` or a streamed source                      |
| Java interop                                  | Java collection at the boundary only                    |
| A paginated API collection                    | `Flow[A]` — never `List[A]`                             |

Do not expose mutable collections from APIs. If a third-party library returns
mutable data, copy it into an immutable domain value at the boundary.

### Pagination Is The Default

This is the rule that matters most in this project. A repository can have tens of
thousands of issues; a `List[Issue]` return type is a latent outage.

Give callers both shapes and make the eager one explicit about its bound:

```scala
package com.worxbend.codeberg4s.issues

import ox.flow.Flow

final class IssueClient(transport: IssueTransport):

  /** One page. The caller controls paging. */
  def listPage(slug: RepoSlug, page: Page): Either[CodebergError, IssuePage] =
    transport.fetchPage(slug, page)

  /** All issues, fetched lazily one page at a time. Never materialized whole. */
  def listAll(slug: RepoSlug): Flow[Issue] =
    Flow
      .unfold(Option(Page.First)): maybePage =>
        maybePage.flatMap: page =>
          listPage(slug, page).toOption.map(result => (result.issues, result.nextPage))
      .mapConcat(identity)
```

For bounded parallel work over a page, use a Flow pipeline with an explicit
parallelism bound:

```scala
package com.worxbend.codeberg4s.issues

import ox.flow.Flow

final class IssueEnricher(comments: CommentClient):
  def withComments(slug: RepoSlug, issues: List[Issue]): List[EnrichedIssue] =
    Flow
      .fromIterable(issues)
      .mapPar(8)(issue => EnrichedIssue(issue, comments.list(slug, issue.number)))
      .runToList()
```

Document ordering requirements. Use unordered parallelism only when result order
has no business meaning. Remember that parallel fan-out against a rate-limited
API is a good way to get `429`s — bound it and honour `RateLimited.retryAfter`.

### Tuples

Tuples are fine for tiny local transformations. Do not expose tuples in public
APIs when a named case class would explain the fields.

Use a named type at boundaries:

```scala
package com.worxbend.codeberg4s.paging

final case class IssuePage(issues: List[Issue], nextPage: Option[Page], totalCount: Option[Int])
```

`(List[Issue], Option[Page])` forces every caller to remember which element is
which. Avoid tuple accessors such as `_1` and `_2` in meaningful code. Pattern
matching with names is clearer when a tuple is unavoidable.

## Configuration, Time, And External State

Hidden dependencies make tests unreliable and production behavior surprising.

### Typed Configuration

Configuration should be loaded once near the client's construction, validated
into typed values, and passed through constructors.

Use duration and size types rather than raw numbers:

```scala
package com.worxbend.codeberg4s

import scala.concurrent.duration.FiniteDuration

final case class CodebergConfig(
    baseUri: BaseUri,
    token: ApiToken,
    connectTimeout: FiniteDuration,
    readTimeout: FiniteDuration,
    defaultPageSize: PageSize,
    retry: RetryPolicy,
)
```

`baseUri` is configuration, not a constant: the same client must work against
`codeberg.org` and any self-hosted Forgejo instance. Never hardcode the host.

Do not encode timeouts as unlabelled integers. `30.seconds` and
`connectTimeout = 30.seconds` are readable; `30000` is not.

### Time And Randomness

Pass time, randomness, and ID generation explicitly. Do not hide them inside
domain logic — a client that computes backoff from `System.nanoTime()` internally
cannot be tested without sleeping.

```scala
package com.worxbend.codeberg4s.http

import java.time.Clock
import java.time.Instant

final class RateLimitTracker(clock: Clock):
  def remainingWindow(resetAt: Instant): java.time.Duration =
    java.time.Duration.between(Instant.now(clock), resetAt)
```

This makes tests deterministic and makes production behavior explicit.

### Secrets

API tokens never belong in source code, tests, logs, error messages, or
generated docs. Wrap them in a type whose display behavior is redacted:

```scala
package com.worxbend.codeberg4s.auth

opaque type ApiToken = String

object ApiToken:
  def from(value: String): Either[TokenError, ApiToken] =
    if value.trim.isEmpty then Left(TokenError.Empty) else Right(value)

  extension (token: ApiToken)
    /** Only for building the Authorization header. Never log this. */
    def reveal: String = token

    def redacted: String = "***"

enum TokenError:
  case Empty
```

Two specific hazards in this project: never put the token in a `CodebergError`
payload, and never include it in a request-echo debug log. Assert on that in a
test.

## Concurrency And Lifecycle

Concurrency is an ownership problem before it is a performance problem. Every
fork, channel, backend, and file handle needs a parent scope and a shutdown
path.

### Use Ox

Use Ox for direct-style concurrency:

| Need                               | Default pattern                        |
| ---------------------------------- | -------------------------------------- |
| Small fixed parallel work          | Ox parallel helpers in a local scope.  |
| Collection or stream processing    | `Flow`.                                |
| Mailbox or producer-consumer queue | `Channel[A]`.                          |
| Serialized mutable object          | `Actor`.                               |
| Background poller                  | Daemon fork tied to the app scope.     |
| Worker that must drain             | User fork plus explicit channel close. |

Avoid raw threads, ad hoc executors, blocking queues, lifecycle booleans, and
hand-rolled schedulers. Use Java concurrency primitives only for small atomic
state or when bridging a foreign API.

Virtual threads are never preempted. Long CPU-bound work — parsing a very large
JSON response, diffing big blobs — belongs in `computeIntensive`, or must
`cede()` roughly once per millisecond, or it will starve every other fork in the
process.

### Scope Ownership

Accept an Ox scope only when work or resources must attach to the caller's
lifetime. Otherwise create a local supervised scope and join before returning.

Use a factory for owned workers:

```scala
package com.worxbend.codeberg4s.webhooks

import ox.Ox
import ox.channels.Channel
import ox.channels.ChannelClosed
import ox.forkUserDiscard
import ox.repeatWhile

final class WebhookWorker private[webhooks] (mailbox: Channel[WebhookEvent]):
  def submit(event: WebhookEvent): Unit =
    mailbox.send(event)

  def close(): Unit =
    mailbox.doneOrClosed().discard

object WebhookWorker:
  def start(handler: WebhookHandler)(using Ox): WebhookWorker =
    val mailbox = Channel.bufferedDefault[WebhookEvent]
    forkUserDiscard:
      repeatWhile:
        mailbox.receiveOrClosed() match
          case event: WebhookEvent =>
            handler.handle(event)
            true
          case ChannelClosed.Done =>
            false
          case ChannelClosed.Error(error) =>
            throw error
    WebhookWorker(mailbox)
```

Constructors should not capture an Ox capability. A factory can start the worker
and return a plain value with a clear close operation.

A fork's result is its **return value** (`fork { … }.join()`), not something
published through a shared `AtomicReference`. Never return an object that owns
running forks to be driven later — its lifetime escapes every scope and
cancellation becomes manual again.

### Resource Safety

Acquire resources inside the scope that owns them and release them in reverse
order. Prefer Ox resource helpers for scoped resources and `scala.util.Using`
for a small local Java-style resource.

Make ownership visible:

```scala
package com.worxbend.codeberg4s

import ox.Ox
import ox.useCloseableInScope

final case class CodebergClient(issues: IssueClient, repositories: RepositoryClient)

object CodebergClient:
  def create(config: CodebergConfig)(using Ox): CodebergClient =
    val backend = useCloseableInScope(HttpClientSyncBackend())
    val transport = CodebergTransport(backend, config)
    CodebergClient(IssueClient(transport), RepositoryClient(transport))
```

The sttp backend is the one resource in this library that must be closed. Do not
create one per request, and do not return a client whose backend belongs to a
scope that already ended. Do not rely on finalizers.

Note that a blocking `java.io` read — a streamed release-asset download, an SSE
connection — is **not** interrupted by scope cancellation. Such reads need
explicit teardown in the scope body before the join.

## HTTP, JSON, And Boundaries

Boundaries translate between the outside world and the domain. Keep that
translation explicit. In this project the boundary *is* the product, so it gets
the most design attention.

### DTOs And Domain Types

Use DTOs for wire formats and domain types for business rules. Convert at the
boundary:

```scala
package com.worxbend.codeberg4s.issues.wire

final case class IssueDto(number: Long, title: String, state: String, body: Option[String])

object IssueDto:
  def toDomain(dto: IssueDto): Either[CodebergError, Issue] =
    for
      number <- IssueNumber.from(dto.number).left.map(_ => CodebergError.Malformed("issue number"))
      state  <- IssueState.parse(dto.state).left.map(_ => CodebergError.Malformed("issue state"))
    yield Issue(number, dto.title, state, dto.body)
```

Do not let Forgejo's JSON quirks leak into the domain. The API returns states as
lowercase strings, nullable fields as `null`, and timestamps as RFC-3339 strings
— all of that gets normalized once, in the `wire` package, and never appears
again.

Conversion returns `Either` because the wire format is not under our control. A
DTO that fails to convert is `CodebergError.Malformed`, not an exception.

### JSON Codecs

Derive jsoniter-scala codecs on the DTO, next to the DTO:

```scala
package com.worxbend.codeberg4s.issues.wire

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.CodecMakerConfig
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

object IssueCodecs:
  given JsonValueCodec[IssueDto] =
    JsonCodecMaker.make(CodecMakerConfig.withDiscriminatorFieldName(None))

  given JsonValueCodec[List[IssueDto]] =
    JsonCodecMaker.make
```

If a response body is a list at the top level — which most Forgejo list
endpoints are — provide a codec for the list type as well as the element type.
Deriving only the element codec compiles and then fails at runtime.

### Request Construction

Build requests through one typed path so auth, base URI, and pagination cannot be
forgotten:

```scala
package com.worxbend.codeberg4s.http

private[codeberg4s] final case class CodebergRequest(
    method: HttpMethod,
    path: List[String],
    query: Map[String, String],
)

private[codeberg4s] object CodebergRequest:
  def listIssues(slug: RepoSlug, page: Page, size: PageSize): CodebergRequest =
    CodebergRequest(
      method = HttpMethod.Get,
      path = List("repos", slug.owner.value, slug.name.value, "issues"),
      query = Map("page" -> page.value.toString, "limit" -> size.value.toString),
    )
```

Path segments come from validated opaque types, so an owner containing `/`
cannot forge a path. That validation is why `Owner.from` rejects slashes.

### Dependency Injection

Prefer constructor injection. Dependencies should be visible from the type
signature and easy to replace in tests.

```scala
package com.worxbend.codeberg4s

object Clients:
  def create(transport: CodebergTransport, clock: Clock): CodebergClient =
    CodebergClient(
      issues = IssueClient(transport),
      repositories = RepositoryClient(transport),
      rateLimits = RateLimitTracker(clock),
    )
```

Do not use field injection. Avoid inheritance-based wiring patterns. Composition
is easier to test and change.

## Testing

Tests should make behavior obvious. A test that verifies everything usually
explains nothing.

### Unit Tests

Each unit test covers one scenario. Name the behavior, set up only what it
needs, and assert the result in domain terms.

Use in-memory fakes when they make the behavior clearer than mocks:

```scala
package com.worxbend.codeberg4s.issues

import munit.FunSuite

final class IssueClientSuite extends FunSuite:
  test("listPage maps a 404 response to NotFound"):
    val transport = StubTransport.respondingWith(status = 404, body = "repo not found")
    val client = IssueClient(transport)

    val result = client.listPage(RepoFixtures.Slug, Page.First)

    assertEquals(result, Left(CodebergError.NotFound("repo not found")))
```

Mocking is acceptable for awkward external protocols, but a small fake often
documents the contract better. For this library the fake is a stub transport
returning canned responses — build one, and build it once.

### What Must Be Tested

Given what this library does, these deserve deliberate coverage:

- Every status code in `StatusMapping` maps to the intended `CodebergError`.
- Pagination terminates — on an empty page, on a missing next link, and on a
  single page.
- `listAll` does not fetch page two until page one is consumed.
- Malformed and unexpected JSON produces `Malformed`, never an exception.
- `RateLimited` carries the `Retry-After` value when the header is present.
- The token never appears in an error message, a `toString`, or a log line.
- Opaque-type constructors reject the inputs they promise to reject.

### Integration Tests

Integration tests verify boundaries: real HTTP against a stub server, JSON
round-tripping, request construction, and auth header wiring. Keep domain rules
testable in unit tests so integration tests stay focused and affordable.

| Test type   | Owns                                                              |
| ----------- | ----------------------------------------------------------------- |
| Unit        | Domain rules, validation, state transitions, error mapping.       |
| Integration | Real HTTP client against a stub server, serialization, paging.    |
| End-to-end  | A small number of critical workflows against a live instance.     |

End-to-end tests hitting a real Codeberg instance must be tagged and excluded
from the default `./mill __.test` run. CI must not depend on a third party's
availability or burn a shared rate limit.

Do not hide flaky timing behind sleeps. Use test clocks, controlled queues,
explicit latches, or deterministic Ox scopes.

## Documentation And Comments

The best code needs fewer comments because names and types carry intent.
Comments are still valuable when they explain a decision the code cannot
express.

### Comments

Write comments for:

- Non-obvious business rules.
- Forgejo/Codeberg API quirks — undocumented fields, inconsistent nullability,
  endpoints whose behavior differs from the published spec.
- Performance constraints.
- Security constraints.
- Concurrency or resource ownership assumptions.

Do not narrate the syntax. A comment such as "increment counter" above code that
increments a counter adds noise.

When working around an upstream quirk, link the issue or the API doc section. A
future reader must be able to tell whether the workaround is still needed.

### Scaladoc

This is a published library, so Scaladoc on the public API is not optional.
Document contract, failure behavior, pagination behavior, resource ownership,
and concurrency expectations.

```scala
package com.worxbend.codeberg4s.issues

/** Reads issues from a Codeberg or Forgejo instance.
  *
  * Returns `CodebergError.NotFound` when the repository is absent or the token
  * cannot see it, and `CodebergError.RateLimited` when the instance rejects the
  * request; the latter carries `Retry-After` when the server supplies it.
  *
  * `listAll` is lazy: pages are fetched as the returned flow is consumed, and
  * the whole collection is never held in memory.
  */
trait IssueReader:
  def listPage(slug: RepoSlug, page: Page): Either[CodebergError, IssuePage]
  def listAll(slug: RepoSlug): Flow[Issue]
```

Do not document private methods unless the private method encodes a subtle
algorithm or boundary condition.

## Review Checklist

Use this checklist before opening a pull request or asking another engineer to
review Scala code.

### Design

- The package name describes a concept, not a mechanism.
- Code sits under `com.worxbend.codeberg4s`.
- Public types and methods are intentionally public — remember this is a library
  API, and narrowing later is a breaking change.
- Domain invariants are encoded in types.
- Raw primitives do not cross important domain boundaries.
- No Boolean argument requires the caller to remember what `true` means.
- Effects, time, randomness, and the HTTP backend are explicit dependencies.
- The base URI is configurable; no hostname is hardcoded.

### Implementation

- Scalafmt and Scalafix converge without manual cleanup.
- Public and protected members have explicit result types.
- Functions do one thing or orchestrate named steps.
- Recoverable errors are represented as values against `CodebergError`.
- Every HTTP status the endpoint can return is mapped, including `429`.
- Exceptions are converted at the transport boundary.
- Any endpoint returning a collection is paged or streamed, never eagerly
  materialized.
- Resource acquisition and release ownership is visible; the backend is closed
  exactly once.
- Concurrency uses Ox, with bounded parallelism against the remote API.

### Tests

- Each test covers one scenario.
- Domain rules are testable without network access.
- Integration tests focus on real boundaries; live-instance tests are tagged and
  excluded by default.
- Time and randomness are deterministic in tests.
- Failure paths — 404, 401, 429, malformed JSON — are tested as deliberately as
  success paths.
- No test asserts on, or logs, a real token.

## Migration Notes

Existing code may not satisfy every rule. Do not create churn-only rewrites.
When touching a file, improve the code in the direction of this guide within the
scope of the change.

Use this order of priority:

1. Keep behavior correct.
2. Preserve public compatibility unless a breaking change is intentional — this
   is a published library, and consumers pin versions.
3. Add or update tests around the behavior being changed.
4. Move new code toward this style guide.
5. Leave unrelated cleanup for a separate change.

Style work should make future changes safer. If a refactor does not improve
readability, type safety, testability, or operational confidence, do not include
it in the same pull request.
