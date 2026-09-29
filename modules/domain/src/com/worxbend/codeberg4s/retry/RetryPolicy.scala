package com.worxbend.codeberg4s.retry

import com.worxbend.codeberg4s.ValidationError

import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}

/** When and how often a failed call is attempted again.
  *
  * The policy describes the schedule only; deciding *whether* a particular failure is retryable belongs to the retry
  * engine, and only safe methods are retried without the caller opting in.
  *
  * The delay before attempt `n` (one-based) is `min(baseDelay * 2^(n - 1), maxDelay)`, then adjusted by [[jitter]].
  *
  * ==Invariants==
  *
  * A policy is well-formed when all of the following hold:
  *
  *   - `maxAttempts >= 1` — zero attempts is a client that never calls;
  *   - `baseDelay >= 0` and `maxDelay >= 0` — a negative delay has no meaning to a scheduler;
  *   - `maxDelay >= baseDelay` — a ceiling below the first delay would clamp every delay to something the caller did
  *     not ask for.
  *
  * '''The primary constructor does not check them''', and neither does `copy`: `RetryPolicy(maxAttempts = 0, …)`
  * compiles and constructs. That is deliberate — the constructor is a plain case-class constructor so that a policy can
  * be written out with named arguments, as [[RetryPolicy.Default]] and every guide do — but it means a policy built
  * that way is trusted, not verified. [[RetryPolicy.of]] is the same arity with the check in front, and [[validate]]
  * runs the check on a policy already in hand; either is the right call for a value that came from configuration.
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
):

  /** Checks the invariants listed in the class note, which the constructor did not.
    *
    * The first violated invariant is reported, on the field that violates it — `"maxAttempts"`, `"baseDelay"` or
    * `"maxDelay"` — so a policy read from configuration fails where it was misread rather than at the first retry.
    *
    * @return
    *   this policy unchanged, or a [[ValidationError]] on the offending field
    */
  def validate: Either[ValidationError, this.type] =
    if maxAttempts < RetryPolicy.MinAttempts then
      Left(ValidationError("maxAttempts", s"must be at least ${RetryPolicy.MinAttempts}"))
    else if baseDelay < Duration.Zero then Left(ValidationError("baseDelay", "must not be negative"))
    else if maxDelay < Duration.Zero then Left(ValidationError("maxDelay", "must not be negative"))
    else if maxDelay < baseDelay then Left(ValidationError("maxDelay", "must be at least baseDelay"))
    else Right(this)

object RetryPolicy:

  /** The fewest attempts a policy can describe: the first call itself. */
  private val MinAttempts: Int = 1

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

  /** Builds a policy and checks the invariants listed in the class note, which the constructor does not.
    *
    * Same parameters as the constructor, in the same order, so a call site can switch between the two by changing the
    * name; the difference is that this one returns a `Left` for `maxAttempts = 0`, a negative delay, or a `maxDelay`
    * below `baseDelay` instead of a policy that misbehaves later. See [[RetryPolicy.validate]] for what is reported.
    *
    * @return
    *   the policy, or a [[ValidationError]] on the first offending field
    */
  def of(
      maxAttempts: Int,
      baseDelay: FiniteDuration,
      maxDelay: FiniteDuration,
      jitter: Jitter,
      respectRetryAfter: Boolean,
  ): Either[ValidationError, RetryPolicy] =
    RetryPolicy(maxAttempts, baseDelay, maxDelay, jitter, respectRetryAfter).validate
