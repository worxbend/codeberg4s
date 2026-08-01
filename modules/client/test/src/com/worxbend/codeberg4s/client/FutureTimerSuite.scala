package com.worxbend.codeberg4s.client

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Success

import java.util.concurrent.RejectedExecutionException

import munit.FunSuite

/** The `Future` instance of [[com.worxbend.codeberg4s.core.Timer]].
  *
  * Assertions run on munit's own execution context rather than on the scheduler's thread, because closing a timer from
  * inside one of its own completions interrupts the very thread the assertion is running on.
  */
final class FutureTimerSuite extends FunSuite:

  private given ExecutionContext = munitExecutionContext

  test("a non-positive delay completes immediately, without going near the scheduler"):
    val timer = FutureTimer()

    val effect = timer.sleep(Duration.Zero)
    timer.close()

    assertEquals(effect.value, Some(Success(())))

  test("nowMillis reads the wall clock"):
    val timer  = FutureTimer()
    val before = System.currentTimeMillis()

    val effect = timer.nowMillis
    timer.close()

    effect.value match
      case Some(Success(reading)) => assert(reading >= before, s"$reading is before the call that read it")
      case other                  => fail(s"nowMillis did not complete immediately: $other")

  test("sleep returns before the delay has elapsed, so no caller thread is parked"):
    val timer  = FutureTimer()
    val effect = timer.sleep(FutureTimerSuite.ObservableDelay)

    assert(!effect.isCompleted, "sleep blocked the calling thread instead of scheduling the completion")

    effect.map: _ =>
      timer.close()
      assert(effect.isCompleted)

  test("the scheduler runs on a named daemon thread, so a forgotten client cannot hold the JVM open"):
    val timer = FutureTimer()

    val completingThread = timer.sleep(1.milli).map(_ => Thread.currentThread())(using OnSchedulerThread)

    completingThread.map: thread =>
      timer.close()

      assertEquals(thread.getName, FutureTimer.ThreadName)
      assert(thread.isDaemon, s"${thread.getName} is not a daemon thread")

  test("close is idempotent, and a closed timer stays closed"):
    val timer = FutureTimer()

    timer.close()
    timer.close()

    assert(timer.sleep(FutureTimerSuite.ObservableDelay).isCompleted, "a closed timer must reject new work at once")

  test("sleep after close fails the future instead of throwing"):
    val timer = FutureTimer()
    timer.close()

    timer.sleep(FutureTimerSuite.ObservableDelay).failed.map:
      case _: RejectedExecutionException => ()
      case other                         => fail(s"expected the scheduler to reject the work, got $other")

  /** Runs a continuation on whatever thread completed the promise — for a scheduled sleep, the scheduler's own. */
  private val OnSchedulerThread: ExecutionContext = ExecutionContext.parasitic

object FutureTimerSuite:

  /** Long enough that a scheduled completion cannot plausibly have happened before the next statement runs. */
  private val ObservableDelay: FiniteDuration = 200.millis
