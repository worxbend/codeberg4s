package com.worxbend.codeberg4s.client

import com.worxbend.codeberg4s.syntax.discard

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

import java.util.concurrent.ConcurrentLinkedQueue

/** An execution context that queues every task and runs nothing until it is told to.
  *
  * This is what makes "does `suspend` genuinely defer?" a decidable question. Under any real execution context the
  * answer is a race: `ExecutionContext.global` may or may not have run the thunk by the time the assertion executes,
  * and `ExecutionContext.parasitic` runs it inline, which makes a deferring implementation look eager. Here nothing
  * runs until [[runAll]], so "the thunk has not been called yet" is an observation rather than a guess.
  */
final class QueuedExecutionContext extends ExecutionContext:

  private val pending = ConcurrentLinkedQueue[Runnable]()

  override def execute(runnable: Runnable): Unit = pending.add(runnable).discard

  /** Failures that escape a `Future` are dropped: every suite using this context asserts on the `Future` itself. */
  override def reportFailure(cause: Throwable): Unit = cause.discard

  /** How many tasks are waiting to run. */
  def queued: Int = pending.size()

  /** Runs every queued task, including the tasks those tasks queue in turn. */
  @tailrec
  def runAll(): Unit =
    Option(pending.poll()) match
      case Some(task) =>
        task.run()
        runAll()
      case None       => ()
