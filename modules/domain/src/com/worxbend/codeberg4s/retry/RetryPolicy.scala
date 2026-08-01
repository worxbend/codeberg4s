package com.worxbend.codeberg4s.retry

import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

/** When and how often a failed call is attempted again.
  *
  * The policy describes the schedule only; deciding *whether* a particular failure is retryable belongs to the retry
  * engine, and only safe methods are retried without the caller opting in.
  *
  * The delay before attempt `n` (one-based) is `min(baseDelay * 2^(n - 1), maxDelay)`, then adjusted by [[jitter]].
  *
  * @param maxAttempts
  *   total attempts including the first one, so `1` means "no retry"
  * @param baseDelay
  *   the delay before the second attempt, doubled for each further attempt
  * @param maxDelay
  *   the ceiling for a computed delay, applied before jitter
  * @param jitter
  *   how much randomness to add to the computed delay
  * @param respectRetryAfter
  *   when `true`, a `Retry-After` header from the instance overrides the computed delay. Forgejo sends it on `429`, and
  *   ignoring it is the fastest way to earn a longer ban
  */
final case class RetryPolicy(
    maxAttempts: Int,
    baseDelay: FiniteDuration,
    maxDelay: FiniteDuration,
    jitter: Jitter,
    respectRetryAfter: Boolean,
)

object RetryPolicy:

  /** A conservative default: three attempts, 250 ms base delay, 8 s ceiling, full jitter, `Retry-After` honoured. */
  val Default: RetryPolicy = RetryPolicy(
    maxAttempts       = 3,
    baseDelay         = 250.millis,
    maxDelay          = 8.seconds,
    jitter            = Jitter.Full,
    respectRetryAfter = true,
  )

  /** No retrying at all: one attempt, no delay. Useful in tests and for callers doing their own scheduling. */
  val Off: RetryPolicy = RetryPolicy(
    maxAttempts       = 1,
    baseDelay         = Duration.Zero,
    maxDelay          = Duration.Zero,
    jitter            = Jitter.None,
    respectRetryAfter = false,
  )
