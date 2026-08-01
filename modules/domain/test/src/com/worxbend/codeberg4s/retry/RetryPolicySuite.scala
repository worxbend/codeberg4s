package com.worxbend.codeberg4s.retry

import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt

import munit.FunSuite

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
