# Self-hosted instances

For anyone pointing this client at a Forgejo they run themselves, rather than at
codeberg.org: what changes, what does not, and how to find out what your
instance's limits actually are.

## Codeberg is a default, not an assumption

There is no hardcoded host anywhere in this library. `BaseUri.Codeberg` is the
value `CodebergConfig(auth)` fills in when you give it nothing else; every other
part of the code takes the base URI from the configuration.

```scala mdoc:compile-only
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
    base  <- BaseUri.from("https://forge.example.internal/api/v1")
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
at that size — which is what listings and `PageWalk` should start from on an
instance whose page ceiling differs from codeberg.org's.

Or, if only the host differs:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.BaseUri
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.auth.Auth

val onlyTheHost: Either[ValidationError, CodebergConfig] =
  BaseUri.from("https://forge.example.internal/api/v1").map(base => CodebergConfig(Auth.Anonymous).copy(baseUri = base))
```

### What `BaseUri.from` accepts

- An absolute `http://` or `https://` URI with something after the scheme.
- Surrounding whitespace is trimmed; trailing slashes are removed, so request
  building can join segments with a single `/` and never produce `//`.

It rejects a blank value, a control character, a scheme this library cannot
speak, and a scheme with no authority — each as a `ValidationError` on the
`"baseUri"` field.

**Include the `/api/v1` suffix.** The base URI is the API root, not the site
root. `https://forge.example.internal` alone will produce `404`s on every call,
because the path this library appends is `repos/owner/name`, not
`api/v1/repos/owner/name`.

**Plain `http://` is accepted.** That is deliberate — a Forgejo on a private
network behind a TLS-terminating proxy is a real deployment — but it means a
typo cannot be caught for you. If your instance is on `https`, say so.

## Discovering your instance's limits

Three `settings/*` endpoints and a version endpoint tell you most of what
differs between deployments, and all four are cheap:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.miscellaneous.ServerApiSettings

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** What this instance says about itself, in one shot. */
def instanceProfile(client: CodebergClient)(using ExecutionContext): Future[(String, ServerApiSettings)] =
  for
    version  <- client.version.get()
    settings <- client.misc.apiSettings()
  yield (version.raw, settings)
```

### `client.version.get()`

The cheapest liveness probe there is, and the one endpoint every Forgejo answers
anonymously. Use it to check that a base URI really points at a Forgejo API root
before you debug anything else. Measured against codeberg.org it answers
`16.0.0-dev-668-1bdb1938+gitea-1.22.0`, which also tells you which Gitea release
the Forgejo lineage forked from.

### `client.misc.apiSettings()` — the one that matters for pagination

`ServerApiSettings` carries four fields:

| Field | Codeberg | Meaning |
| --- | --- | --- |
| `maxResponseItems: Long` | `50` | the largest number of items **any** paged endpoint returns, whatever `limit` asked for |
| `defaultPagingNum: Long` | `30` | the page size when a request omits `limit` |
| `gitTreesPerPage: Option[Long]` | `1000` | the page size of the git-trees endpoint, which paginates on its own terms |
| `maxBlobSizeBytes: Option[Long]` | `10485760` | the largest blob the contents endpoints will inline |

`maxResponseItems` is the number to read once at startup on a self-hosted
instance, because it is per-instance configuration and not a protocol constant.
It is the ceiling behind the clamp described in
[Pagination](./04-pagination.md): Forgejo silently reduces `limit` to this value
while the `Link` header still echoes what you asked for.

**A limitation to know about.** `PageSize` in this library refuses anything above
50, hardcoded, because 50 is Codeberg's ceiling and asking for more there is
pointless. If your instance is configured with a *larger*
`max_response_items` — 100, say — this client still cannot ask for pages bigger
than 50. That is a real constraint of the current release, not something you have
configured wrongly.

The opposite case is the dangerous one: if your instance is configured *below*
50, `PageSize.from(50)` succeeds and the instance clamps. Reading
`maxResponseItems` and clamping your own page size to it is worth doing:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.paging.PageSize

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** The largest page this instance will actually serve, expressed as a `PageSize`.
  *
  * Falls back to the library default when the instance reports something this
  * client cannot represent — a ceiling above 50, or a nonsensical one.
  */
def effectivePageSize(client: CodebergClient)(using ExecutionContext): Future[PageSize] =
  client.misc.apiSettings().map: settings =>
    val wanted: Int = math.min(settings.maxResponseItems, PageSize.Max.value.toLong).toInt
    PageSize.from(wanted).getOrElse(PageSize.Default)
```

### `client.misc.repositorySettings()`

Seven booleans, each naming a feature the instance has **disabled**:
`mirrorsDisabled`, `httpGitDisabled`, `migrationsDisabled`, `starsDisabled`,
`forksDisabled`, `timeTrackingDisabled`, `lfsDisabled`.

This is how you find out ahead of time that a write you are about to attempt will
be refused. An automation that files tracked time against an instance with
`timeTrackingDisabled` gets an error per issue; one that reads this endpoint once
gets a clear message at startup.

### `client.misc.attachmentSettings()`

`enabled`, `allowedTypes`, `maxSizeMib`, `maxFiles`. Codeberg allows any type —
`settings.acceptsAnyType` is the check — but a self-hosted instance frequently
does not.

### `client.misc.uiSettings()` and `client.misc.nodeInfo()`

`uiSettings` carries `allowedReactions`, which is the list to consult before
posting a reaction — `allows(reaction)` answers `false` for an empty list,
because an empty list means "the instance did not say" and this library does not
turn silence into permission.

`nodeInfo` reports the software's own name (`forgejo`, `gitea`), its version, and
whether registration is open.

## What is different from codeberg.org

### Rate-limit headers are probably absent

Codeberg emits draft-IETF `ratelimit` and `ratelimit-policy` headers. Those come
from Codeberg's **edge**, not from Forgejo itself — the captures carry
`x-backend-name: b_forgejo_secondary` alongside them — so a stock self-hosted
Forgejo will very likely send neither those nor the GitHub-style `X-RateLimit-*`.

In practice this changes nothing about your code, because **this library does not
parse or surface rate-limit headers on either kind of instance**. Rate limiting
reaches you as a `429` and nothing more. See
[Retries and rate limits](./05-retries-and-rate-limits.md).

What it does change is your expectations: on a self-hosted instance there may be
no rate limit at all, and a walk over 200 000 issues that codeberg.org would
throttle will run without complaint. Be a good citizen of your own infrastructure anyway.

### Authentication may be older

`Auth.Basic` exists because some self-hosted deployments still require it. Prefer
a token where the instance allows one — a token can be scoped, listed and revoked
on its own, and a password cannot. Both redact themselves identically. See
[Authentication](./02-authentication.md).

### Endpoints may be missing or may behave differently

This library targets Forgejo v1 as pinned in `spec/swagger.v1.json`, at
`16.0.0-dev-668-1bdb1938+gitea-1.22.0`. An older instance will not have every
endpoint, and a missing endpoint arrives as an ordinary
`CodebergError.Api(ctx, 404, body)` — indistinguishable, at the type level, from
a repository that does not exist. `client.version.get()` at startup is how you
tell the two apart before they confuse you.

### `403` and `409` bodies were never captured

The library's error-body model is built from captured responses for `400`, `401`,
`404` and `422`, all measured anonymously against codeberg.org. `403` and `409`
could not be produced that way — both need an authenticated or mutating request —
so their body shape is assumed to be the same `{message, url}` and is not
verified. Their **status codes** are handled exactly like any other; it is only
the payload shape that rests on the specification rather than on a capture.
[`docs/HAZARDS.md`](../project/HAZARDS.md) §4 records this openly.

## Timeouts on a slow or distant instance

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.auth.Auth

import scala.concurrent.duration.DurationInt

val patientTimeouts: CodebergConfig =
  CodebergConfig(Auth.Anonymous).copy(connectTimeout = 30.seconds, readTimeout = 2.minutes)
```

One trap: sttp models the **connect** timeout as a property of the backend rather
than of a request. So `connectTimeout` is honoured only by a client built with
`CodebergClient(config)`, which creates its own backend from that value. A client
built with `CodebergClient.usingBackend` ignores it — configure the connect
timeout on the backend you supplied. `readTimeout` is applied per request and is
honoured either way.

Raise the read timeout for endpoints that do real work on the instance:
generating an archive, comparing two distant commits, migrating a repository.

## Response size on an instance you configured

This library reads a whole response into memory rather than streaming it, so
every request carries a byte bound. A body that passes the bound is abandoned
part-read and reported as
`CodebergError.Transport(ctx, TransportCause.ResponseTooLarge(detail))` — and
that cause is deliberately **not** retried, because repeating the call would
download the oversized body once per attempt.

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.auth.Auth

val roomierBodies: CodebergConfig =
  CodebergConfig(Auth.Anonymous).copy(
    maxResponseBodyBytes = 64L * 1024 * 1024,
    maxDownloadBodyBytes = 512L * 1024 * 1024,
  )
```

The defaults are 16 MiB for a textual response and 50 MiB for the two ZIP
downloads under `client.downloads`. Two settings rather than one,
because the reasoning behind them is different: the textual bound is derived
from `default_max_blob_size` — 10 MiB on codeberg.org, base64-encoded into a
file-contents response at four bytes per three — while a CI artifact is whatever
a workflow uploaded and no such number bounds it.

That first number is per-instance configuration, not a protocol constant. If
your Forgejo raises `default_max_blob_size`, read the instance's own value back
from `client.misc.apiSettings()` — it is `maxBlobSizeBytes` on the returned
`ServerApiSettings` — and raise `maxResponseBodyBytes` to match, or fetching a
large file will fail as `ResponseTooLarge`.

## Self-signed certificates

This library does not configure TLS. It builds an sttp JDK-HTTP-client backend
and nothing more, so trust decisions belong to the JVM and to the backend.

If your instance uses a private certificate authority, either add it to the JVM
trust store or build your own sttp backend with the `SSLContext` you want and
pass it to `CodebergClient.usingBackend`. Note also that a TLS failure is
**not retried**: `TransportCause.Tls` is deliberately excluded, because a
certificate problem does not heal by itself.

## Does it work against Gitea?

Forgejo forked from Gitea and the v1 API is largely shared, so much of this
library will very likely work against a Gitea instance.

**That is a plausible expectation and not a tested claim.** Nothing in this
repository runs against Gitea: the container integration suite starts
`codeberg.org/forgejo/forgejo:12`, and the live smoke suite talks to
codeberg.org. Endpoints Forgejo added or renamed after the fork will not exist,
and this library's models are built from Forgejo captures.

If you try it, `client.misc.nodeInfo()` will tell you which software you actually
reached, and `client.version.get()` will tell you its version.

## Next

- [Pagination](./04-pagination.md) — where `maxResponseItems` matters.
- [Troubleshooting](./10-troubleshooting.md) — including "404 on a repository I
  can see in a browser".
- [FAQ](../reference/faq.md).
