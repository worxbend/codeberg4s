package com.worxbend.codeberg4s.client

import com.worxbend.codeberg4s.core.Timer
import com.worxbend.codeberg4s.syntax.discard

import scala.collection.mutable
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{
  CancellationException,
  RejectedExecutionException,
  ScheduledThreadPoolExecutor,
  ThreadFactory,
  TimeUnit
}

/** The [[com.worxbend.codeberg4s.core.Timer]] the published client runs on.
  *
  * '''Nothing blocks.''' Retry backoff must not cost a thread: a client waiting eight seconds before its third attempt
  * would otherwise park a worker for eight seconds, and a caller issuing a few dozen concurrent requests against a
  * rate-limited instance would exhaust their pool waiting. [[sleep]] therefore registers a
  * [[scala.concurrent.Promise]], schedules its completion and returns immediately.
  *
  * '''One daemon thread.''' The scheduler is a single-threaded executor whose thread is a daemon, so a JVM can exit
  * even if an application forgets to [[close]] its client. Forgetting is still a leak — the thread lives as long as the
  * process — which is why [[com.worxbend.codeberg4s.CodebergClient.close]] releases it.
  *
  * '''Every sleep ends.''' Pending promises belong to the timer independently of the scheduler's queue. A sleep
  * succeeds when its completion wins, fails with the scheduling error if admission fails, or fails when [[close]]
  * claims it first. Dequeueing or internally cancelling a scheduled task cannot hide its promise from close.
  *
  * '''Ownership.''' Whoever calls [[FutureTimer.apply]] owns the result and must [[close]] it. A
  * [[com.worxbend.codeberg4s.CodebergClient]] creates its own and closes it, so an application that only uses the
  * client never touches this type.
  */
final class FutureTimer private (scheduler: ScheduledThreadPoolExecutor) extends Timer[Future]:

  // This short monitor coordinates ownership only. Neither scheduler admission/shutdown nor promise publication
  // runs inside it: a scheduler may hold admission, and a parasitic callback may reenter from another thread.
  private val pending = mutable.HashSet.empty[Promise[Unit]]
  private val closed  = AtomicBoolean(false)

  /** The wall clock, read when this method is called rather than when the returned effect completes.
    *
    * That is exactly what the request pipeline wants: it reads the clock on both sides of a send, inside the effect
    * chain, so the difference is the attempt's duration and not the time some continuation happened to be scheduled.
    */
  override def nowMillis: Future[Long] = Future.successful(System.currentTimeMillis())

  /** Completes after `duration` without occupying a thread, preserving positive delays in nanoseconds.
    *
    * A non-positive duration completes immediately, without going near the scheduler, even after [[close]]. New
    * positive sleeps after close fail with [[java.util.concurrent.RejectedExecutionException]] rather than throwing. A
    * registered sleep belongs to the timer before scheduler admission starts, so close can settle it even while
    * admission is in progress. Scheduling failures are delivered unchanged unless close already claimed the sleep.
    */
  override def sleep(duration: FiniteDuration): Future[Unit] =
    if duration <= Duration.Zero then Future.unit
    else
      val promise    = Promise[Unit]()
      val registered = pending.synchronized:
        if closed.get() then false
        else pending.add(promise)
      if !registered then Future.failed(RejectedExecutionException("codeberg4s timer is closed"))
      else
        val completing: Runnable = () => finish(promise, Success(()))
        Try(scheduler.schedule(completing, duration.toNanos, TimeUnit.NANOSECONDS)) match
          // Close may have drained the scheduler before a held admission returned its handle. Clean up that late
          // handle too; the promise was already registered and close owns its outcome independently of this task.
          case Success(task)   => if closed.get() then task.cancel(false).discard
          case Failure(reason) => finish(promise, Failure(reason))
        promise.future

  /** Releases the scheduler thread and fails every sleep whose outstanding ownership close wins. Idempotent, and safe
    * to call from any thread.
    *
    * Close first prevents new positive sleeps and detaches all pending promises, including admitted tasks no longer in
    * the delayed queue and sleeps still entering the scheduler. It then shuts the scheduler down and publishes
    * [[java.util.concurrent.CancellationException]] to those promises outside the bookkeeping monitor. An already
    * selected success or scheduling failure is retained. No callback runs under that monitor.
    *
    * Cancellation here ends a timer wait, not an underlying HTTP exchange. It reports a lifecycle defect rather than a
    * [[com.worxbend.codeberg4s.CodebergError]] worth retrying.
    */
  def close(): Unit =
    val waiting = pending.synchronized:
      closed.set(true)
      val snapshot = pending.toList
      pending.clear()
      snapshot
    try scheduler.shutdownNow().discard
    finally
      waiting.foreach: promise =>
        promise.tryFailure(CancellationException("codeberg4s timer closed while a retry was still waiting")).discard

  /** Claim an outcome under the monitor, then publish it without holding the monitor. Close uses the same ownership
    * boundary, so late completion or rejection cannot change a sleep it already claimed.
    */
  private def finish(promise: Promise[Unit], outcome: Try[Unit]): Unit =
    val owned = pending.synchronized(pending.remove(promise))
    if owned then promise.tryComplete(outcome).discard

object FutureTimer:

  /** The name of the scheduler thread, so a thread dump names this library rather than `pool-3-thread-1`. */
  val ThreadName: String = "codeberg4s-timer"

  /** Creates a timer with its own daemon scheduler thread. The caller owns the result and must close it. Promise
    * ownership is kept by the timer, not by executor queue wrappers or the list returned from `shutdownNow()`.
    */
  def apply(): FutureTimer = new FutureTimer(ScheduledThreadPoolExecutor(1, DaemonThreads))

  private val DaemonThreads: ThreadFactory = (runnable: Runnable) =>
    val thread = Thread(runnable, ThreadName)
    thread.setDaemon(true)
    thread
