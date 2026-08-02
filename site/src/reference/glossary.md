# Glossary

For anyone who has met a term in these guides, in the Scaladoc, or in Forgejo's
own vocabulary and wants a short definition. Terms are grouped by where they come
from rather than alphabetised, because the confusions are between neighbours.

## Identifiers

### `Owner`

The user *or* organisation that owns a repository — the first segment of
`owner/name`. Answers "who does this repository belong to?", and the answer may
be either kind of account.

`Owner.from("forgejo")` returns `Either[ValidationError, Owner]`. It rejects a
blank value, a `/`, and control characters, because an `Owner` is interpolated
into a request path.

### `Username`

The handle that names a **person** — the `{username}` of `/users/{username}`.
Answers "which account is this?".

Deliberately not the same type as `Owner`, even though the two are the same
characters on the wire. The endpoints that take a `Username` —
`/users/{u}/followers`, `/users/{u}/keys` — are about a person, and one shared
type would let `client.users.keys(repository.slug.owner)` compile against an
organisation that has no keys. Converting is a deliberate step through
`Username.from`, never an implicit widening.

### `OrgName`

The handle that names an **organisation** — the `{org}` of `/orgs/{org}`. Not
`Owner` and not `Username`, for the same reason: one shared type would let
`client.organizations.members(repository.slug.owner)` compile against a personal
account, which has no members and answers `404`.

So there are three types for what is, on the wire, one string. That is the point.
Each one says what kind of question the endpoint is asking.

### Slug

The `owner/name` pair that identifies a repository, as Forgejo displays it and as
the API paths spell it: `forgejo/forgejo`.

Modelled as `RepoSlug(owner, name)` with a `value` that renders the canonical
form. It is a real type because two loose strings let a caller swap them
silently, and every repository endpoint takes both.

Elsewhere in web software "slug" often means a URL-safe version of a title; that
is **not** the meaning here.

### `RepoName`

The second segment of `owner/name`, on its own. Validated the same way as
`Owner`.

## Git terms

### Ref

Short for *reference*: a name that points at a commit. `refs/heads/main`,
`refs/tags/v1.2.0`. Branches and tags are both refs; a ref is the general case.

`RefName` covers the whole family — whole or partial, `refs/heads/main` or
`heads/main` or `main` on its own — because Forgejo's raw-Git endpoints spell a ref in
three different positions and one type serves all three.

### Branch

A ref under `refs/heads/` that moves as commits are added. `BranchName` is the
type.

**A branch name may contain `/`.** `renovate/some-dependency-0.x` and
`v16.0/forgejo` are both real names from the captured fixtures. That matters
because Forgejo routes them with a wildcard, so the slashes have to reach the
wire as real separators: a name percent-encoded whole into one path segment
answers `404`. `BranchName.segments` exists for exactly that, and it is what the
request builder uses.

### Tag

A ref under `refs/tags/` that does not move. `TagName` is the type.

An *annotated* tag is a Git object in its own right, carrying a message and a
tagger; a *lightweight* tag is only a name pointing at a commit.
`client.repos.git.getAnnotatedTag` reads the first kind.

### Sha / blob id

A Git object identifier — 40 hex characters for SHA-1, 64 for SHA-256. `CommitSha`
is the type, and it accepts 4 to 64 characters so that abbreviated ids work.

The word does double duty in this API. On a file write, `UpdateFile.expectedSha`
is the **blob** id of the file's current contents, not a commit id. Both are
`CommitSha` because both are Git object ids of the same shape.

## Pagination

### Page and limit

Forgejo's two query parameters. `page` is the one-based index of the window you
want; `limit` is how many items it may hold. This library sends both, always, as
one `PageParams(page, size)` value.

Never send a lone `limit`: list endpoints given one have been observed to ignore
it and return the entire collection.

### `PageSize`

The validated form of `limit`. Accepts `1..50` and rejects anything larger rather
than clamping it. `PageSize.Default` is 30.

### `PageNumber`

The validated form of `page`. One-based, and `0` is rejected — Forgejo silently
treats `page=0` as `page=1`, which would hide an off-by-one in your code.

### `Page[A]`

What one request returns: the `items`, the `params` that produced them, an
optional `totalCount`, and `nextPage` / `prevPage`. It is never the whole
collection.

### Clamp

Forgejo reducing your `limit` to the instance's own `max_response_items` without
saying so, while the `Link` header still echoes what you asked for. The reason
`items.size` is not an end-of-pages test. See
[Pagination](../guides/04-pagination.md).

### `Link` header

RFC 5988. A comma-separated list of URIs with relation types — `rel="next"`,
`rel="prev"`, `rel="first"`, `rel="last"`. The presence of `rel="next"` is the
only sound end-of-collection test against Forgejo.

## This library's own vocabulary

### Rail

One of the two ways every operation is exposed.

The **convenience rail** is the method on the group: `client.repos.get(...)`
returns `Future[Repository]` and fails the `Future` with `CodebergException`.

The **typed rail** is the same operation under `.attempt`:
`client.repos.attempt.get(...)` returns `Future[Either[CodebergError, Repository]]`
and never fails.

They are one implementation. `.attempt` is the convenience rail with its failure
channel materialised, so the two cannot drift. Pick one per call site; see
[Errors](../guides/03-errors.md).

### Command

A value describing a write, built by naming what should be set, rather than a
method with a row of optional parameters: `CreateIssue`, `EditIssue`,
`MergePullRequest`, `UpdateFile`. Only what you set is sent.

### `CallContext`

Attached to every remote failure: the operation id, the HTTP method, the
**redacted** URI, the instance's request id when it sent one, and how long the
attempt took. It is what lets you tell which call failed without correlating
logs.

### Operation id

A stable, greppable string identifying one endpoint — `"repos.get"`,
`"issues.list"`, `"version.get"`. It never changes, so it is safe to build alerts
and metrics on. It appears in `CallContext.operation` and as a `val` on each API
companion object.

### Redaction

Removing credential material before a value can be logged. Here it is a
guarantee with tests behind it: `ApiToken` and `Password` render as `***` from
`toString` and from string interpolation; the URI in a `CallContext` is redacted
by the transport before the context is built; `CodebergError.describe` is
assembled only from that redacted context and from server-supplied text.

### `RetryEligibility`

Whether an operation may be repeated at all: `Never`, `IdempotentOnly` (safe
methods only), or `AlwaysRetry`. Stated by the operation, not by the caller —
Forgejo has no idempotency keys, so it cannot be inferred from the response.

### Telemetry

The observation port. This library has no logging dependency and writes nothing
anywhere; you implement `Telemetry[Future]` and pass it at construction. See
[Observability](../guides/06-observability.md).

### Golden fixture

A response captured verbatim from a real instance and checked into the
repository, used to build and test the models. Where the specification and a
fixture disagree, the fixture wins — the specification declares no `required`
fields on any response model and no `nullable` anywhere, so read literally it
asserts that every field of every response is optional and none may be null.

## HTTP and general terms

### Safe method

RFC 9110's term: a method with no intended side effect on the server. `GET` and
`HEAD`. Only safe methods are retried without the operation opting in.

### Idempotent

A request that produces the same server state whether it is made once or many
times. Deleting a numbered row is idempotent; creating an issue is not.

The word is often used loosely as "safe to retry", and this library is careful
not to: `RetryEligibility.IdempotentOnly` means "safe methods only", and the
stronger claim — that a *mutating* call may be repeated — is `AlwaysRetry`, and
is argued for per operation.

### Optimistic concurrency

Making a write conditional on the state you read, so that a concurrent change is
detected rather than overwritten. Here it is `UpdateFile.expectedSha` (Forgejo
answers `409` if the file moved on) and `MergePullRequest.expecting` (Forgejo
refuses if the branch head moved).

## Scala terms

### `Future[A]`

A value that is not there yet and will eventually hold an `A` or hold a failure.
Scala's equivalent of a JavaScript `Promise` or a Java `CompletableFuture`.

### `ExecutionContext`

Where the continuations of a `Future` run — a thread pool, essentially. Scala
makes you name one rather than picking a global default, which is why you write
`given ExecutionContext = …` once per scope. See
[Getting started](../guides/01-getting-started.md).

### `given` / `using`

Scala 3's context parameters. A `using` parameter is one the compiler supplies
from a matching `given` value in scope, instead of your passing it at every call
site. It is how `ExecutionContext` travels.

### Opaque type

A type that is a distinct type at compile time and its underlying representation
at run time. `opaque type Owner = String` means an `Owner` *is* a `String` when
the program runs — no wrapper object, no allocation — but a `String` cannot be
passed where an `Owner` is expected, and the only way to make one is the smart
constructor.

That is why most identifiers here are opaque types. The two credential types —
`ApiToken` and `Password` — are ordinary `final class`es instead, because an
opaque alias over `String` cannot stop `toString` from printing the secret
outside its own file.

### Smart constructor

A function that validates its input and returns the type or an error, instead of
a public constructor that trusts you. Here they are called `from` (`Owner.from`,
`ApiToken.from`, `PageSize.from`) or `of` (`CreateIssue.of`,
`MergePullRequest.using`), and they return `Either[ValidationError, A]` when they
can fail.

### ADT

*Algebraic data type* — a type defined as a closed set of alternatives, each with
its own fields. Scala 3 spells it `enum`. `CodebergError` is one, with five
cases; so are `Auth`, `TransportCause`, `ContentEntry` and `FileOperation`.

The value of a closed set is that the compiler can tell you when a `match` has
forgotten a case.

### Port

A trait the library defines and something else implements, so that the library
does not depend on the something else. `Telemetry` is the one you are expected to
implement; `HttpPort` and `Decode` are internal ones, satisfied by the sttp and
upickle adapters.
