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
- `Retry-After` is honoured when `RetryPolicy.respectRetryAfter` is set, clamped
  to `maxDelay`.
- Retryable statuses: 429, 500, 502, 503, 504, plus transport-level failures.
- Eligibility is an enum (`IdempotentOnly` / `AlwaysRetry` / `Never`), not a
  Boolean parameter — `SCALA_CODE_STYLE.md` §"Boolean Blindness". `GET`/`HEAD`
  retry by default; `POST`/`PATCH`/`DELETE` never do unless the caller opts in
  per call.
- Exhaustion produces `CodebergError.RetriesExhausted(ctx, attempts, last)`,
  preserving the last underlying error. No context is lost through the loop.

## Consequences

The cost is a few dozen lines we own, tested with a fake `Timer` that records
requested sleeps rather than sleeping. The benefit is a retry driver that is
deterministic under test, free of extra dependencies, and shared by both rails.

Because retry sits behind a port, replacing it later with a third-party library
is a non-breaking change. That is the property `PLAN.md` asked for; this ADR
just records that the fallback branch was taken, and why.
