package com.worxbend.codeberg4s.client

import com.worxbend.codeberg4s.syntax.discard

import munit.FunSuite

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import java.util.concurrent.CancellationException
import java.util.concurrent.RejectedExecutionException

/** The `Future` instance of [[com.worxbend.codeberg4s.core.Timer]].
  *
  * Assertions run on munit's own execution context rather than on the scheduler's thread, because closing a timer from
  * inside one of its own completions interrupts the very thread the assertion is running on.
  */
final class FutureTimerSuite extends FunSuite:

  private given ExecutionContext = munitExecutionContext

  /** Clears a left-over thread interrupt before each test.
    *
    * Closing a timer interrupts the scheduler's thread, and one test below deliberately continues on that thread in
    * order to inspect it. munit may then start the next test on the same, now-interrupted, thread. An interrupted
    * thread cannot wait: `Await` throws `InterruptedException` at once instead of honouring its timeout. Clearing the
    * flag here keeps that leak from turning into a flake in whichever test happens to run next.
    */
  override def beforeEach(context: BeforeEach): Unit = Thread.interrupted().discard

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

  test("closing the timer fails every sleep that was still waiting, rather than leaving it without an outcome"):
    val timer   = FutureTimer()
    val waiting = List.fill(2)(timer.sleep(FutureTimerSuite.UnreachableDelay))

    timer.close()

    // A bounded `Await`, deliberately, rather than returning a mapped Future. The bug this guards against left the
    // promise with no outcome at all, and a continuation on a Future that never completes never runs — so a regression
    // would hang the suite instead of failing it.
    waiting.foreach: effect =>
      Try(Await.result(effect, FutureTimerSuite.CompletionBound)) match
        case Failure(_: CancellationException) => ()
        case outcome                           => fail(s"closing the timer left a waiting sleep at $outcome")

  /** Runs a continuation on whatever thread completed the promise — for a scheduled sleep, the scheduler's own. */
  private val OnSchedulerThread: ExecutionContext = ExecutionContext.parasitic

object FutureTimerSuite:

  /** Long enough that a scheduled completion cannot plausibly have happened before the next statement runs. */
  private val ObservableDelay: FiniteDuration = 200.millis

  /** Far longer than the test can run, so a sleep given this delay is certainly still waiting when the timer closes. */
  private val UnreachableDelay: FiniteDuration = 1.hour

  /** How long a closed timer is given to fail its waiting sleeps. Closing does the work inline, so this is slack. */
  private val CompletionBound: FiniteDuration = 5.seconds
