package com.worxbend.codeberg4s.core

import java.util.concurrent.ThreadLocalRandom

/** Where [[RetryEngine]] draws randomness from.
  *
  * Randomness is a dependency, like time. The engine never calls `scala.util.Random` itself: it asks this port, so a
  * test can pin the schedule exactly and still exercise the jitter arithmetic. Production uses [[JitterSource.Random]],
  * which the companion also exposes as the given instance, so a caller that does not care writes
  * `RetryEngine(policy, timer)` and gets sane behaviour.
  */
trait JitterSource:

  /** A pseudo-random value uniformly distributed in `[0, boundInclusive]` ticks, inclusive even at `Long.MaxValue`.
    *
    * The caller chooses the tick unit; the result has the same unit as the bound. [[RetryEngine]] retains millisecond
    * ticks for exactly integral-millisecond computed bounds, preserving the original sampling grid and injected-source
    * behaviour for those policies. Fractional-millisecond bounds use nanosecond ticks instead.
    *
    * A non-positive bound yields `0`. Implementations must be safe to call from several threads.
    */
  def upTo(boundInclusive: Long): Long

object JitterSource:

  /** Draws from [[java.util.concurrent.ThreadLocalRandom]]: no shared state, no lock, no seed to manage.
    *
    * At `Long.MaxValue`, two independent uniform draws select a high part in `[0, Long.MaxValue / 2]` and a bit in
    * `[0, 1]`. Each result of `2 * high + bit` has exactly one preimage, so all nonnegative Longs are equally likely,
    * including the maximum. Both exclusive bounds and the composition fit Long; no rejection loop is needed.
    */
  val Random: JitterSource = new JitterSource:

    override def upTo(boundInclusive: Long): Long =
      if boundInclusive <= 0L then 0L
      else if boundInclusive.equals(Long.MaxValue) then
        val random = ThreadLocalRandom.current()
        val high   = random.nextLong(Long.MaxValue / 2L + 1L)
        val bit    = random.nextLong(2L)
        high * 2L + bit
      else ThreadLocalRandom.current().nextLong(boundInclusive + 1L)

  /** Always returns the bound, so the computed backoff is used unchanged.
    *
    * This is the deterministic source unit tests run against. It is also what a caller effectively gets by choosing
    * [[com.worxbend.codeberg4s.retry.Jitter.None]], since the engine then never consults the source at all.
    */
  val Deterministic: JitterSource = new JitterSource:

    override def upTo(boundInclusive: Long): Long =
      if boundInclusive <= 0L then 0L else boundInclusive

  /** The source used unless the caller supplies another one. */
  given jitterSource: JitterSource = Random
