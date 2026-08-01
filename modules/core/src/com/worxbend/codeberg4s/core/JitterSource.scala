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

  /** A pseudo-random value uniformly distributed in `[0, boundInclusive]` milliseconds.
    *
    * A non-positive bound yields `0`. Implementations must be safe to call from several threads.
    */
  def upTo(boundInclusive: Long): Long

object JitterSource:

  /** Draws from [[java.util.concurrent.ThreadLocalRandom]]: no shared state, no lock, no seed to manage. */
  val Random: JitterSource = new JitterSource:

    override def upTo(boundInclusive: Long): Long =
      if boundInclusive <= 0L then 0L else ThreadLocalRandom.current().nextLong(boundInclusive + 1L)

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
