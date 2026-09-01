package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.core.Exec.flatMap
import com.worxbend.codeberg4s.retry.{Jitter, RetryPolicy}
import com.worxbend.codeberg4s.{CallContext, CodebergError, HttpMethod, TransportCause}

import scala.concurrent.duration.{DurationLong, FiniteDuration}

/** Repeats a failed call according to a [[com.worxbend.codeberg4s.retry.RetryPolicy]].
  *
  * The engine decides three things, in this order:
  *   1. is the call allowed to be repeated at all — [[RetryEligibility]], which the caller states;
  *   1. is this particular failure worth repeating — a transport failure that can heal, or one of
  *      [[StatusMapping.RetryableStatuses]];
  *   1. how long to wait — the server's `Retry-After` when the policy honours it, otherwise `baseDelay * 2^(n - 1)`
  *      after the *n*-th failed attempt, clamped to `maxDelay` and then jittered.
  *
  * Nothing is lost when it gives up: a call the engine was repeating and could not repeat again reports
  * [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]], carrying the number of attempts and the last underlying
  * error verbatim. Every other failure is reported unwrapped, because there is nothing to explain: a call that fails
  * once and is not repeated — a `404`, a `POST` under [[RetryEligibility.IdempotentOnly]], a policy of
  * [[com.worxbend.codeberg4s.retry.RetryPolicy.Off]] — and equally a call whose last attempt failed in a way no
  * repetition could fix, such as a `503` followed by a `404`. `RetriesExhausted` therefore means the attempt budget ran
  * out, never merely that more than one attempt was made.
  *
  * Waiting goes through [[Timer]] and jitter through [[JitterSource]], so the whole schedule is observable in a unit
  * test without a single real millisecond passing.
  *
  * @param policy
  *   how many attempts and how long to wait between them
  * @param timer
  *   the port that performs the waiting
  * @tparam F
  *   the effect the client runs in
  */
final class RetryEngine[F[_]](policy: RetryPolicy, timer: Timer[F])(using exec: Exec[F])(using jitter: JitterSource):

  /** Runs `attempt` under the policy, treating the call as a safe read.
    *
    * Equivalent to [[runWith]] with `GET` and [[RetryEligibility.IdempotentOnly]]. The attempt reports its failure as a
    * `Left` inside a successful effect, which is the shape the request pipeline already produces; there is nowhere for
    * the server's `Retry-After` hint to travel, so the schedule is always the policy's own backoff. Anything that
    * mutates state, and anything that wants the server's backoff respected, goes through [[runWith]].
    *
    * @param operation
    *   the stable operation id, used when a failure carries no call context of its own
    * @param attempt
    *   makes the call; receives the one-based attempt number
    */
  def run[A](operation: String)(attempt: Int => F[Either[CodebergError, A]]): F[A] =
    runWith(operation, HttpMethod.Get, RetryEligibility.IdempotentOnly): number =>
      exec.map(attempt(number))(result => AttemptOutcome(result, None))

  /** Runs `attempt` under the policy, reading the failure from `F`'s own error channel.
    *
    * The same contract as [[run]] for a caller whose attempt fails the effect itself — a failed `Future` — rather than
    * returning a `Left`. Non-[[com.worxbend.codeberg4s.CodebergError]] defects are not caught; see [[Exec.attempt]].
    */
  def runEffect[A](operation: String)(attempt: Int => F[A]): F[A] =
    run(operation)(number => exec.attempt(attempt(number)))

  /** Runs `attempt` under the policy.
    *
    * @param operation
    *   the stable operation id, used when a failure carries no call context of its own
    * @param method
    *   the method the call uses; consulted only by [[RetryEligibility.IdempotentOnly]]
    * @param eligibility
    *   whether repeating this call is acceptable at all
    * @param attempt
    *   makes the call; receives the one-based attempt number and may report a server backoff hint
    */
  def runWith[A](operation: String, method: HttpMethod, eligibility: RetryEligibility)(
      attempt: Int => F[AttemptOutcome[A]]
  ): F[A] =
    loop(operation, method, eligibility, 1, attempt)

  private def loop[A](
      operation: String,
      method: HttpMethod,
      eligibility: RetryEligibility,
      number: Int,
      attempt: Int => F[AttemptOutcome[A]],
  ): F[A] =
    exec.suspend(() => attempt(number)).flatMap: outcome =>
      outcome.result match
        case Right(value) => exec.pure(value)
        case Left(error)  =>
          if shouldRetry(method, eligibility, number, error) then
            waitThenRetry(operation, method, eligibility, number, attempt, outcome.retryAfter)
          else exec.raise(giveUp(operation, method, eligibility, number, error))

  private def waitThenRetry[A](
      operation: String,
      method: HttpMethod,
      eligibility: RetryEligibility,
      number: Int,
      attempt: Int => F[AttemptOutcome[A]],
      hint: Option[FiniteDuration],
  ): F[A] =
    timer.sleep(delayAfter(number, hint)).flatMap: _ =>
      loop(operation, method, eligibility, number + 1, attempt)

  private def shouldRetry(
      method: HttpMethod,
      eligibility: RetryEligibility,
      number: Int,
      error: CodebergError,
  ): Boolean =
    number < policy.maxAttempts && eligibility.allows(method) && RetryEngine.isRetryable(error)

  /** The failure the caller receives once the loop has stopped.
    *
    * Wrapping in [[com.worxbend.codeberg4s.CodebergError.RetriesExhausted]] is a claim that the attempt budget is what
    * ended the call, so it is made only when every part of that claim holds: more than one attempt was made, the caller
    * allowed repetition, and the failure that ended the loop is itself one the engine would have repeated had an
    * attempt been left. A call that meets a `503` and then a `404` stopped because a `404` cannot be repeated, not
    * because the budget ran out, so it reports that `404` unwrapped — the same failure it would have reported had the
    * `404` arrived first.
    */
  private def giveUp(
      operation: String,
      method: HttpMethod,
      eligibility: RetryEligibility,
      attempts: Int,
      error: CodebergError,
  ): CodebergError =
    if attempts > 1 && eligibility.allows(method) && RetryEngine.isRetryable(error) then
      CodebergError.RetriesExhausted(RetryEngine.contextOf(operation, method, error), attempts, error)
    else error

  private def delayAfter(failedAttempts: Int, hint: Option[FiniteDuration]): FiniteDuration =
    hint match
      case Some(retryAfter) if policy.respectRetryAfter => capped(retryAfter)
      case _                                            => jittered(backoff(failedAttempts))

  private def backoff(failedAttempts: Int): FiniteDuration =
    RetryEngine.doubled(policy.baseDelay.toMillis, failedAttempts - 1, policy.maxDelay.toMillis).millis

  private def capped(delay: FiniteDuration): FiniteDuration =
    if delay > policy.maxDelay then policy.maxDelay else delay

  private def jittered(delay: FiniteDuration): FiniteDuration =
    policy.jitter match
      case Jitter.None => delay
      case Jitter.Full => jitter.upTo(delay.toMillis).millis

object RetryEngine:

  /** Stands in for the URI when the failure that ended a retry loop carried no call context of its own. */
  val UnknownUri: String = "(uri unknown)"

  /** Whether repeating a call that failed this way could plausibly succeed.
    *
    * A decoding failure, a pre-flight validation failure and an already-exhausted retry are never repeated: the same
    * request would produce the same answer.
    */
  private[core] def isRetryable(error: CodebergError): Boolean =
    error match
      case CodebergError.Transport(_, cause)        => isRetryable(cause)
      case CodebergError.Api(_, status, _, _)       => StatusMapping.isRetryable(status)
      case CodebergError.DecodingFailed(_, _, _, _) => false
      case CodebergError.Validation(_, _)           => false
      case CodebergError.RetriesExhausted(_, _, _)  => false
      // Never reaches this engine — a page walk is assembled above it — and
      // repeating the walk would stop at the same cap, so it is not retryable
      // even in principle.
      case CodebergError.WalkTruncated(_, _)        => false

  /** A TLS failure does not heal by itself, an interruption was asked for, and an oversized body would arrive oversized
    * again — repeating that one would download the body the bound exists to refuse once per attempt. Everything else
    * may be transient.
    */
  private[core] def isRetryable(cause: TransportCause): Boolean =
    cause match
      case TransportCause.ConnectionFailed(_) => true
      case TransportCause.Timeout(_)          => true
      case TransportCause.Dns(_)              => true
      case TransportCause.Unknown(_)          => true
      case TransportCause.Tls(_)              => false
      case TransportCause.Interrupted(_)      => false
      case TransportCause.ResponseTooLarge(_) => false

  private[core] def contextOf(operation: String, method: HttpMethod, error: CodebergError): CallContext =
    error match
      case CodebergError.Transport(ctx, _)            => ctx
      case CodebergError.Api(ctx, _, _, _)            => ctx
      case CodebergError.DecodingFailed(ctx, _, _, _) => ctx
      case CodebergError.RetriesExhausted(ctx, _, _)  => ctx
      case CodebergError.Validation(_, _)             => CallContext(operation, method, UnknownUri, None, 0L)
      case CodebergError.WalkTruncated(_, _)          => CallContext(operation, method, UnknownUri, None, 0L)

  /** `value` doubled `times` over, stopping at `cap`. Written as a fold rather than a shift so that a large attempt
    * count cannot overflow the exponent.
    */
  private def doubled(value: Long, times: Int, cap: Long): Long =
    if times <= 0 || value >= cap then math.min(value, cap)
    else doubled(value * 2, times - 1, cap)
