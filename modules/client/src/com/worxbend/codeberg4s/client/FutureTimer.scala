package com.worxbend.codeberg4s.client

import com.worxbend.codeberg4s.core.Timer
import com.worxbend.codeberg4s.syntax.discard

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/** The [[com.worxbend.codeberg4s.core.Timer]] the published client runs on.
  *
  * '''Nothing blocks.''' Retry backoff must not cost a thread: a client waiting eight seconds before its third attempt
  * would otherwise park a worker for eight seconds, and a caller issuing a few dozen concurrent requests against a
  * rate-limited instance would exhaust their pool waiting. [[sleep]] therefore hands a [[scala.concurrent.Promise]] to
  * a scheduler and returns immediately; the only thread involved is the scheduler's own, and it only runs the
  * completion.
  *
  * '''One daemon thread.''' The scheduler is a single-threaded executor whose thread is a daemon, so a JVM can exit
  * even if an application forgets to [[close]] its client. Forgetting is still a leak — the thread lives as long as the
  * process — which is why [[com.worxbend.codeberg4s.CodebergClient.close]] releases it.
  *
  * '''Ownership.''' Whoever calls [[FutureTimer.apply]] owns the result and must [[close]] it. A
  * [[com.worxbend.codeberg4s.CodebergClient]] creates its own and closes it, so an application that only uses the
  * client never touches this type.
  */
final class FutureTimer private (scheduler: ScheduledExecutorService) extends Timer[Future]:

  /** The wall clock, read when this method is called rather than when the returned effect completes.
    *
    * That is exactly what the request pipeline wants: it reads the clock on both sides of a send, inside the effect
    * chain, so the difference is the attempt's duration and not the time some continuation happened to be scheduled.
    */
  override def nowMillis: Future[Long] = Future.successful(System.currentTimeMillis())

  /** Completes after `duration` without occupying a thread.
    *
    * A non-positive duration completes immediately, without going near the scheduler. After [[close]] the scheduler
    * rejects new work; that is reported as a failed `Future` rather than as a thrown exception, because using a closed
    * client is a defect and a defect must not be laundered into a [[com.worxbend.codeberg4s.CodebergError]] the caller
    * would then retry.
    */
  override def sleep(duration: FiniteDuration): Future[Unit] =
    if duration <= Duration.Zero then Future.unit
    else
      val promise = Promise[Unit]()
      Try(scheduler.schedule(FutureTimer.completing(promise), duration.toMillis, TimeUnit.MILLISECONDS)) match
        case Success(_)      => promise.future
        case Failure(reason) => Future.failed(reason)

  /** Releases the scheduler thread. Idempotent, and safe to call from any thread.
    *
    * Work already scheduled is abandoned, so a `Future` returned by an in-flight [[sleep]] never completes. That is the
    * intended reading of "the client is closed": a retry that was waiting is not resumed.
    */
  def close(): Unit = scheduler.shutdownNow().discard

object FutureTimer:

  /** The name of the scheduler thread, so a thread dump names this library rather than `pool-3-thread-1`. */
  val ThreadName: String = "codeberg4s-timer"

  /** Creates a timer with its own daemon scheduler thread. The caller owns the result and must close it. */
  def apply(): FutureTimer = new FutureTimer(Executors.newSingleThreadScheduledExecutor(DaemonThreads))

  private val DaemonThreads: ThreadFactory = (runnable: Runnable) =>
    val thread = Thread(runnable, ThreadName)
    thread.setDaemon(true)
    thread

  private def completing(promise: Promise[Unit]): Runnable =
    () => promise.success(()).discard
