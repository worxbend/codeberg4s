package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.ApiErrorBody
import com.worxbend.codeberg4s.CallContext
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.TransportCause
import com.worxbend.codeberg4s.retry.Jitter
import com.worxbend.codeberg4s.retry.RetryPolicy
import com.worxbend.codeberg4s.syntax.discard

import munit.FunSuite

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

final class RetryEngineSuite extends FunSuite:

  private val context: CallContext =
    CallContext("issues.list", HttpMethod.Get, "https://codeberg.org/api/v1/repos/owner/name/issues", None, 12L)

  test("a call that succeeds first time is attempted once and never sleeps"):
    val timer    = FakeTimer(0L)
    val attempts = ListBuffer.empty[Int]

    val result = engine(RetryPolicy.Default, timer, JitterSource.Deterministic).run("issues.list"): number =>
      attempts.append(number).discard
      Right(Right("ok"))

    assertEquals(result, Right("ok"))
    assertEquals(attempts.toList, List(1))
    assertEquals(timer.sleeps, Vector.empty[FiniteDuration])

  test("a retryable failure is attempted again and the later value is returned"):
    val timer    = FakeTimer(0L)
    val attempts = ListBuffer.empty[Int]

    val result = engine(RetryPolicy.Default, timer, JitterSource.Deterministic).run("issues.list"): number =>
      attempts.append(number).discard
      if number < 2 then Right(Left(apiFailure(503))) else Right(Right("ok"))

    assertEquals(result, Right("ok"))
    assertEquals(attempts.toList, List(1, 2))
    assertEquals(timer.sleeps, Vector(250.millis))

  test("running out of attempts reports RetriesExhausted carrying the last failure"):
    val timer    = FakeTimer(0L)
    val attempts = ListBuffer.empty[Int]
    val last     = apiFailure(503)

    val result = engine(RetryPolicy.Default, timer, JitterSource.Deterministic)
      .run("issues.list")(failing(attempts, last))

    assertEquals(result, Left(CodebergError.RetriesExhausted(context, 3, last)))
    assertEquals(attempts.toList, List(1, 2, 3))

  test("the backoff doubles after every failed attempt"):
    val timer = FakeTimer(0L)

    engine(RetryPolicy.Default, timer, JitterSource.Deterministic)
      .run("issues.list")(failing(ListBuffer.empty[Int], apiFailure(503)))
      .discard

    assertEquals(timer.sleeps, Vector(250.millis, 500.millis))

  test("the backoff never exceeds the policy's ceiling"):
    val timer  = FakeTimer(0L)
    val policy = RetryPolicy(
      maxAttempts       = 5,
      baseDelay         = 1.second,
      maxDelay          = 2.seconds,
      jitter            = Jitter.None,
      respectRetryAfter = false,
    )

    engine(policy, timer, JitterSource.Deterministic)
      .run("issues.list")(failing(ListBuffer.empty[Int], apiFailure(503)))
      .discard

    assertEquals(timer.sleeps, Vector(1.second, 2.seconds, 2.seconds, 2.seconds))

  test("full jitter draws the delay from the injected source"):
    val timer   = FakeTimer(0L)
    val halving = new JitterSource:
      override def upTo(boundInclusive: Long): Long = boundInclusive / 2

    engine(RetryPolicy.Default, timer, halving)
      .run("issues.list")(failing(ListBuffer.empty[Int], apiFailure(503)))
      .discard

    assertEquals(timer.sleeps, Vector(125.millis, 250.millis))

  test("without jitter the computed delay is used exactly"):
    val timer  = FakeTimer(0L)
    val policy = RetryPolicy.Default.copy(jitter = Jitter.None)

    engine(policy, timer, JitterSource.Deterministic)
      .run("issues.list")(failing(ListBuffer.empty[Int], apiFailure(503)))
      .discard

    assertEquals(timer.sleeps, Vector(250.millis, 500.millis))

  test("a failure that repeating cannot fix ends the call at the first attempt"):
    val timer    = FakeTimer(0L)
    val attempts = ListBuffer.empty[Int]
    val last     = apiFailure(404)

    val result = engine(RetryPolicy.Default, timer, JitterSource.Deterministic)
      .run("issues.list")(failing(attempts, last))

    assertEquals(result, Left(last))
    assertEquals(attempts.toList, List(1))
    assert(timer.sleeps.isEmpty)

  test("a timeout is worth attempting again"):
    val timer    = FakeTimer(0L)
    val attempts = ListBuffer.empty[Int]
    val last     = CodebergError.Transport(context, TransportCause.Timeout("read timed out"))

    engine(RetryPolicy.Default, timer, JitterSource.Deterministic).run("issues.list")(failing(attempts, last)).discard

    assertEquals(attempts.toList, List(1, 2, 3))

  test("an interrupted call is not attempted again"):
    val timer    = FakeTimer(0L)
    val attempts = ListBuffer.empty[Int]
    val last     = CodebergError.Transport(context, TransportCause.Interrupted("cancelled"))

    val result = engine(RetryPolicy.Default, timer, JitterSource.Deterministic)
      .run("issues.list")(failing(attempts, last))

    assertEquals(result, Left(last))
    assertEquals(attempts.toList, List(1))

  test("a policy of one attempt reports the failure unwrapped"):
    val timer    = FakeTimer(0L)
    val attempts = ListBuffer.empty[Int]
    val last     = apiFailure(503)

    val result = engine(RetryPolicy.Off, timer, JitterSource.Deterministic).run("issues.list")(failing(attempts, last))

    assertEquals(result, Left(last))
    assertEquals(attempts.toList, List(1))
    assert(timer.sleeps.isEmpty)

  test("eligibility Never stops after the first attempt whatever the failure"):
    val timer    = FakeTimer(0L)
    val attempts = ListBuffer.empty[Int]
    val last     = apiFailure(503)

    val result = engine(RetryPolicy.Default, timer, JitterSource.Deterministic)
      .runWith("issues.create", HttpMethod.Post, RetryEligibility.Never)(outcomes(attempts, last))

    assertEquals(result, Left(last))
    assertEquals(attempts.toList, List(1))

  test("eligibility IdempotentOnly never repeats a mutating method"):
    val timer    = FakeTimer(0L)
    val attempts = ListBuffer.empty[Int]
    val last     = apiFailure(503)

    val result = engine(RetryPolicy.Default, timer, JitterSource.Deterministic)
      .runWith("issues.create", HttpMethod.Post, RetryEligibility.IdempotentOnly)(outcomes(attempts, last))

    assertEquals(result, Left(last))
    assertEquals(attempts.toList, List(1))

  test("eligibility AlwaysRetry repeats a mutating method the caller vouched for"):
    val timer    = FakeTimer(0L)
    val attempts = ListBuffer.empty[Int]

    val result = engine(RetryPolicy.Default, timer, JitterSource.Deterministic)
      .runWith("issues.create", HttpMethod.Post, RetryEligibility.AlwaysRetry)(outcomes(attempts, apiFailure(503)))

    assert(result.isLeft)
    assertEquals(attempts.toList, List(1, 2, 3))

  test("Retry-After replaces the computed backoff when the policy honours it"):
    val timer     = FakeTimer(0L)
    val attempts  = ListBuffer.empty[Int]
    val throttled = AttemptOutcome[String](Left(apiFailure(429)), Some(2.seconds))

    val attempt: Int => Exec.Result[AttemptOutcome[String]] = number =>
      attempts.append(number).discard
      if number < 2 then Right(throttled) else Right(AttemptOutcome.succeeded("ok"))

    val result = engine(RetryPolicy.Default, timer, JitterSource.Deterministic)
      .runWith("issues.list", HttpMethod.Get, RetryEligibility.IdempotentOnly)(attempt)

    assertEquals(result, Right("ok"))
    assertEquals(timer.sleeps, Vector(2.seconds))

  test("Retry-After is clamped to the policy's ceiling"):
    val timer     = FakeTimer(0L)
    val throttled = AttemptOutcome[String](Left(apiFailure(429)), Some(30.seconds))

    engine(RetryPolicy.Default, timer, JitterSource.Deterministic)
      .runWith("issues.list", HttpMethod.Get, RetryEligibility.IdempotentOnly)(_ => Right(throttled))
      .discard

    assertEquals(timer.sleeps, Vector(8.seconds, 8.seconds))

  test("Retry-After is ignored when the policy does not honour it"):
    val timer     = FakeTimer(0L)
    val policy    = RetryPolicy.Default.copy(jitter = Jitter.None, respectRetryAfter = false)
    val throttled = AttemptOutcome[String](Left(apiFailure(429)), Some(2.seconds))

    engine(policy, timer, JitterSource.Deterministic)
      .runWith("issues.list", HttpMethod.Get, RetryEligibility.IdempotentOnly)(_ => Right(throttled))
      .discard

    assertEquals(timer.sleeps, Vector(250.millis, 500.millis))

  private def engine(policy: RetryPolicy, timer: FakeTimer, jitter: JitterSource): RetryEngine[Exec.Result] =
    new RetryEngine[Exec.Result](policy, timer)(using Exec.eitherExec)(using jitter)

  /** An attempt that always fails, in the shape [[RetryEngine.run]] wants: the failure travels as a `Left` inside a
    * successful effect.
    */
  private def failing(
      attempts: ListBuffer[Int],
      error: CodebergError,
  ): Int => Exec.Result[Exec.Result[String]] =
    number =>
      attempts.append(number).discard
      Right(Left(error))

  private def outcomes(
      attempts: ListBuffer[Int],
      error: CodebergError,
  ): Int => Exec.Result[AttemptOutcome[String]] =
    number =>
      attempts.append(number).discard
      Right(AttemptOutcome.failed(error))

  private def apiFailure(status: Int): CodebergError =
    CodebergError.Api(context, status, ApiErrorBody.Empty)
