package com.worxbend.codeberg4s.client

import com.worxbend.codeberg4s.syntax.discard

import munit.FunSuite

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    val effect = timer.sleep(FutureTimerSuite.UnreachableDelay)

    // Read before closing: the claim is about the moment `sleep` returned. With an hour-long delay the completion
    // cannot have been served in between, whatever the scheduler thread or a busy CI runner was doing — which the
    // 200 ms this used to wait for could not promise. Closing then cancels the wait instead of serving it, so the
    // test ends now rather than in an hour, and the cancellation is the outcome awaited below.
    val completedOnReturn = effect.isCompleted
    timer.close()

    assert(!completedOnReturn, "sleep blocked the calling thread instead of scheduling the completion")

    effect.failed.map:
      case _: CancellationException => ()
      case other                    => fail(s"expected the closed timer to cancel the pending sleep, got $other")

  test("the scheduler runs on a named daemon thread, so a forgotten client cannot hold the JVM open"):
    val timer = FutureTimer()

    // A `ScheduledThreadPoolExecutor` creates its core thread synchronously on the first `schedule` call
    // (`delayedExecute` → `ensurePrestart`), so the thread exists the moment `sleep` returns and can be found among the
    // live threads without racing the sleep itself. Mapping the sleep's completion onto `Thread.currentThread()` was
    // racy: a short sleep that completed before the callback was attached ran that callback on the caller's thread.
    timer.sleep(1.hour).discard

    val schedulerThread = Thread.getAllStackTraces.keySet.asScala.find(_.getName == FutureTimer.ThreadName)

    timer.close()

    schedulerThread match
      case Some(thread) => assert(thread.isDaemon, s"${thread.getName} is not a daemon thread")
      case None         => fail(s"no live thread named ${FutureTimer.ThreadName} after a sleep was scheduled")

  test("close is idempotent, and a closed timer stays closed"):
    val timer = FutureTimer()

    timer.close()
    timer.close()

    // Synchronous on purpose, and genuinely so: a shut-down `ScheduledThreadPoolExecutor` rejects `schedule` by
    // throwing `RejectedExecutionException` before anything is queued, and `sleep` turns that into `Future.failed`,
    // which is complete the moment it is constructed. Nothing here depends on another thread running first.
    assert(timer.sleep(FutureTimerSuite.PositiveDelay).isCompleted, "a closed timer must reject new work at once")

  test("sleep after close fails the future instead of throwing"):
    val timer = FutureTimer()
    timer.close()

    timer.sleep(FutureTimerSuite.PositiveDelay).failed.map:
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

  test("positive sleeps preserve nanosecond schedule arguments without millisecond truncation"):
    val scheduler = FutureTimerTestScheduler()
    val timer     = FutureTimerTestScheduler.timer(scheduler)
    val durations = List(
      FiniteDuration(1L, TimeUnit.NANOSECONDS),
      FiniteDuration(999999L, TimeUnit.NANOSECONDS),
      FiniteDuration(1500001L, TimeUnit.NANOSECONDS),
      FutureTimerSuite.UnreachableDelay,
      FiniteDuration(Long.MaxValue, TimeUnit.NANOSECONDS),
    )
    try
      durations.foreach(duration => timer.sleep(duration).discard)
      val actual   = scheduler.scheduledArguments
      val expected = durations.map(duration => (duration.toNanos, TimeUnit.NANOSECONDS))
      assertEquals(actual, expected, "positive delays must reach the scheduler with their full nanosecond precision")
    finally
      try timer.close()
      finally scheduler.dispose()

  test("zero and negative sleeps bypass the scheduler even after close"):
    val scheduler = FutureTimerTestScheduler()
    val timer     = FutureTimerTestScheduler.timer(scheduler)
    try
      timer.close()
      val zero     = timer.sleep(Duration.Zero)
      val negative = timer.sleep(FiniteDuration(-1L, TimeUnit.NANOSECONDS))
      assertEquals(zero.value, Some(Success(())))
      assertEquals(negative.value, Some(Success(())))
      assertEquals(scheduler.scheduledArguments, Nil)
      assertRejected(timer.sleep(FutureTimerSuite.PositiveDelay))
    finally
      try timer.close()
      finally scheduler.dispose()

  test("close settles an accepted sleep internally cancelled before schedule returns and absent from the drain"):
    val scheduler = FutureTimerTestScheduler()
    val timer     = FutureTimerTestScheduler.timer(scheduler)
    scheduler.onAcceptance: task =>
      scheduler.dequeue(task)
      // Closing reentrantly at this acceptance boundary also permits a correct implementation to serialize schedule
      // with close. No FutureTimer-specific task/decorator hook participates in the delegate's cancellation.
      timer.close()
      task.cancel(false).discard
    try
      val waiting = timer.sleep(FutureTimerSuite.UnreachableDelay)
      assertEquals(scheduler.shutdownDrainSize, Some(0))
      assert(scheduler.onlyTask.isCancelled, "the scheduler's accepted delegate must have been cancelled")
      assertEquals(scheduler.originExecutions, 0)
      assertCancelled(waiting, "an accepted internally cancelled sleep was lost before schedule returned")
    finally
      try timer.close()
      finally scheduler.dispose()

  test("close settles a dequeued sleep when the JDK delegate self-cancels without running its origin"):
    val scheduler = FutureTimerTestScheduler()
    val timer     = FutureTimerTestScheduler.timer(scheduler)
    try
      val waiting  = timer.sleep(FutureTimerSuite.UnreachableDelay)
      val dequeued = scheduler.onlyTask
      scheduler.dequeue(dequeued)
      timer.close()
      // This is the actual ScheduledFutureTask.run after STOP. Its internal cancel path does not call a queue
      // decorator's cancel method, and shutdownNow cannot return an entry that a worker has already taken.
      dequeued.run()
      assertEquals(scheduler.shutdownDrainSize, Some(0))
      assert(dequeued.isCancelled, "the dequeued JDK delegate must self-cancel after shutdown")
      assertEquals(scheduler.originExecutions, 0, "the origin must not run in the internal-cancellation path")
      assertCancelled(waiting, "a dequeued internally cancelled sleep was absent from the shutdown drain")
    finally
      try timer.close()
      finally scheduler.dispose()

  test("successful completion before close is retained and delivered exactly once"):
    val scheduler   = FutureTimerTestScheduler()
    val timer       = FutureTimerTestScheduler.timer(scheduler)
    val completions = AtomicInteger(0)
    try
      val waiting  = timer.sleep(FutureTimerSuite.UnreachableDelay)
      waiting.onComplete(_ => completions.incrementAndGet().discard)(using ExecutionContext.parasitic)
      val dequeued = scheduler.onlyTask
      scheduler.dequeue(dequeued)
      dequeued.run()
      timer.close()
      timer.close()
      dequeued.run()
      assertEquals(waiting.value, Some(Success(())))
      assertEquals(completions.get(), 1)
      assertEquals(scheduler.originExecutions, 1)
      assertRejected(timer.sleep(FutureTimerSuite.PositiveDelay))
    finally
      try timer.close()
      finally scheduler.dispose()

  test("close racing a held accepted admission settles the sleep regardless of which operation wins"):
    val scheduler        = FutureTimerTestScheduler()
    val timer            = FutureTimerTestScheduler.timer(scheduler)
    val workers          = Executors.newFixedThreadPool(2)
    val accepted         = CountDownLatch(1)
    val releaseAdmission = CountDownLatch(1)
    val closing          = CountDownLatch(1)
    scheduler.onAcceptance: task =>
      scheduler.dequeue(task)
      accepted.countDown()
      FutureTimerSuite.awaitGate(releaseAdmission, "release accepted admission")
      if scheduler.executor.isShutdown then task.cancel(false).discard
    try
      val admission = workers.submit[Future[Unit]](new Callable[Future[Unit]]:
        override def call(): Future[Unit] = timer.sleep(FutureTimerSuite.UnreachableDelay))
      FutureTimerSuite.awaitGate(accepted, "scheduler acceptance")
      val closure   = workers.submit[Unit](new Callable[Unit]:
        override def call(): Unit =
          closing.countDown()
          timer.close())
      FutureTimerSuite.awaitGate(closing, "concurrent close entry")
      // Close must not need scheduler admission to return: the sleep's promise is already independently owned.
      closure.get(5L, TimeUnit.SECONDS)
      releaseAdmission.countDown()
      val waiting   = admission.get(5L, TimeUnit.SECONDS)
      scheduler.onlyTask.run()
      assertEquals(scheduler.shutdownDrainSize, Some(0))
      assert(scheduler.onlyTask.isCancelled)
      assertEquals(scheduler.originExecutions, 0)
      assertCancelled(waiting, "concurrent accepted admission escaped coordinated close")
      assertRejected(timer.sleep(FutureTimerSuite.PositiveDelay))
    finally
      releaseAdmission.countDown()
      try FutureTimerSuite.stopWorkers(workers)
      finally
        try timer.close()
        finally scheduler.dispose()

  test("concurrent completion close and admission all settle once without changing an established outcome"):
    val workers = Executors.newFixedThreadPool(3)
    try
      (1 to 32).foreach: round =>
        val scheduler   = FutureTimerTestScheduler()
        val timer       = FutureTimerTestScheduler.timer(scheduler)
        val ready       = CountDownLatch(3)
        val start       = CountDownLatch(1)
        val completions = AtomicInteger(0)
        try
          val waiting             = timer.sleep(FutureTimerSuite.UnreachableDelay)
          waiting.onComplete(_ => completions.incrementAndGet().discard)(using ExecutionContext.parasitic)
          val dequeued            = scheduler.onlyTask
          scheduler.dequeue(dequeued)
          def enterRace(): Unit   =
            ready.countDown()
            FutureTimerSuite.awaitGate(start, "race start")
          val completion          = workers.submit[Unit](new Callable[Unit]:
            override def call(): Unit =
              enterRace()
              dequeued.run())
          val closure             = workers.submit[Unit](new Callable[Unit]:
            override def call(): Unit =
              enterRace()
              timer.close()
              timer.close())
          val admission           = workers.submit[Future[Unit]](new Callable[Future[Unit]]:
            override def call(): Future[Unit] =
              enterRace()
              timer.sleep(FutureTimerSuite.UnreachableDelay))
          FutureTimerSuite.awaitGate(ready, "all race participants")
          start.countDown()
          completion.get(5L, TimeUnit.SECONDS)
          closure.get(5L, TimeUnit.SECONDS)
          val admitted            = admission.get(5L, TimeUnit.SECONDS)
          val originalOutcome     = Try(Await.result(waiting, FutureTimerSuite.CompletionBound))
          originalOutcome match
            case Success(())                       => ()
            case Failure(_: CancellationException) => ()
            case other => fail(s"round $round: dequeued completion/close race did not settle: $other")
          val admittedCompletions = AtomicInteger(0)
          admitted.onComplete(_ => admittedCompletions.incrementAndGet().discard)(using ExecutionContext.parasitic)
          val admittedOutcome     = Try(Await.result(admitted, FutureTimerSuite.CompletionBound))
          admittedOutcome match
            case Failure(_: CancellationException)      => ()
            case Failure(_: RejectedExecutionException) => ()
            case other => fail(s"round $round: concurrent admission did not cancel or reject: $other")
          val settledOriginal     = waiting.value
          val settledAdmission    = admitted.value
          timer.close()
          dequeued.run()
          assertEquals(waiting.value, settledOriginal, s"round $round: close changed a settled outcome")
          assertEquals(admitted.value, settledAdmission, s"round $round: close changed an admitted outcome")
          assertEquals(completions.get(), 1, s"round $round: original callback count")
          assertEquals(admittedCompletions.get(), 1, s"round $round: admission callback count")
          assertRejected(timer.sleep(FutureTimerSuite.PositiveDelay))
        finally
          start.countDown()
          try timer.close()
          finally scheduler.dispose()
    finally FutureTimerSuite.stopWorkers(workers)

  test("successful completion callbacks allow admission from another thread before returning"):
    val scheduler   = FutureTimerTestScheduler()
    val timer       = FutureTimerTestScheduler.timer(scheduler)
    val workers     = Executors.newFixedThreadPool(2)
    val readmission = Promise[Future[Unit]]()
    try
      val waiting         = timer.sleep(FutureTimerSuite.UnreachableDelay)
      waiting.onComplete(_ =>
        val outcome = Try:
          workers.submit[Future[Unit]](new Callable[Future[Unit]]:
            override def call(): Future[Unit] = timer.sleep(FutureTimerSuite.UnreachableDelay)).get(2L, TimeUnit.SECONDS)
        readmission.tryComplete(outcome).discard
      )(using ExecutionContext.parasitic)
      val dequeued        = scheduler.onlyTask
      scheduler.dequeue(dequeued)
      val completion      = workers.submit[Unit](new Callable[Unit]:
        override def call(): Unit = dequeued.run())
      completion.get(5L, TimeUnit.SECONDS)
      val callbackOutcome = Try(Await.result(readmission.future, FutureTimerSuite.CompletionBound))
      assert(
        callbackOutcome.isSuccess,
        s"completion callback could not cross-thread reenter admission: $callbackOutcome"
      )
      assertEquals(waiting.value, Some(Success(())))
      timer.close()
      callbackOutcome.foreach(effect => assertCancelled(effect, "callback's newly admitted sleep escaped close"))
    finally
      try FutureTimerSuite.stopWorkers(workers)
      finally
        try timer.close()
        finally scheduler.dispose()

  test("close callbacks allow rejected admission from another thread before returning"):
    val scheduler   = FutureTimerTestScheduler()
    val timer       = FutureTimerTestScheduler.timer(scheduler)
    val workers     = Executors.newFixedThreadPool(2)
    val readmission = Promise[Future[Unit]]()
    try
      val waiting         = timer.sleep(FutureTimerSuite.UnreachableDelay)
      val dequeued        = scheduler.onlyTask
      scheduler.dequeue(dequeued)
      waiting.onComplete(_ =>
        val outcome = Try:
          workers.submit[Future[Unit]](new Callable[Future[Unit]]:
            override def call(): Future[Unit] = timer.sleep(FutureTimerSuite.PositiveDelay)).get(2L, TimeUnit.SECONDS)
        readmission.tryComplete(outcome).discard
      )(using ExecutionContext.parasitic)
      val closure         = workers.submit[Unit](new Callable[Unit]:
        override def call(): Unit = timer.close())
      closure.get(5L, TimeUnit.SECONDS)
      val callbackOutcome = Try(Await.result(readmission.future, FutureTimerSuite.CompletionBound))
      assert(callbackOutcome.isSuccess, s"close callback could not cross-thread reenter admission: $callbackOutcome")
      callbackOutcome.foreach(assertRejected)
      assertCancelled(waiting, "close callback's original sleep never settled")
    finally
      try FutureTimerSuite.stopWorkers(workers)
      finally
        try timer.close()
        finally scheduler.dispose()

  test("close settles a registered sleep while scheduler admission is held before acceptance"):
    val scheduler        = FutureTimerTestScheduler()
    val timer            = FutureTimerTestScheduler.timer(scheduler)
    val workers          = Executors.newFixedThreadPool(2)
    val entering         = CountDownLatch(1)
    val releaseAdmission = CountDownLatch(1)
    scheduler.beforeAdmission: () =>
      entering.countDown()
      FutureTimerSuite.awaitGate(releaseAdmission, "release admission before acceptance")
    try
      val admission = workers.submit[Future[Unit]](new Callable[Future[Unit]]:
        override def call(): Future[Unit] = timer.sleep(FutureTimerSuite.UnreachableDelay))
      FutureTimerSuite.awaitGate(entering, "scheduler entry before acceptance")
      val closure   = workers.submit[Unit](new Callable[Unit]:
        override def call(): Unit = timer.close())
      closure.get(5L, TimeUnit.SECONDS)
      releaseAdmission.countDown()
      val waiting   = admission.get(5L, TimeUnit.SECONDS)
      assertEquals(scheduler.shutdownDrainSize, Some(0))
      assertEquals(scheduler.originExecutions, 0)
      assertCancelled(waiting, "registered sleep was lost when close preceded scheduler acceptance")
      assertRejected(timer.sleep(FutureTimerSuite.PositiveDelay))
    finally
      releaseAdmission.countDown()
      try FutureTimerSuite.stopWorkers(workers)
      finally
        try timer.close()
        finally scheduler.dispose()

  test("scheduler rejection remains the original failed Future after repeated close"):
    val scheduler = FutureTimerTestScheduler()
    val timer     = FutureTimerTestScheduler.timer(scheduler)
    val rejection = RejectedExecutionException("controlled admission rejection")
    scheduler.beforeAdmission(() => throw rejection)
    try
      val waiting = Try(timer.sleep(FutureTimerSuite.PositiveDelay)) match
        case Success(effect) => effect
        case Failure(reason) => fail(s"scheduler rejection escaped synchronously: $reason")
      val outcome = Try(Await.result(waiting, FutureTimerSuite.CompletionBound))
      outcome match
        case Failure(reason) => assert(reason eq rejection, s"scheduler failure was replaced or wrapped: $reason")
        case other           => fail(s"expected the scheduler rejection, got $other")
      timer.close()
      timer.close()
      assertEquals(waiting.value, Some(Failure(rejection)))
    finally
      try timer.close()
      finally scheduler.dispose()

  private def assertCancelled(effect: Future[Unit], clue: String): Unit =
    val outcome = Try(Await.result(effect, FutureTimerSuite.CompletionBound))
    outcome match
      case Failure(_: CancellationException) => ()
      case other                             => fail(s"$clue: expected CancellationException, got $other")

  private def assertRejected(effect: Future[Unit]): Unit =
    val outcome = Try(Await.result(effect, FutureTimerSuite.CompletionBound))
    outcome match
      case Failure(_: RejectedExecutionException) => ()
      case other => fail(s"expected a rejected failed Future rather than a throw or pending sleep, got $other")

object FutureTimerSuite:

  /** Coordination waits are outside MUnit assertions, including when called by a worker. */
  private def awaitGate(gate: CountDownLatch, clue: String): Unit =
    val opened = gate.await(5L, TimeUnit.SECONDS)
    if !opened then throw java.util.concurrent.TimeoutException(s"timed out waiting for $clue")

  private def stopWorkers(workers: ExecutorService): Unit =
    workers.shutdownNow().discard
    val terminated = workers.awaitTermination(5L, TimeUnit.SECONDS)
    if !terminated then throw IllegalStateException("timer regression workers did not terminate")

  /** Any positive delay: enough to reach the scheduler rather than the non-positive shortcut. Its length never matters,
    * because every test that uses it has already closed the timer, and a closed scheduler rejects the work
    * synchronously.
    */
  private val PositiveDelay: FiniteDuration = 200.millis

  /** Far longer than the test can run, so a sleep given this delay is certainly still waiting when the timer closes —
    * and certainly not complete when `sleep` returns, which is what makes that assertion deterministic rather than a
    * race against the scheduler.
    */
  private val UnreachableDelay: FiniteDuration = 1.hour

  /** How long a closed timer is given to fail its waiting sleeps. Closing does the work inline, so this is slack. */
  private val CompletionBound: FiniteDuration = 5.seconds
