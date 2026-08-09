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

import java.util.concurrent.CancellationException
import java.util.concurrent.Delayed
import java.util.concurrent.RunnableScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
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
  * '''Every sleep ends.''' A `Future` handed out by [[sleep]] always reaches an outcome: it succeeds when the delay
  * elapses, and it fails when [[close]] is called first. Nothing this type returns is left without one, so a caller can
  * always await it.
  *
  * '''Ownership.''' Whoever calls [[FutureTimer.apply]] owns the result and must [[close]] it. A
  * [[com.worxbend.codeberg4s.CodebergClient]] creates its own and closes it, so an application that only uses the
  * client never touches this type.
  */
final class FutureTimer private (scheduler: ScheduledThreadPoolExecutor) extends Timer[Future]:

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
    *
    * A sleep that is already waiting when [[close]] arrives is failed rather than dropped — see there.
    */
  override def sleep(duration: FiniteDuration): Future[Unit] =
    if duration <= Duration.Zero then Future.unit
    else
      val completing = FutureTimer.Completing(Promise[Unit]())
      Try(scheduler.schedule(completing, duration.toMillis, TimeUnit.MILLISECONDS)) match
        case Success(_)      => completing.future
        case Failure(reason) => Future.failed(reason)

  /** Releases the scheduler thread and fails every sleep that was still waiting. Idempotent, and safe to call from any
    * thread.
    *
    * A sleep whose delay has not elapsed yet is abandoned: its `Future` is completed with a
    * [[java.util.concurrent.CancellationException]]. Completing it is the whole point. A `Future` that is neither
    * fulfilled nor failed has no outcome at all, so an application that closed its client while a call sat in retry
    * backoff would wait on that `Future` for as long as the process lived. Failing it means "the client was closed
    * under you", which a caller can see, log and shut down on.
    *
    * The failure is a `CancellationException` rather than a [[com.worxbend.codeberg4s.CodebergError]] for the same
    * reason [[sleep]] reports a closed scheduler that way: closing a client that is still in use is a defect in the
    * calling program, not a remote failure worth retrying.
    */
  def close(): Unit = scheduler.shutdownNow().forEach(task => FutureTimer.abandon(task))

object FutureTimer:

  /** The name of the scheduler thread, so a thread dump names this library rather than `pool-3-thread-1`. */
  val ThreadName: String = "codeberg4s-timer"

  /** Creates a timer with its own daemon scheduler thread. The caller owns the result and must close it. */
  def apply(): FutureTimer = new FutureTimer(QueueKeepingPromises(DaemonThreads))

  private val DaemonThreads: ThreadFactory = (runnable: Runnable) =>
    val thread = Thread(runnable, ThreadName)
    thread.setDaemon(true)
    thread

  /** Fails the promise behind an abandoned queue entry, ignoring anything else the queue happened to hold. */
  private def abandon(task: Runnable): Unit = task match
    case waiting: Waiting[?] => waiting.origin.abandon()
    case _                   => ()

  /** The scheduler, subclassed so that its queue remembers which promise each waiting entry would have completed.
    *
    * A `ScheduledThreadPoolExecutor` does not put the `Runnable` it was handed onto its queue; it puts an internal task
    * object that wraps it, and that wrapper — not the original — is what `shutdownNow()` gives back. Walking the
    * returned list would therefore find nothing recognisable. `decorateTask` is the supported hook for choosing what
    * goes onto the queue, so overriding it is how [[FutureTimer.close]] gets to see the waiting promises at all.
    *
    * `Executors.newSingleThreadScheduledExecutor` builds exactly this executor with a core pool size of one; the only
    * thing given up by constructing it directly is the wrapper that stops callers reconfiguring it, and the instance
    * never leaves [[FutureTimer]].
    */
  private final class QueueKeepingPromises(threads: ThreadFactory) extends ScheduledThreadPoolExecutor(1, threads):

    override protected def decorateTask[V](
        runnable: Runnable,
        task: RunnableScheduledFuture[V],
    ): RunnableScheduledFuture[V] =
      runnable match
        case completing: Completing => Waiting(completing, task)
        case _                      => task

  /** The scheduled work itself: complete the promise a [[FutureTimer.sleep]] handed out.
    *
    * It is a named class rather than a lambda so that [[QueueKeepingPromises.decorateTask]] can recognise it and carry
    * its promise onto the queue.
    */
  private final class Completing(promise: Promise[Unit]) extends Runnable:

    /** The effect [[FutureTimer.sleep]] returns; it ends in exactly one of [[run]] or [[abandon]]. */
    def future: Future[Unit] = promise.future

    override def run(): Unit = promise.trySuccess(()).discard

    /** Ends the sleep as a failure because the timer was closed before the delay elapsed. */
    def abandon(): Unit =
      promise.tryFailure(CancellationException("codeberg4s timer closed while a retry was still waiting")).discard

  /** A queue entry that behaves exactly like the scheduler's own but also names the [[Completing]] it will run.
    *
    * Every method delegates; the single added member is [[origin]], which is what makes the list returned by
    * `shutdownNow()` worth walking.
    */
  private final class Waiting[V](val origin: Completing, delegate: RunnableScheduledFuture[V])
      extends RunnableScheduledFuture[V]:
    override def run(): Unit                            = delegate.run()
    override def isPeriodic: Boolean                    = delegate.isPeriodic
    override def getDelay(unit: TimeUnit): Long         = delegate.getDelay(unit)
    override def compareTo(other: Delayed): Int         = delegate.compareTo(other)
    override def cancel(mayInterrupt: Boolean): Boolean = delegate.cancel(mayInterrupt)
    override def isCancelled: Boolean                   = delegate.isCancelled
    override def isDone: Boolean                        = delegate.isDone
    override def get(): V                               = delegate.get()
    override def get(timeout: Long, unit: TimeUnit): V  = delegate.get(timeout, unit)
