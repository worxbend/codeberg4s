package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.syntax.discard

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration

import java.util.concurrent.atomic.AtomicLong

/** A [[Timer]] that records what it was asked to wait for instead of waiting.
  *
  * Every retry test asserts on [[sleeps]], so the whole backoff schedule — the exponent, the ceiling, the jitter and
  * `Retry-After` — is observable without a single real millisecond passing. The simulated clock advances by exactly
  * the requested duration, which keeps [[nowMillis]] consistent with the sleeps a test asked for.
  *
  * @param startMillis
  *   the value [[nowMillis]] reports before anything has slept
  */
final class FakeTimer(startMillis: Long) extends Timer[Exec.Result]:

  private val clock     = AtomicLong(startMillis)
  private val requested = ListBuffer.empty[FiniteDuration]

  override def nowMillis: Exec.Result[Long] = Right(clock.get())

  override def sleep(duration: FiniteDuration): Exec.Result[Unit] =
    requested.append(duration).discard
    clock.addAndGet(duration.toMillis).discard
    Right(())

  /** Every duration this timer was asked to wait for, in the order it was asked. */
  def sleeps: Vector[FiniteDuration] = requested.toVector
