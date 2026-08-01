package com.worxbend.codeberg4s.core

import scala.concurrent.duration.FiniteDuration

/** Wall clock and delayed continuation, as ports.
  *
  * Time is a dependency, not an ambient capability: a retry engine that called `Thread.sleep` or `System.nanoTime`
  * directly could only be tested by actually sleeping. Every core component that needs either takes a `Timer`, and unit
  * tests substitute a fake that records the requested delays and advances a simulated clock instead of blocking.
  *
  * Implementations must not block the calling thread when `F` is asynchronous.
  *
  * @tparam F
  *   the effect the client runs in
  */
trait Timer[F[_]]:

  /** The current wall-clock time in milliseconds since the epoch.
    *
    * Used to measure the duration recorded in [[com.worxbend.codeberg4s.CallContext]]. It is a wall clock, so it can
    * jump; never derive a monotonic measurement from two readings without allowing for that.
    */
  def nowMillis: F[Long]

  /** Completes after at least `duration` has elapsed. A non-positive duration completes immediately. */
  def sleep(duration: FiniteDuration): F[Unit]
