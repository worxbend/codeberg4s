package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.ApiErrorBody
import com.worxbend.codeberg4s.CallContext
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.HttpMethod
import com.worxbend.codeberg4s.retry.Jitter
import com.worxbend.codeberg4s.retry.RetryPolicy
import com.worxbend.codeberg4s.syntax.discard

import munit.FunSuite

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.DurationLong
import scala.concurrent.duration.FiniteDuration

/** Requested waits, not elapsed wall-clock time: every policy here passes native-duration validation.
  *
  * Fractional computed waits must survive until Timer.sleep. Integral-millisecond full jitter retains its existing
  * sampling grid; fractional waits deliberately require finer sampling. No test depends on a particular number of
  * source draws at Long.MaxValue, where inclusive sampling may need an overflow-safe composition.
  */
final class RetryEnginePrecisionSuite extends FunSuite:

  private val context: CallContext =
    CallContext("issues.list", HttpMethod.Get, "https://codeberg.org/api/v1/repos/owner/name/issues", None, 0L)

  private val failure: CodebergError = CodebergError.Api(context, 503, ApiErrorBody.Empty, None)

  test("without jitter positive sub-millisecond backoff doubles and clamps without truncation"):
    val jitter = RecordingJitter(identity)
    val policy = RetryPolicy(4, 250.micros, 900.micros, Jitter.None, respectRetryAfter = false)
    val run    = runFailing(policy, jitter, None)

    assertEquals(run.sleeps, Vector(250.micros, 500.micros, 900.micros))
    assertEquals(jitter.bounds, Vector.empty[Long])
    assertEquals(run.attempts, Vector(1, 2, 3, 4))
    assertEquals(run.result, Left(CodebergError.RetriesExhausted(context, 4, failure)))

  test("without jitter fractional-millisecond base and ceiling are requested exactly"):
    val policy = RetryPolicy(5, 1500.micros, 5250.micros, Jitter.None, respectRetryAfter = false)
    val run    = runFailing(policy, JitterSource.Deterministic, None)

    assertEquals(run.sleeps, Vector(1500.micros, 3000.micros, 5250.micros, 5250.micros))

  test("without jitter the smallest positive native duration is not rounded to zero"):
    val policy = RetryPolicy(3, 1.nano, 1.nano, Jitter.None, respectRetryAfter = false)
    val run    = runFailing(policy, JitterSource.Deterministic, None)

    assertEquals(run.sleeps, Vector(1.nano, 1.nano))

  test("without jitter a Long.MaxValue nanosecond ceiling retains its fractional millisecond"):
    val cap    = Long.MaxValue.nanos
    val policy = RetryPolicy(3, cap, cap, Jitter.None, respectRetryAfter = false)
    val run    = runFailing(policy, JitterSource.Deterministic, None)

    assertEquals(run.sleeps, Vector(cap, cap))

  test("doubling above half of Long.MaxValue saturates before multiplication can overflow"):
    val base   = (Long.MaxValue / 2L + 1L).nanos
    val cap    = Long.MaxValue.nanos
    val policy = RetryPolicy(5, base, cap, Jitter.None, respectRetryAfter = false)
    val run    = runFailing(policy, JitterSource.Deterministic, None)

    assertEquals(run.sleeps, Vector(base, cap, cap, cap))
    assert(run.sleeps.forall(delay => delay > Duration.Zero && delay <= cap))

  test("large backoff exponents saturate at the finite nanosecond ceiling"):
    val cap      = Long.MaxValue.nanos
    val policy   = RetryPolicy(66, 1.nano, cap, Jitter.None, respectRetryAfter = false)
    val run      = runFailing(policy, JitterSource.Deterministic, None)
    // BigInt is an independent oracle, not the engine's bounded Long doubling or a wrapping Long shift.
    val expected = Vector.tabulate(65)(step => (BigInt(1) << step).min(BigInt(Long.MaxValue)).toLong.nanos)

    assertEquals(run.sleeps, expected)
    assertEquals(run.attempts, (1 to 66).toVector)
    assertEquals(run.result, Left(CodebergError.RetriesExhausted(context, 66, failure)))

  test("full jitter can request the exact fractional upper bound after clamping"):
    val policy = RetryPolicy(4, 750.micros, 1250.micros, Jitter.Full, respectRetryAfter = false)
    val run    = runFailing(policy, JitterSource.Deterministic, None)

    assertEquals(run.sleeps, Vector(750.micros, 1250.micros, 1250.micros))
    assert(run.sleeps.forall(delay => delay >= Duration.Zero && delay <= policy.maxDelay))

  test("full jitter samples positive fractional bounds even when the source returns zero"):
    val jitter = RecordingJitter(_ => 0L)
    val policy = RetryPolicy(4, 750.micros, 1250.micros, Jitter.Full, respectRetryAfter = false)
    val run    = runFailing(policy, jitter, None)

    assertEquals(run.sleeps, Vector(Duration.Zero, Duration.Zero, Duration.Zero))
    assertEquals(jitter.bounds.size, 3)
    assert(jitter.bounds.forall(_ > 0L))

  test("full jitter preserves a fractional bound when the source selects its midpoint"):
    val jitter = RecordingJitter(_ / 2L)
    val policy = RetryPolicy(4, 750.micros, 1250.micros, Jitter.Full, respectRetryAfter = false)
    val run    = runFailing(policy, jitter, None)

    assertEquals(run.sleeps, Vector(375.micros, 625.micros, 625.micros))
    assert(run.sleeps.forall(delay => delay >= Duration.Zero && delay <= policy.maxDelay))

  test("full jitter retains the existing millisecond sampling grid for integral computed bounds"):
    val jitter = RecordingJitter(_ / 2L)
    val policy = RetryPolicy(4, 251.millis, 501.millis, Jitter.Full, respectRetryAfter = false)
    val run    = runFailing(policy, jitter, None)

    assertEquals(run.sleeps, Vector(125.millis, 250.millis, 250.millis))
    assertEquals(jitter.bounds, Vector(251L, 501L, 501L))

  test("full jitter selects the millisecond grid when a fractional base doubles to an integral bound"):
    val jitter = RecordingJitter(_ / 2L)
    val policy = RetryPolicy(4, 500.micros, 2.millis, Jitter.Full, respectRetryAfter = false)
    val run    = runFailing(policy, jitter, None)

    assertEquals(run.sleeps, Vector(250.micros, Duration.Zero, 1.milli))
    assertEquals(jitter.bounds, Vector(500000L, 1L, 2L))

  test("full jitter samples integral bounds in milliseconds regardless of duration storage units"):
    val jitter = RecordingJitter(_ / 2L)
    val policy = RetryPolicy(4, 251000000.nanos, 501000000.nanos, Jitter.Full, respectRetryAfter = false)
    val run    = runFailing(policy, jitter, None)

    assertEquals(run.sleeps, Vector(125.millis, 250.millis, 250.millis))
    assertEquals(jitter.bounds, Vector(251L, 501L, 501L))

  test("full jitter includes a Long.MaxValue nanosecond upper bound without overflowing"):
    val cap    = Long.MaxValue.nanos
    val policy = RetryPolicy(3, cap, cap, Jitter.Full, respectRetryAfter = false)
    val run    = runFailing(policy, JitterSource.Deterministic, None)

    assertEquals(run.sleeps, Vector(cap, cap))

  test("the production jitter source stays bounded at the largest finite nanosecond delay"):
    val cap    = Long.MaxValue.nanos
    val policy = RetryPolicy(33, cap, cap, Jitter.Full, respectRetryAfter = false)
    val run    = runFailing(policy, JitterSource.Random, None)

    // No probability assertion: every possible draw must satisfy the bounds and must not throw on cap + 1.
    assertEquals(run.sleeps.size, 32)
    assert(run.sleeps.forall(delay => delay >= Duration.Zero && delay <= cap))
    assertEquals(run.result, Left(CodebergError.RetriesExhausted(context, 33, failure)))

  test("an honoured fractional Retry-After is requested exactly without consulting full jitter"):
    val jitter = RecordingJitter(_ => 0L)
    val hint   = 875123.nanos
    val policy = RetryPolicy(3, 250.micros, 1500.micros, Jitter.Full, respectRetryAfter = true)
    val run    = runFailing(policy, jitter, Some(hint))

    assertEquals(run.sleeps, Vector(hint, hint))
    assertEquals(jitter.bounds, Vector.empty[Long])

  test("a Retry-After one nanosecond beyond a fractional ceiling stops without jitter or wrapping"):
    val jitter = RecordingJitter(identity)
    val policy = RetryPolicy(3, 250.micros, 1500.micros, Jitter.Full, respectRetryAfter = true)
    val run    = runFailing(policy, jitter, Some(1500001.nanos))

    assertEquals(run.result, Left(failure))
    assertEquals(run.attempts, Vector(1))
    assertEquals(run.sleeps, Vector.empty[FiniteDuration])
    assertEquals(jitter.bounds, Vector.empty[Long])

  test("a single-attempt policy never computes or samples even the largest finite backoff"):
    val jitter = RecordingJitter(identity)
    val cap    = Long.MaxValue.nanos
    val policy = RetryPolicy(1, cap, cap, Jitter.Full, respectRetryAfter = false)
    val run    = runFailing(policy, jitter, None)

    assertEquals(run.result, Left(failure))
    assertEquals(run.attempts, Vector(1))
    assertEquals(run.sleeps, Vector.empty[FiniteDuration])
    assertEquals(jitter.bounds, Vector.empty[Long])

  test("RetryPolicy.Off still reports one unwrapped failure without a wait"):
    val jitter = RecordingJitter(identity)
    val run    = runFailing(RetryPolicy.Off, jitter, None)

    assertEquals(run.result, Left(failure))
    assertEquals(run.attempts, Vector(1))
    assertEquals(run.sleeps, Vector.empty[FiniteDuration])
    assertEquals(jitter.bounds, Vector.empty[Long])

  private final case class Run(
      result: Exec.Result[String],
      attempts: Vector[Int],
      sleeps: Vector[FiniteDuration],
  )

  private final class RecordingJitter(draw: Long => Long) extends JitterSource:
    private val requested = ListBuffer.empty[Long]

    override def upTo(boundInclusive: Long): Long =
      requested.append(boundInclusive).discard
      draw(boundInclusive)

    def bounds: Vector[Long] = requested.toVector

  private def runFailing(policy: RetryPolicy, jitter: JitterSource, hint: Option[FiniteDuration]): Run =
    assert(policy.validate.isRight)
    val timer    = FakeTimer(0L)
    val attempts = ListBuffer.empty[Int]
    val engine   = new RetryEngine[Exec.Result](policy, timer)(using Exec.eitherExec)(using jitter)

    val result = engine.runWith("issues.list", HttpMethod.Get, RetryEligibility.IdempotentOnly): number =>
      attempts.append(number).discard
      Right(AttemptOutcome[String](Left(failure), hint))

    Run(result, attempts.toVector, timer.sleeps)
