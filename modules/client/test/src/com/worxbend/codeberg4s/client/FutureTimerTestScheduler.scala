package com.worxbend.codeberg4s.client

import com.worxbend.codeberg4s.syntax.discard

import scala.jdk.CollectionConverters.*

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RunnableScheduledFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A real JDK scheduler whose worker cannot consume tasks until cleanup releases it.
  *
  * Tests may remove an actual JDK ScheduledFutureTask from its queue and run it explicitly. In particular, running it
  * after STOP exercises the delegate's own self-cancellation, without calling the origin or a decorator's cancel hook.
  * No assertion, elapsed-time threshold or production injection hook is involved in the coordination.
  */
private[client] final class FutureTimerTestScheduler:

  private val releaseWorker = CountDownLatch(1)
  private val arguments     = ConcurrentLinkedQueue[(Long, TimeUnit)]()
  private val tasks         = ConcurrentLinkedQueue[RunnableScheduledFuture[?]]()
  private val admission     = AtomicReference[() => Unit](() => ())
  private val acceptance    = AtomicReference[RunnableScheduledFuture[?] => Unit](_ => ())
  private val drained       = AtomicReference[Option[Int]](None)
  private val executions    = AtomicInteger(0)

  private val threads: ThreadFactory = (worker: Runnable) =>
    val thread = Thread(
      () =>
        // Always enter Worker.run even on interruption: its finally block owns executor termination accounting.
        try releaseWorker.await()
        finally worker.run(),
      "codeberg4s-timer-test-held-worker",
    )
    thread.setDaemon(true)
    thread

  val executor: ScheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(1, threads):
    override def schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture[?] =
      arguments.add((delay, unit)).discard
      admission.get().apply()
      val observed: Runnable = () =>
        executions.incrementAndGet().discard
        command.run()
      // ScheduledThreadPoolExecutor's Runnable overload returns a RunnableScheduledFuture. Keep the actual JDK task,
      // not a fake future: its run-state test is essential to the dequeued/internal-cancel regression.
      val task               = super.schedule(observed, delay, unit).asInstanceOf[RunnableScheduledFuture[?]]
      tasks.add(task).discard
      acceptance.get().apply(task)
      task

    override def shutdownNow(): java.util.List[Runnable] =
      val abandoned = super.shutdownNow()
      drained.set(Some(abandoned.size()))
      abandoned

  /** Hold or reject admission before the JDK sees it; install before calling sleep. */
  def beforeAdmission(action: () => Unit): Unit = admission.set(action)

  /** Install before calling sleep; deliberately test-only, and never exposed by FutureTimer. */
  def onAcceptance(action: RunnableScheduledFuture[?] => Unit): Unit = acceptance.set(action)

  def scheduledArguments: List[(Long, TimeUnit)] = arguments.iterator().asScala.toList
  def shutdownDrainSize: Option[Int]             = drained.get()
  def originExecutions: Int                      = executions.get()

  def onlyTask: RunnableScheduledFuture[?] =
    if tasks.size() != 1 then throw IllegalStateException(s"expected one scheduled task, got ${tasks.size()}")
    Option(tasks.peek()).getOrElse(throw IllegalStateException("scheduled task disappeared"))

  /** Model ownership having passed from the delayed queue to a worker, before ScheduledFutureTask.run. */
  def dequeue(task: RunnableScheduledFuture[?]): Unit =
    val removed = executor.getQueue.remove(task)
    if !removed then throw IllegalStateException("controlled task was not in the delayed queue")

  /** Close and join even when an assertion or coordination wait failed. */
  def dispose(): Unit =
    executor.shutdownNow().discard
    releaseWorker.countDown()
    val terminated = executor.awaitTermination(5L, TimeUnit.SECONDS)
    if !terminated then throw IllegalStateException("controlled timer scheduler did not terminate")

private[client] object FutureTimerTestScheduler:

  /** Access the existing private constructor only in tests; do not add a production seam to obtain RED evidence. */
  def timer(scheduler: FutureTimerTestScheduler): FutureTimer =
    val constructor = classOf[FutureTimer].getDeclaredConstructor(classOf[ScheduledThreadPoolExecutor])
    constructor.setAccessible(true)
    constructor.newInstance(scheduler.executor)
