# Troubleshooting

For anyone staring at a failure that does not make sense. Start with the table,
then read the section it points at.

## Symptom to cause

| Symptom | Most likely cause | Where to look |
| --- | --- | --- |
| `401` although a token is configured | the token is blank, expired, missing a scope, or the endpoint needs credentials you did not think it needed | [401 with a token set](#401-with-a-token-set) |
| `404` on a repository you can open in a browser | the repository is private and the call is anonymous; or the owner is a redirect; or the base URI is missing `/api/v1` | [404 on something that exists](#404-on-something-that-exists) |
| `404` on a branch whose name contains `/` | not this — slashed branch names work; look at the branch name's *validity* instead | [Slashed branch names](#slashed-branch-names) |
| `CodebergError.DecodingFailed` | the instance sent a payload the model does not cover; possibly an older or newer Forgejo | [Decode failures](#decode-failures) |
| Nothing happens; the `Future` never completes | no `ExecutionContext` work being done, a blocking telemetry callback, or a read timeout longer than your patience | [A hang](#a-hang) |
| A listing returns fewer items than expected and then stops | `items.size` used as an end-of-pages test | [Pagination](./04-pagination.md) |
| A count is zero and should not be | `Page.totalCount` was `None` and got treated as zero | [Pagination](./04-pagination.md) |
| `RejectedExecutionException` from the scheduler | the client was used after `close()` | [Using a closed client](#using-a-closed-client) |
| Compile error: "could not find an implicit ExecutionContext" | no `given ExecutionContext` in scope | [Getting started](./01-getting-started.md) |
| Compile error: "Owner does not take parameters" | `Owner("x")` instead of `Owner.from("x")` | [Getting started](./01-getting-started.md) |

## First, print the failure

Before diagnosing anything, look at `describe`. It is bounded, secret-free, and
it names the operation, the method, the redacted URI, the instance's request id
and the elapsed time:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.issues.IssueQuery
import com.worxbend.codeberg4s.paging.PageParams
import com.worxbend.codeberg4s.repositories.Owner
import com.worxbend.codeberg4s.repositories.RepoName

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Renders whatever went wrong, without unwrapping anything by hand. */
def diagnose(client: CodebergClient, owner: Owner, name: RepoName)(using ExecutionContext): Future[String] =
  client.issues.attempt.list(owner, name, IssueQuery.Empty, PageParams.First).map:
    case Right(page)   => s"ok: ${page.items.size} issues on this page"
    case Left(failure) => failure.describe
```

A typical line reads:

```
issues.list GET https://codeberg.org/api/v1/repos/forgejo/nope/issues?page=1&limit=30 [request-id abc123] after 84ms responded 404: GetRepositoryByName (repository does not exist)
```

Everything you need to identify the call is in there. If you are reporting a
problem to an instance's administrators, quote the request id — it is their own
correlation id and the only thing that finds your request in their logs.

## 401 with a token set

Work down this list; the first three are by far the most common.

**The token is blank.** An unset `CODEBERG_TOKEN` becomes `""`, and if you
built the `Auth` without checking, nothing useful is sent. `ApiToken.from`
rejects a blank value precisely so this fails at construction — check that you
are not discarding its `Left`:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.auth.ApiToken

/** Returns the reason a token was refused, for a startup log line. */
def tokenProblem(raw: String): Option[String] =
  ApiToken.from(raw).left.toOption.map(problem => s"${problem.field}: ${problem.message}")
```

**The token has a trailing newline.** `ApiToken.from` trims, so this one is
already handled — but only if you passed the raw value through it rather than
building the header yourself somewhere else.

**The token lacks the scope.** Forgejo does not tell you which scope was missing,
and it does not reject an unknown scope at creation time either: a token minted
with `read:repositories` — plural, and wrong — is created successfully and can do
nothing. Check the token's scopes on the instance, or list them:
`client.users.tokens.list(username, page)` returns each token's `scopes`.

**The endpoint needs credentials and you assumed it did not.** The pinned
OpenAPI document carries **no per-operation security information at all**, so
nothing in the specification distinguishes a public endpoint from a private one.
Measured against codeberg.org, an anonymous `GET /users/{username}/followers`
answers `401` even though the path looks public. If a call answers `401` and you
expected otherwise, configure credentials rather than hunting for a bug.

**The token belongs to a different account than the path names.** Managing
another account's tokens, keys or settings is `403` or `401`, not an empty
result.

**Basic auth with two-factor enabled.** Forgejo requires an `X-FORGEJO-OTP`
header in that case, which this library does not support. Use a token.

## 404 on something that exists

**The repository is private and the client is anonymous.** A forge that answered
`403` for a private repository would be confirming that it exists, so `404` for
"you may not see this" is the usual behaviour and is indistinguishable from
genuine absence. Configure a token and try again before concluding anything.
(This library's error model does not need to know which it was: both arrive as
`Api(ctx, 404, body)`.)

**The base URI is missing `/api/v1`.** `https://forge.example` as a base URI
produces requests to `https://forge.example/repos/owner/name`, which is a web
page path and not an API path. Check with `client.version.get()`: if that fails
too, the base URI is wrong. See [Self-hosted instances](./09-self-hosted.md).

**The owner is a redirect.** Forgejo keeps redirects for renamed users and
organisations on the web UI, and the API's `404` body says so explicitly. The
captured body for a missing owner is:

```json
{"message":"GetUserByName","url":"https://codeberg.org/api/swagger","errors":["user redirect does not exist [name: definitely]"]}
```

Note that the useful sentence is in `errors[0]`, and `message` is the name of the
Go function that failed. **Never show `message` alone to a user** — it will say
`GetUserByName` and mean nothing to anybody.

**Case.** Owner and repository names are matched by the instance, and its rules
are its own. If the browser URL is `Codeberg/Community`, use those spellings.

**The endpoint does not exist on this instance's version.** An older Forgejo
returns `404` for an endpoint added later, which looks exactly like a missing
resource. `client.version.get()` tells you what you are talking to.

## Slashed branch names

A branch called `renovate/some-dependency-0.x` works. This library splits a
`BranchName` on `/` and appends the segments to the request path one at a time,
because Forgejo routes those with a wildcard and a name percent-encoded whole
into a single segment answers `404`. Both real names in the captured fixtures —
`renovate/forgejo-github.com-go-swagger-go-swagger-cmd-swagger-0.x` and
`v16.0/forgejo` — go through that path.

So if a slashed branch is `404`ing, the slash is not the reason. Check instead
that the name is one `BranchName.from` accepts:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.repositories.BranchName

/** Why a branch name was refused, if it was. */
def branchProblem(raw: String): Option[String] =
  BranchName.from(raw).left.toOption.map(problem => problem.message)
```

`BranchName.from` rejects a blank name, a control character, a leading or
trailing `/`, an empty segment (`a//b`), and a `.` or `..` segment. Git rejects
all of those as ref names too, so nothing valid is lost — and the `.`/`..` case
is a security boundary, not a formality: it is the one thing that would let a
crafted name climb out of the branch route.

If the name is accepted and the call still `404`s, the branch genuinely is not
there under that spelling. Confirm with `client.repos.listBranches`.

## Decode failures

`CodebergError.DecodingFailed(ctx, snippet, path, cause)` means the instance
answered `2xx` and the payload did not fit the model. **Retrying will not help**
— the same request produces the same payload.

Read `path` first. It renders as a JSONPath such as `$.id` or `$.items[3].owner`,
and it names exactly where the decoder gave up. `snippet` is a bounded excerpt of
the body — at most 512 characters — and `cause` is the decoder's own message.

The three realistic explanations, in order of likelihood:

1. **The instance is a different Forgejo version.** A field this library requires
   was added later, or renamed. Compare `client.version.get()` against the pinned
   `16.0.0-dev-668-1bdb1938+gitea-1.22.0`.
2. **A field this library treats as required is genuinely absent.** The models
   are built from captured responses rather than from the specification, because
   the specification declares **no** `required` list on any response model and no
   `nullable` anywhere — read literally it asserts that every field of every
   response is optional and none may be null, which is both useless and false.
   Where a field is required here, a real capture showed it present.
3. **Something between you and the instance rewrote the body** — a proxy, an
   error page, a captive portal. `snippet` will make that obvious immediately.

Whichever it is, `describe` on the failure is what a bug report needs: it carries
the operation, the redacted URI, the path and the snippet, and it cannot contain
a credential.

## A hang

**No `ExecutionContext` doing work.** A `Future` composed on an executor with no
threads available never completes. If your pool is shared and saturated by
blocking work elsewhere, the client's continuations queue behind it.

**A blocking telemetry callback.** Telemetry callbacks run on the client's
execution path and the client waits for the `Future` each one returns. An
implementation that writes synchronously to a slow appender slows every request;
one that blocks on a lock can stop them. See
[Observability](./06-observability.md) for the offloading pattern.

**Your read timeout is longer than your patience.** The default is 30 seconds,
and retries multiply it: three attempts with backoff is over a minute and a half
before you hear anything. Turn retries off — `RetryPolicy.Off` — while you are
diagnosing.

**A walk over every page that will not end.** If the instance advertises a next
page forever, a loop without the empty-page guard runs until the rate limit
stops it. The guard is `case Some(following) if page.items.nonEmpty` — see
[Pagination](./04-pagination.md).

**`Await` inside asynchronous code.** Blocking a thread of the same pool the
`Future` needs to complete on is a deadlock. `Await.result` belongs in a `main`
method and nowhere else.

**The client was closed while a retry was waiting.** `close()` abandons work the
scheduler had already accepted, so a `Future` that was sleeping between attempts
never completes at all — it neither succeeds nor fails. That is the intended
reading of "the client is closed": a retry that was waiting is not resumed. See
[Using a closed client](#using-a-closed-client) for the shape that causes it.

To find out whether requests are leaving at all, attach a telemetry
implementation: `onRequest` firing with no matching `onResponse` says the
instance is not answering; nothing firing at all says the problem is on your
side of the client.

The client's own thread is named `codeberg4s-timer`, so a thread dump names this
library rather than `pool-3-thread-1`. It is a single daemon thread, and there is
exactly one per client.

## Using a closed client

Using a closed client is a defect, and the library is careful not to launder it
into something the retry engine would react to.

Concretely, the scheduler behind the retry backoff is shut down, so anything that
needs to wait between attempts fails with a `RejectedExecutionException` inside a
failed `Future` — not with a `CodebergError`. Dressing that up as a `Transport`
failure would invite a retry of a client that cannot retry. And if the client
owned its HTTP backend, that is shut down too, so calls fail at the transport
instead.

The third outcome is the one that looks like a hang: a call that was *already*
sleeping between attempts when `close()` ran never completes at all.

`close()` itself is idempotent and safe from any thread; the second and later
calls do nothing. The usual cause of all three symptoms is a
`finally client.close()` inside a function that returns a `Future` — the block
completes and closes the client while the request is still in flight. Close it
when the `Future` completes instead:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.auth.Auth

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Closes the client when the work has finished, not when the block returns. */
def withClient[A](work: CodebergClient => Future[A])(using ExecutionContext): Future[A] =
  val client = CodebergClient(CodebergConfig(Auth.Anonymous))
  work(client).transform: outcome =>
    client.close()
    outcome
```

In an application, do neither: build one client at startup and close it at
shutdown.

## Things that look like problems and are not

**`totalCount` is `None`.** Several Forgejo endpoints omit `x-total-count`
entirely — an endpoint that takes no paging parameters sends no paging headers at
all. `None` means "unknown", never zero.

**A page past the end returns `200` with an empty list.** That is Forgejo's
behaviour, not an error, and it is why the end-of-pages test is the absence of
`rel="next"`.

**`client.misc.signingKey()` returns `None`.** An instance that does not sign
commits is a legitimate answer, which is why it is an `Option` rather than a
`404`.

**A notification subject type you have never seen.** Forgejo adds them between
releases, so an unknown one decodes to `NotificationSubjectType.Other(raw)`
instead of failing the whole page.

**`toString` on your config prints `***`.** That is the redaction working.

## Still stuck

- [`docs/HAZARDS.md`](../project/HAZARDS.md) — every measured divergence
  between the specification and what Codeberg actually returns, with verbatim
  captures and the `curl` commands that produced them. If the instance is
  behaving strangely, the explanation is often already there.
- [`docs/API_INVENTORY.md`](../project/API_INVENTORY.md) — the endpoint-level
  checklist, including what is out of scope for v1: `admin`, `activitypub` and
  `package`.
- [FAQ](../reference/faq.md) — the design questions this library provokes.
