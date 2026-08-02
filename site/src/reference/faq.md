# FAQ

For anyone who has looked at the API and thought "why on earth is it like
that?". Each answer is the honest one, and where a decision was recorded at the
time, the architecture decision record it links to carries the full reasoning.

## Why `Future` and not cats-effect, ZIO, or a direct-style effect?

Because a **client library** should not choose your effect system for you.

A user on ZIO should not inherit cats-effect. A user on cats-effect should not
inherit ZIO. A user on neither should inherit nothing at all. `Future` is in the
standard library, so it is the one type that costs every reader exactly the same
— nothing.

That is not a claim that `Future` is a good effect type. It is eager, it needs an
`ExecutionContext` threaded through everything, its cancellation story is poor,
and its failure channel is untyped. The typed rail
(`.attempt`, returning `Either[CodebergError, A]`) exists specifically because the
last of those is a real cost, and it hands the failure back as a value for anyone
who wants one.

Wrapping a `Future`-returning API in your own effect type is one line
(`IO.fromFuture`, `ZIO.fromFuture`, `Future.await` under Ox). Unwrapping somebody
else's chosen effect is not.

[ADR-0005](../project/adr/0005-future-public-api.md) records the decision, and
notes that it settled a genuine disagreement between two of this repository's own
planning documents. [ADR-0002](../project/adr/0002-hand-rolled-exec-over-effect-systems.md)
records the internal consequence: the cross-cutting logic is written against a
forty-line `Exec[F]` typeclass with two instances — `Future` for the published
client, and `Either` for the tests — so the retry engine and the pagination
driver are tested synchronously, without `Await` and without timeouts, and the
published artifact carries no effect-system dependency at all.

## Why opaque types instead of `String`?

Because a `String` that reaches a request path unchecked is a way to address an
endpoint the API never offered.

`Owner`, `RepoName`, `Username`, `OrgName`, `BranchName`, `RefName`,
`ContentPath` and the rest are all validated as URI path segments. A value
containing `/`, a control character, or a `..` segment is rejected at
construction. That is a security boundary rather than tidiness:
`client.users.keys` on `"someone/../../admin"` would otherwise compile and
dispatch.

There is a second reason, and in day-to-day use it matters more. Three of those
types — `Owner`, `Username`, `OrgName` — are the same characters on the wire and
answer three different questions: "who owns this repository?", "which person is
this?", "which organisation is this?". One shared type would let
`client.organizations.members(repository.slug.owner)` compile against a personal
account that has no members. Converting between them is a deliberate step through
`from`, never an implicit widening.

The cost at run time is zero. An `opaque type Owner = String` **is** a `String`
once the program runs — no wrapper object, no allocation. The cost at the call
site is one `Either` to handle, once, at the boundary where the string came from.

## Why do smart constructors return `Either` rather than throwing?

Because a bad owner name is not exceptional. It is a value your program received
from a configuration file, a command line, or a user, and handling it is ordinary
control flow.

Returning `Either` also lets several checks compose in one `for` comprehension
that stops at the first failure, which is what you almost always want when
building a request out of three or four validated parts.

## Why two error rails? Is that not two ways to do the same thing?

It is one implementation exposed twice, and the duplication is mechanical:
`.attempt` is the convenience rail with its failure channel materialised by a
single combinator. They cannot drift, because one is defined in terms of the
other.

They exist because two kinds of caller genuinely disagree. A codebase written
around `Future` wants failures on `Future`'s failure channel, where `recover`
works and a `for` comprehension does not have to unpack an `Either` inside a
`Future` at every step. A codebase that models errors as values does not want a
second, exception-shaped channel next to the one it already has.

`CodebergException` carries the full `CodebergError`, so the convenience rail
loses nothing. Pick per call site: the typed rail where you are deciding
something, the convenience rail in plumbing that forwards results.

## Why is there no `listAll` on each group?

There is a walk — `com.worxbend.codeberg4s.paging.PageWalk` — but it is one
helper rather than a `listAll` on each of the thirty-eight API classes, and that
is deliberate.

```scala
PageWalk.all(PageParams.First): params =>
  client.issues.list(owner, name, IssueQuery.Empty, params)
```

The termination rule is the subtle part of pagination: it reads `rel="next"` from
the `Link` header and must never compare items returned against items requested,
because Forgejo clamps `limit` to 50 while echoing back what you asked for. One
`listAll` per group would be one copy of that rule per group, and copies drift.

Because the walk takes the operation as an argument, it works on every listing in
the library — including any added later — without those listings knowing it
exists. `PageWalk.fold` and `PageWalk.foreach` are the bounded-memory forms; see
[Pagination](../guides/04-pagination.md).

## Why `Page` at all? Why not return a `List`?

Because a repository can hold tens of thousands of issues, and an operation that
quietly fetches all of them is an operation that quietly turns one listing into
an outage.

More importantly, a `List` would have to be assembled by a loop, and the obvious
loop against Forgejo is wrong. Forgejo clamps `limit` to the instance's maximum
while echoing the value you asked for, so `items.size < requested` is true on
every page and a loop written that way silently returns a truncated result.
`Page` makes the correct signal — the presence of `rel="next"` — the thing you
reach for. See [Pagination](../guides/04-pagination.md).

## Why jsoniter-scala rather than circe or jsoniter?

Three reasons, in the order they mattered.

sttp client4 ships a first-party jsoniter-scala integration module, so the response path
is one dependency rather than two plus glue. The transitive footprint is small,
which matters for a published library for the same reason as the effect-system
decision. And this project writes explicit codecs anyway — every field is
hand-checked against a captured response — so circe's derivation conveniences buy
less here than they usually would.

The costs are real and recorded: snake-case wire fields need explicit mapping,
and a top-level JSON array needs a codec for the collection as well as for the
element — a mistake that compiles and fails at run time, which is why every list
endpoint has a decode test against a captured fixture.

[ADR-0003](../project/adr/0003-jsoniter-for-json.md).

## Why hand-written models instead of generating them from the OpenAPI document?

Because generating from *this* document produces code that is wrong in ways that
only surface in your application.

The specification is Swagger 2.0, which has no `nullable`. Measured over the
pinned document: of 246 definitions, **zero** response models declare a
`required` list, and the string `nullable` does not appear anywhere. Read
literally, it asserts that every field of every response is optional and none may
be null — which is simultaneously useless and false. A live `Issue` from
codeberg.org returns JSON `null` for five fields the document declares as typed
values, including one it declares as an array. A generator produces a decoder
that crashes on the first issue of the first page.

It also cannot express the union at
`GET /repos/{owner}/{repo}/contents/{filepath}`, which returns an object for a
file and an array for a directory: the union exists only in the English summary,
and the machine-readable schema claims the response is always an object.

And generated names are permanent API design. `repoGetContents` and
`issueGetCommentsAndTimeline` are the specification's internal naming, and once
published they are yours forever.

So the models are hand-written and validated against **golden fixtures captured
from the live API**. Where the specification and a fixture disagree, the fixture
wins. The specification is still vendored and checksummed — as a reference and a
drift detector, not as a source of code.
[ADR-0001](../project/adr/0001-curated-models-over-codegen.md), and
[`docs/HAZARDS.md`](../project/HAZARDS.md) for the measurements.

## Why is the retry engine written here rather than using a retry library?

`com.softwaremill:retry` was evaluated first, and two things disqualified it.

It is `Future`-native, and the retry driver here has to run over the abstract
`Exec[F]` so that it can be unit-tested with `F = Either` — synchronously, with a
fake timer that records requested sleeps rather than sleeping. A `Future`-only
library cannot sit behind that seam. And it brings `odelay` for scheduling, which
adds a timer thread and a transitive dependency to a library whose whole
dependency argument is minimalism.

The cost is a few dozen lines this project owns. Because retry sits behind a
port, replacing it later with a third-party library would be a non-breaking
change. [ADR-0004](../project/adr/0004-retry-implemented-in-core.md).

## Does it work against Gitea?

Very likely much of it, and that is a plausible expectation rather than a tested
claim.

Forgejo forked from Gitea and the v1 API is largely shared — codeberg.org's own
version string is `16.0.0-dev-668-1bdb1938+gitea-1.22.0`. But **nothing in this
repository runs against Gitea**: the container integration suite starts
`codeberg.org/forgejo/forgejo:12`, and the live smoke suite talks to
codeberg.org. Endpoints Forgejo added or renamed after the fork will not exist,
and the models are built from Forgejo captures.

If you try it, `client.misc.nodeInfo()` reports which software you actually
reached and `client.version.get()` reports its version. If you find divergences,
they would be worth reporting.

## Why is `admin` not covered?

`admin`, `activitypub` and `package` are out of scope for v1 — a deliberate
scoping decision, not an oversight. Everything else in the Forgejo v1 API is
implemented on both rails.
[`docs/API_INVENTORY.md`](../project/API_INVENTORY.md) has the endpoint-level
checklist.

Note that `client.repos.admin` is unrelated to that tag: it is this library's own
grouping for administering a repository you own, and it needs no instance-admin
privileges.

## Why can I not stream a large file or artifact?

Because this library does not stream, anywhere. `client.downloads.artifact` and
`client.downloads.runLogs` hold the whole archive in memory, and so do the raw
and media file endpoints.

That is a real limitation to plan around rather than a setting to change. If you
need to stream a large blob, use the `download_url` a content entry carries and
fetch it with your own HTTP client.

## Why does the library not log anything?

Because a published library that drags SLF4J, Logback and a configuration file
behind it is a nuisance to embed: it collides with whatever the application
already uses, and it emits output nobody asked for.

Implement `Telemetry[Future]` and pass it at construction. That is one small
trait between your logging and this code, and the callbacks receive only
already-redacted values, so an implementation cannot leak a credential by logging
what it is handed. See [Observability](../guides/06-observability.md).

## Why does it not surface rate-limit headers?

Because they are not there in any dependable form.

Codeberg emits draft-IETF `ratelimit` and `ratelimit-policy` headers — not the
GitHub-style `X-RateLimit-*` — and those come from Codeberg's edge rather than
from Forgejo, so a self-hosted instance will very likely emit neither family.
Building a `RateLimit` type on top of that would be a feature that works on
exactly one deployment.

The current release therefore reports rate limiting as a `429` and nothing more.
If you need the counters, own the sttp backend and read the headers there. See
[Retries and rate limits](../guides/05-retries-and-rate-limits.md).

## Why is `PageSize` capped at 50 when my instance allows more?

Because 50 is Codeberg's `max_response_items`, and asking for more there is
silently clamped rather than refused — the failure mode this library exists to
prevent.

On a self-hosted instance configured with a larger ceiling, the cap is a genuine
limitation of the current release: you cannot ask for pages bigger than 50. On one
configured *below* 50 it is not enough on its own, which is why
`client.misc.apiSettings().map(_.maxResponseItems)` is where the real ceiling
lives. See [Self-hosted instances](../guides/09-self-hosted.md).

## Why five artifacts instead of one?

Because the module graph is what enforces the architecture, and publishing it is
what keeps it honest: `domain` has no dependencies at all, `core` never imports
sttp or jsoniter-scala, `codec` never imports sttp.

You almost certainly want `codeberg4s-client`, which pulls in the other four
transitively. The exception is a module that needs to *handle* a `CodebergError`
without making requests — a shared error-rendering module, say. That can depend
on `codeberg4s-domain` and link no HTTP client at all.

## Why is nothing on Maven Central?

Because `0.1.0` has not been tagged. The build is configured for it and the
coordinates are stable; until then they resolve only against a local publish.
This is the first thing a stranger hits, and it is tracked as blocking in
[`docs/READINESS.md`](../project/READINESS.md).

## Is it thread-safe? Can I share one client?

Yes, and you should. A `CodebergClient` is immutable apart from its closed flag
and is meant to be shared: build one per instance you talk to, for the lifetime of
the application.

Building one per request creates a scheduler thread each time, and — on the
`CodebergClient(config)` path — a connection pool as well.

`close()` is idempotent and safe from any thread. It releases the scheduler
thread, and the HTTP backend as well when the client created it; a backend you
passed to `usingBackend` is yours to close.

## What Scala versions are supported?

Scala 3 only, built against 3.8.4. There is no Scala 2 cross-build and no
Scala.js or Scala Native build; the last two are explicitly deferred.

The API uses Scala 3 features throughout — opaque types, `enum`, context
functions — so a Scala 2 port would be a different library rather than a
cross-build.

## How do I report a bug, or contribute?

The repository is at
[codeberg.org/worxbend/codeberg4s](https://codeberg.org/worxbend/codeberg4s).

Note that `docs/READINESS.md` lists the contribution path — `CONTRIBUTING.md`,
issue templates, a security policy — as still missing at the time of writing. If
it is still missing when you read this, an issue on the repository is the place
to start.

For a decoding failure specifically, the useful bug report is
`error.describe`: it carries the operation, the redacted URI, the JSON path and a
bounded excerpt of the body, and it cannot contain a credential.
