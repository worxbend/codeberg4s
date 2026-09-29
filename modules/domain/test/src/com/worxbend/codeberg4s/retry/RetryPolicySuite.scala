package com.worxbend.codeberg4s.retry

import com.worxbend.codeberg4s.ValidationError

import munit.FunSuite

import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

final class RetryPolicySuite extends FunSuite:

  test("the default retries twice after the first attempt"):
    assertEquals(RetryPolicy.Default.maxAttempts, 3)

  test("the default backs off from 250 ms up to 8 s"):
    assertEquals(RetryPolicy.Default.baseDelay, 250.millis)
    assertEquals(RetryPolicy.Default.maxDelay, 8.seconds)

  test("the default jitters, so clients do not retry in lockstep"):
    assertEquals(RetryPolicy.Default.jitter, Jitter.Full)

  test("the default honours Retry-After"):
    assert(RetryPolicy.Default.respectRetryAfter)

  test("Off makes exactly one attempt"):
    assertEquals(RetryPolicy.Off.maxAttempts, 1)

  test("Off waits for nothing and stays deterministic"):
    assertEquals(RetryPolicy.Off.baseDelay, Duration.Zero)
    assertEquals(RetryPolicy.Off.maxDelay, Duration.Zero)
    assertEquals(RetryPolicy.Off.jitter, Jitter.None)

  // --- invariants ---------------------------------------------------------------

  test("the two shipped policies satisfy their own invariants"):
    assertEquals(widened(RetryPolicy.Default.validate), Right(RetryPolicy.Default))
    assertEquals(widened(RetryPolicy.Off.validate), Right(RetryPolicy.Off))

  test("the primary constructor does not check the invariants, so named-argument construction keeps compiling"):
    assertEquals(RetryPolicy.Default.copy(maxAttempts = 0).maxAttempts, 0)

  test("validate rejects zero attempts, on the maxAttempts field"):
    assertEquals(field(RetryPolicy.Default.copy(maxAttempts = 0).validate), Some("maxAttempts"))

  test("validate rejects a negative attempt count"):
    assertEquals(field(RetryPolicy.Default.copy(maxAttempts = -3).validate), Some("maxAttempts"))

  test("validate accepts exactly one attempt, which is how Off is spelled"):
    assertEquals(RetryPolicy.Default.copy(maxAttempts = 1).validate.isRight, true)

  test("validate rejects a negative base delay, on the baseDelay field"):
    assertEquals(field(RetryPolicy.Default.copy(baseDelay = -1.millis).validate), Some("baseDelay"))

  test("validate rejects a negative ceiling, on the maxDelay field"):
    assertEquals(
      field(RetryPolicy.Default.copy(baseDelay = Duration.Zero, maxDelay = -1.millis).validate),
      Some("maxDelay"),
    )

  test("validate rejects a ceiling below the base delay, on the maxDelay field"):
    assertEquals(field(RetryPolicy.Default.copy(baseDelay = 9.seconds, maxDelay = 8.seconds).validate), Some("maxDelay"))

  test("validate accepts a ceiling equal to the base delay"):
    assertEquals(RetryPolicy.Default.copy(baseDelay = 8.seconds, maxDelay = 8.seconds).validate.isRight, true)

  test("validate returns the same policy it was asked about"):
    assertEquals(widened(RetryPolicy.Default.validate).toOption, Some(RetryPolicy.Default))

  test("of builds the same value the constructor would, when the invariants hold"):
    assertEquals(RetryPolicy.of(3, 250.millis, 8.seconds, Jitter.Full, true), Right(RetryPolicy.Default))

  test("of refuses what validate refuses"):
    assertEquals(field(RetryPolicy.of(0, 250.millis, 8.seconds, Jitter.Full, true)), Some("maxAttempts"))
    assertEquals(field(RetryPolicy.of(3, 250.millis, 100.millis, Jitter.Full, true)), Some("maxDelay"))

  private def field(result: Either[ValidationError, ?]): Option[String] =
    result.swap.toOption.map(_.field)

  /** `validate` answers `Either[ValidationError, policy.type]`; munit's equality wants the plain type. */
  private def widened(result: Either[ValidationError, RetryPolicy]): Either[ValidationError, RetryPolicy] =
    result
