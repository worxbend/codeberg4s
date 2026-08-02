# Retries and rate limits

For anyone tuning a client that talks to a real instance under load: what the
retry engine repeats, what it refuses to repeat, how `Retry-After` is honoured,
and why the rate-limit headers you are looking for are not there.

## The policy

`com.worxbend.codeberg4s.retry.RetryPolicy` has five fields and no behaviour:

| Field | Default | Meaning |
| --- | --- | --- |
| `maxAttempts` | `3` | total attempts including the first, so `1` means "no retry" |
| `baseDelay` | `250.millis` | the delay before the second attempt |
| `maxDelay` | `8.seconds` | ceiling on a computed delay, applied before jitter |
| `jitter` | `Jitter.Full` | how much randomness to add |
| `respectRetryAfter` | `true` | let the instance's `Retry-After` override the computed delay |

The delay before attempt *n* is `min(baseDelay * 2^(n - 1), maxDelay)`, then
adjusted by the jitter. With the defaults: roughly 250 ms, then 500 ms, and then
you are out of attempts.

`RetryPolicy.Default` is the table above. `RetryPolicy.Off` is one attempt, no
delay, no jitter, no `Retry-After` — useful in tests and for callers doing their
own scheduling.

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergConfig
import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.retry.Jitter
import com.worxbend.codeberg4s.retry.RetryPolicy

import scala.concurrent.duration.DurationInt

val patient: CodebergConfig =
  CodebergConfig(Auth.Anonymous).copy(
    retry = RetryPolicy(
      maxAttempts       = 5,
      baseDelay         = 500.millis,
      maxDelay          = 30.seconds,
      jitter            = Jitter.Full,
      respectRetryAfter = true,
    )
  )
```

### Jitter is not optional in production

`Jitter.Full` sleeps for a uniformly random duration in `[0, computed delay]` —
AWS's "full jitter" backoff. Without it, every client that saw the same `429`
retries at the same instant and the instance gets a second thundering herd one
backoff later. `Jitter.None` uses the computed delay exactly and exists so that
tests are deterministic.

Note that `Jitter.None` shadows `scala.None` if you import the enum's cases
unqualified. Write `Jitter.None`.

## What gets retried

Three questions are asked, in this order, and all three must say yes.

### 1. May this call be repeated at all?

That is `com.worxbend.codeberg4s.core.RetryEligibility`, and it is stated by the
operation, not by your policy:

| Case | Meaning |
| --- | --- |
| `Never` | do not repeat this call, whatever failed |
| `IdempotentOnly` | repeat only when the method is safe in the RFC 9110 sense — `GET` or `HEAD` |
| `AlwaysRetry` | repeat whenever the failure is retryable, even for a mutating method |

Forgejo offers no idempotency keys, so this cannot be inferred from the response.
Each operation in this library states its own eligibility, and **each method's
Scaladoc names which one it uses**. It is not a caller-supplied argument: you
cannot ask for `issues.create` to be retried, and you cannot turn off the retry
on a `GET` except by changing the policy.

The rule the library applies:

- Reads use `IdempotentOnly`. A `GET` is safe, so it is repeated.
- An operation that **creates** something, or that applies a **partial update
  against whatever the resource has become**, is `Never`. `issues.create` under
  a retry would file the issue twice; `issues.edit` would re-apply a patch to a
  resource that has moved on.
- An operation whose request **names one resource and states its whole intended
  state** is `AlwaysRetry`, because N attempts leave the instance exactly as one
  attempt would. Marking a notification thread read, following a user, replacing
  a repository's topic list, deleting a numbered row the instance never reuses:
  all of these converge.

That third category is larger than it may look — it covers a great many `PUT`s
and `DELETE`s across the library. Read the method's Scaladoc rather than
guessing from the HTTP verb; each one that claims `AlwaysRetry` argues for it
explicitly.

> One caveat if you are reading the sources: `NotificationApi`'s class-level
> Scaladoc says its three mutating operations are "the only place in this
> library" that uses `AlwaysRetry`. That sentence is out of date — many other
> groups use it now. The per-method Scaladoc is accurate; the class-level
> sentence is stale.

### 2. Is this particular failure worth repeating?

| Failure | Retried? |
| --- | --- |
| `Api` with status `429`, `500`, `502`, `503`, `504` | yes |
| `Api` with any other status | no |
| `Transport(ConnectionFailed \| Timeout \| Dns \| Unknown)` | yes |
| `Transport(Tls)` | no — a certificate problem does not heal by itself |
| `Transport(Interrupted)` | no — the interruption was asked for |
| `DecodingFailed` | no — the same request produces the same payload |
| `Validation` | no — nothing was sent |
| `RetriesExhausted` | no — it already is the result of a loop |

`500` is in the retryable set because Forgejo answers a few transient database
and Git-process failures with it. `501` and `505` are not: they describe what the
instance can do, and repeating the call only spends your rate-limit budget.

### 3. Are there attempts left?

`number < policy.maxAttempts`. That is all.

## `Retry-After`

When the instance sends a `Retry-After` header and `respectRetryAfter` is on, it
replaces the computed backoff — clamped to `maxDelay`, so a hostile or misconfigured
instance cannot park your thread for an hour.

Two limits worth knowing:

- **Only the delta-seconds form is understood.** `Retry-After: 120` is honoured;
  the HTTP-date form is treated as absent, because honouring a date needs a
  trustworthy clock at both ends. Some reverse proxies rewrite the header into a
  date, in which case the policy's own backoff applies instead.
- A header that is present but blank counts as absent, which is what a gateway
  that strips a value leaves behind.

None of this can fail a call. A missing or unparseable `Retry-After` is always
"no hint", never an error.

## What you receive when it gives up

`CodebergError.RetriesExhausted(ctx, attempts, last)`, with `last` holding the
final underlying failure verbatim. A call that failed once and was not repeated
returns its failure unwrapped, so `RetriesExhausted` genuinely means "more than
one attempt was made". [Errors](./03-errors.md) has the matching patterns.

## Codeberg's rate-limit headers are not `X-RateLimit-*`

This is the part that surprises people arriving from a GitHub client.

Measured against codeberg.org across 15 responses — `200`, `400`, `401`, `404`
and `422` alike — there is **no** `X-RateLimit-Limit`, no
`X-RateLimit-Remaining` and no `X-RateLimit-Reset` on any response. What Codeberg
sends is the draft-IETF pair:

```
ratelimit-policy: "baseline";q=2000;w=600
ratelimit: "baseline";r=1996;t=600
```

| Token | Meaning | Observed |
| --- | --- | --- |
| `"baseline"` | the quota partition's name | `baseline` |
| `q` | requests allowed per window | `2000` |
| `w` | window length in seconds | `600` |
| `r` | requests remaining in the current window | counts down |
| `t` | seconds until the window resets | `600` |

So: **2000 requests per 10 minutes, anonymous**, and `r` decremented by exactly
one per request *including the error responses*. Failed requests consume quota.

Three consequences.

**Code that looks for `X-RateLimit-*` finds nothing.** Not "finds zero" —
finds nothing, which if you treat a missing header as zero remaining will make
your client stop for no reason.

**These headers come from Codeberg's edge, not from Forgejo.** The captures carry
`x-backend-name: b_forgejo_secondary` alongside them. A self-hosted Forgejo will
very likely send neither family. Never make correctness depend on them.

**This library does not parse or surface them at all.** There is no `RateLimit`
type, and `Telemetry` receives a status code rather than a header map. That is a
deliberate limit of the current release, not an oversight you have missed: rate
limiting is reported to your code as a `429`, and nothing more.

If you need the counters — for a dashboard, or to slow yourself down before you
hit the wall — the way to get them today is to own the sttp backend and read the
headers there:

```scala mdoc:compile-only
import com.worxbend.codeberg4s.CodebergClient
import com.worxbend.codeberg4s.CodebergConfig

import sttp.client4.Backend

import scala.concurrent.ExecutionContext

/** Wrap your own backend to observe response headers the client does not expose,
  * then hand the wrapper to `usingBackend` — you keep ownership, so you close it.
  */
def clientOn(backend: Backend[scala.concurrent.Future], config: CodebergConfig)(using
    ExecutionContext): CodebergClient =
  CodebergClient.usingBackend(config, backend)
```

The mechanics of wrapping a backend are sttp's, not this library's; see sttp's
own documentation for `Backend` delegation.

## Practical advice

**Do not raise `maxAttempts` to work around a rate limit.** Retrying a `429`
faster spends the quota you are out of. Raise `baseDelay` and `maxDelay`
instead, or slow the caller down.

**Budget your walks.** [Pagination](./04-pagination.md) shows a loop over every
page; at 30 items per page, a 50 000-issue repository is more than 1600 requests.
That fits inside an anonymous 10-minute window with room to spare, but two such
walks in parallel do not.

**Authenticate even for public reads** if you make many of them. A token widens
the budget, and it makes the traffic attributable to you rather than to an
address shared with everyone else behind your egress.

**Turn retries off in tests** unless the retry is what you are testing.
`RetryPolicy.Off` makes a failing stub fail once, immediately, and keeps the
suite fast — see [Testing your code](./07-testing-your-code.md).

## Where the evidence is

The rate-limit measurements, the header names, the quota numbers and the
"failed requests consume quota" finding are in
[`docs/HAZARDS.md`](../project/HAZARDS.md) §6, with the verbatim captures and
the `curl` commands that produced them. §6 also records what is *not* verified:
a real `429` was never triggered against codeberg.org — it would have meant
burning 2000 requests — so whether Codeberg accompanies one with `Retry-After`
is measured only against a stub, not against the live instance.

## Next

- [Errors](./03-errors.md) — matching on `RetriesExhausted`.
- [Observability](./06-observability.md) — seeing the retries happen.
- [Self-hosted instances](./09-self-hosted.md) — where these headers are
  probably absent entirely.
