# ADR-0004 — Retry implemented in `core`, not delegated to `softwaremill/retry`

- Status: accepted
- Date: 2026-08-01

## Context

`PLAN.md` §3.3 item 4 says to evaluate `com.softwaremill:retry` first and fall
back to an in-core implementation if it disqualifies itself at version
resolution time.

At resolution (2026-08-01) `com.softwaremill.retry:retry_3` resolves to
**0.3.6**. Two things disqualify it for this project:

1. It is `Future`-native. Our retry driver has to run over the abstract
   `Exec[F]` (ADR-0002) so it can be unit-tested with `F = Either`, synchronously
   and without sleeping. A `Future`-only library cannot sit behind that seam.
2. It brings `odelay` for scheduling, which adds a timer thread and a transitive
   dependency to a library whose whole dependency argument is minimalism.

## Decision

Implement `RetryEngine` in `modules/core` over `Exec[F]` and a `Timer[F]` port.

- Backoff: `baseDelay * 2^(attempt - 1)`, capped at `maxDelay`.
- Jitter: `Jitter.Full` draws uniformly from `[0, computed]`; the randomness
  source is injected, never `scala.util.Random` called inline, so tests are
  deterministic.
- `Retry-After` is honoured when `RetryPolicy.respectRetryAfter` is set: a hint
  within `maxDelay` is waited for exactly, unjittered. A hint **beyond**
  `maxDelay` ends the call at once, surfacing the `Api` failure — status and
  `retryAfter` included — unwrapped, so the caller can schedule the wait the
  server asked for. It is not clamped and retried early: a repeat before the
  server's stated time is answered with the same `429` and burns both an
  attempt and a unit of rate-limit quota to learn nothing. (Amended 2026-09-29;
  the original decision clamped the hint to `maxDelay`.)
- Retryable statuses: 429, 500, 502, 503, 504. Retryable transport causes:
  `ConnectionFailed`, `Timeout`, `Dns`. `Tls`, `Interrupted`, `ResponseTooLarge`
  and `Unknown` are not: the adapter classifies every `IOException` as one of
  the first three, so an `Unknown` cause is a non-I/O exception — a client that
  was shut down, a request the backend could not build — that the same request
  would reproduce. (Amended 2026-09-29; `Unknown` was originally retried.)
- Eligibility is an enum (`IdempotentOnly` / `AlwaysRetry` / `Never`), not a
  Boolean parameter — `SCALA_CODE_STYLE.md` §"Boolean Blindness". `GET`/`HEAD`
  retry by default. `POST`/`PUT`/`PATCH`/`DELETE` retry only under
  `AlwaysRetry`, which the library itself chooses for a fixed set of operations
  whose repetition provably leaves the instance in the same state — deletes
  addressed by a server-issued id, `PUT`/`PATCH` calls that replace whole state
  or set a named resource to a stated value, the bodyless mark-read calls, and
  the storage-free rendering endpoints — each documented at its call site. The
  consequence a caller accepts on those operations is that a success whose
  response was lost is repeated, and the repeat is answered for the changed
  state: a repeated `DELETE` earns a `404`. A `POST` that creates, or a `PATCH`
  that applies a delta, is never placed under `AlwaysRetry` by the library.
- Exhaustion produces `CodebergError.RetriesExhausted(ctx, attempts, last)`,
  preserving the last underlying error. No context is lost through the loop.

## Consequences

The cost is a few dozen lines we own, tested with a fake `Timer` that records
requested sleeps rather than sleeping. The benefit is a retry driver that is
deterministic under test, free of extra dependencies, and shared by both rails.

Because retry sits behind a port, replacing it later with a third-party library
is a non-breaking change. That is the property `PLAN.md` asked for; this ADR
just records that the fallback branch was taken, and why.
