package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.ApiErrorBody
import com.worxbend.codeberg4s.CallContext
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.TransportCause
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.retry.Jitter
import com.worxbend.codeberg4s.retry.RetryPolicy
import com.worxbend.codeberg4s.syntax.discard

import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Prop.AnyOperators
import org.scalacheck.Prop.forAll

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.DurationLong
import scala.concurrent.duration.FiniteDuration

/** The retry engine's bounds, stated over every policy rather than over the handful an example test can name.
  *
  * A retry engine is where a client library does the most damage when it is wrong: one attempt too many multiplies
  * every caller's load on the instance, one delay past the ceiling turns a transient `503` into a hung call, and a
  * failure dropped on the way out of the loop is a bug report with nothing in it. The four bounds below are exactly
  * those hazards, and each is checked against a generated policy — including the degenerate `maxAttempts = 0`, a zero
  * base delay and a zero ceiling, which are the shapes a hand-written test never bothers to write.
  *
  * Jitter is drawn from [[JitterSource.Deterministic]] throughout. That is not a way of avoiding the jitter arithmetic:
  * the deterministic source returns its bound, so it produces the '''longest''' delay full jitter can, which is the
  * worst case the ceiling property has to survive. It also keeps every case reproducible from the pinned ScalaCheck
  * seed alone, with no second source of randomness in the run.
  */
final class RetryEngineProps extends PropertyBase:

  /** What one run of the engine produced, so a property can talk about all three at once without a `val` pattern. */
  private final case class Run(
      result: Either[CodebergError, String],
      attempts: Vector[Int],
      sleeps: Vector[FiniteDuration],
  )

  /** A method and an eligibility that, together, forbid a second attempt. */
  private final case class Ineligible(method: HttpMethod, eligibility: RetryEligibility)

  private val context: CallContext =
    CallContext("issues.list", HttpMethod.Get, "https://codeberg.org/api/v1/repos/owner/name/issues", None, 12L)

  private val policies: Gen[RetryPolicy] =
    for
      attempts <- Gen.choose(0, 6)
      base     <- Gen.choose(0L, 2000L)
      ceiling  <- Gen.choose(0L, 20000L)
      jitter   <- Gen.oneOf(Jitter.None, Jitter.Full)
      respect  <- Gen.oneOf(true, false)
    yield RetryPolicy(attempts, base.millis, ceiling.millis, jitter, respect)

  /** Failures the engine is allowed to repeat: the retryable statuses, and the transport causes that can heal. */
  private val retryableErrors: Gen[CodebergError] =
    Gen.oneOf(
      Gen
        .oneOf(StatusMapping.RetryableStatuses.toVector)
        .map(status => CodebergError.Api(context, status, ApiErrorBody.Empty, None)),
      Gen
        .oneOf[TransportCause](
          TransportCause.ConnectionFailed("connection reset"),
          TransportCause.Timeout("read timed out"),
          TransportCause.Dns("name not resolved"),
          TransportCause.Unknown("unclassified"),
        )
        .map(cause  => CodebergError.Transport(context, cause)),
    )

  /** Failures that repeating cannot fix, so the engine must not spend a second attempt on them. */
  private val nonRetryableErrors: Gen[CodebergError] =
    Gen.oneOf(
      Gen
        .choose(400, 599)
        .suchThat(status => !StatusMapping.isRetryable(status))
        .map(status => CodebergError.Api(context, status, ApiErrorBody.Empty, None)),
      Gen
        .oneOf[TransportCause](
          TransportCause.Tls("certificate expired"),
          TransportCause.Interrupted("cancelled"),
          TransportCause.ResponseTooLarge("Stream length limit of 16777216 bytes exceeded"),
        )
        .map(cause  => CodebergError.Transport(context, cause)),
      Gen.const(CodebergError.DecodingFailed(context, "{", JsonPath.Root, "unexpected end of input")),
      Gen.const(ValidationError("owner", "must not be blank")),
      Gen.const(
        CodebergError.RetriesExhausted(context, 2, CodebergError.Transport(context, TransportCause.Timeout("read")))
      ),
    )

  private val methods: Gen[HttpMethod] = Gen.oneOf(HttpMethod.values.toVector)

  private val eligibilities: Gen[RetryEligibility] = Gen.oneOf(RetryEligibility.values.toVector)

  private val ineligible: Gen[Ineligible] =
    for
      method      <- methods
      eligibility <- eligibilities
      if !eligibility.allows(method)
    yield Ineligible(method, eligibility)

  private def engine(policy: RetryPolicy, timer: FakeTimer): RetryEngine[Exec.Result] =
    new RetryEngine[Exec.Result](policy, timer)(using Exec.eitherExec)(using JitterSource.Deterministic)

  /** Runs a call that fails every time, and reports everything the engine did on the way. */
  private def runFailing(
      policy: RetryPolicy,
      method: HttpMethod,
      eligibility: RetryEligibility,
      error: CodebergError,
      hint: Option[FiniteDuration],
  ): Run =
    val timer    = FakeTimer(0L)
    val attempts = ListBuffer.empty[Int]

    val result = engine(policy, timer).runWith("issues.list", method, eligibility): number =>
      attempts.append(number).discard
      Right(AttemptOutcome[String](Left(error), hint))

    Run(result, attempts.toVector, timer.sleeps)

  property("a failing call is attempted no more often than the policy allows".tag(Property)):
    forAll(policies, retryableErrors, methods, eligibilities) { (policy, error, method, eligibility) =>
      val run = runFailing(policy, method, eligibility, error, None)
      Prop
        .propBoolean(run.attempts.size <= math.max(1, policy.maxAttempts))
        .label(s"${run.attempts.size} attempts against maxAttempts=${policy.maxAttempts}") &&
      (run.attempts ?= (1 to run.attempts.size).toVector).label("attempt numbers are consecutive and one-based") &&
      (run.sleeps.size ?= run.attempts.size - 1).label("the engine must not sleep after its final attempt") &&
      Prop.propBoolean(run.result.isLeft).label("a call that never succeeds must fail")
    }

  property("no delay the engine waits for exceeds the policy's ceiling".tag(Property)):
    forAll(policies, retryableErrors, Gen.option(Gen.choose(0L, 120000L))) { (policy, error, hintMillis) =>
      val run = runFailing(
        policy,
        HttpMethod.Get,
        RetryEligibility.AlwaysRetry,
        error,
        hintMillis.map(_.millis),
      )
      Prop
        .propBoolean(run.sleeps.forall(_ <= policy.maxDelay))
        .label(s"slept ${run.sleeps} against a ceiling of ${policy.maxDelay}")
    }

  property("without a server hint the backoff doubles from the base delay and stops at the ceiling".tag(Property)):
    forAll(Gen.choose(1, 6), Gen.choose(0L, 500L), Gen.choose(0L, 20000L), retryableErrors) {
      (attempts, base, ceiling, error) =>
        val policy   = RetryPolicy(attempts, base.millis, ceiling.millis, Jitter.None, respectRetryAfter = false)
        val run      = runFailing(policy, HttpMethod.Get, RetryEligibility.AlwaysRetry, error, None)
        val expected = (0 until math.max(0, attempts - 1)).map(step =>
          math.min(base * (1L << step), ceiling).millis
        ).toVector
        (run.sleeps ?= expected).label(s"base $base, ceiling $ceiling, $attempts attempts")
    }

  property("a failure that repeating cannot fix is attempted once and reported unwrapped".tag(Property)):
    forAll(policies, nonRetryableErrors) { (policy, error) =>
      val run = runFailing(policy, HttpMethod.Get, RetryEligibility.AlwaysRetry, error, None)
      (run.attempts ?= Vector(1)).label("exactly one attempt") &&
      (run.result ?= Left(error)).label("the failure must not be wrapped in RetriesExhausted") &&
      (run.sleeps ?= Vector.empty[FiniteDuration]).label("nothing to wait for")
    }

  property("a call the caller did not vouch for is never repeated".tag(Property)):
    forAll(policies, retryableErrors, ineligible) { (policy, error, forbidden) =>
      val run = runFailing(policy, forbidden.method, forbidden.eligibility, error, None)
      (run.attempts ?= Vector(1)).label(s"${forbidden.eligibility} must not repeat ${forbidden.method}") &&
      (run.result ?= Left(error)) &&
      (run.sleeps ?= Vector.empty[FiniteDuration])
    }

  property("giving up preserves the last failure verbatim and the true attempt count".tag(Property)):
    forAll(Gen.choose(2, 6), retryableErrors, retryableErrors) { (maxAttempts, earlier, last) =>
      val policy   = RetryPolicy(maxAttempts, 1.milli, 1.milli, Jitter.None, respectRetryAfter = false)
      val timer    = FakeTimer(0L)
      val attempts = ListBuffer.empty[Int]

      val result = engine(policy, timer).runWith("issues.list", HttpMethod.Get, RetryEligibility.AlwaysRetry): number =>
        attempts.append(number).discard
        Right(AttemptOutcome.failed[String](if number < maxAttempts then earlier else last))

      result match
        case Left(CodebergError.RetriesExhausted(_, reported, carried)) =>
          (reported ?= maxAttempts).label("the reported attempt count") &&
          (carried ?= last).label("the last failure, not the first") &&
          (attempts.size ?= maxAttempts).label("the attempts actually made")
        case other                                                      =>
          Prop.falsified.label(s"expected RetriesExhausted after $maxAttempts attempts, got $other")
    }

  property("a call that succeeds on its k-th attempt is attempted exactly k times".tag(Property)):
    forAll(Gen.choose(1, 6), retryableErrors) { (successAt, error) =>
      val policy   = RetryPolicy(6, 1.milli, 1.milli, Jitter.None, respectRetryAfter = false)
      val timer    = FakeTimer(0L)
      val attempts = ListBuffer.empty[Int]

      val result = engine(policy, timer).runWith("issues.list", HttpMethod.Get, RetryEligibility.AlwaysRetry): number =>
        attempts.append(number).discard
        Right(if number < successAt then AttemptOutcome.failed[String](error) else AttemptOutcome.succeeded("ok"))

      (result ?= Right("ok")) &&
      (attempts.size ?= successAt) &&
      (timer.sleeps.size ?= successAt - 1)
    }

  property("a server backoff hint replaces the computed delay only when the policy honours it".tag(Property)):
    forAll(Gen.choose(0L, 60000L), Gen.choose(1L, 20000L), retryableErrors) { (hint, ceiling, error) =>
      val honouring = RetryPolicy(2, 10.millis, ceiling.millis, Jitter.None, respectRetryAfter = true)
      val ignoring  = honouring.copy(respectRetryAfter = false)
      val honoured  =
        runFailing(honouring, HttpMethod.Get, RetryEligibility.AlwaysRetry, error, Some(hint.millis))
      val ignored   =
        runFailing(ignoring, HttpMethod.Get, RetryEligibility.AlwaysRetry, error, Some(hint.millis))

      (honoured.sleeps ?= Vector(math.min(hint, ceiling).millis)).label("the hint, clamped to the ceiling") &&
      (ignored.sleeps ?= Vector(math.min(10L, ceiling).millis)).label("the policy's own backoff")
    }
