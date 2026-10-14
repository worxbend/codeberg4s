package com.worxbend.codeberg4s.client

import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.core.Telemetry
import com.worxbend.codeberg4s.syntax.discard
import com.worxbend.codeberg4s.{
  ApiErrorBody,
  BaseUri,
  CallContext,
  CodebergClient,
  CodebergConfig,
  CodebergError,
  CodebergException,
  HttpMethod
}

import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.model.StatusCode

import munit.FunSuite

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

import java.io.IOException
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{Callable, ExecutionException, Executors, ThreadFactory, TimeUnit}

/** Failed callback effects, not actual VM exhaustion; every potentially recursive classifier runs on owned daemon
  * workers.
  */
final class GuardedTelemetrySuite extends FunSuite:

  private val context  = CallContext("version.get", HttpMethod.Get, "https://forge.example/api/v1/version", None, 0L)
  private val apiError = CodebergError.Api(context, 403, ApiErrorBody.Empty, None)
  private val instance = BaseUri.from("https://forge.example/api/v1") match
    case Right(value) => value
    case Left(error)  => fail(s"invalid fixture: $error")

  private final class CauseNode(reads: AtomicInteger, initial: Throwable)
      extends ExecutionException("synthetic telemetry wrapper"):
    private val next                     = AtomicReference[Throwable](initial)
    def pointsTo(cause: Throwable): Unit = next.set(cause)

    override def getCause: Throwable =
      reads.incrementAndGet().discard
      next.get()

  private final class PlainCauseNode(reads: AtomicInteger, cause: Throwable)
      extends IOException("synthetic non-ExecutionException wrapper", cause):

    override def getCause: Throwable =
      reads.incrementAndGet().discard
      super.getCause

  private final case class CallbackFailure(effect: Future[Unit], original: Throwable, reads: AtomicInteger)

  private def captured(error: Throwable, reads: AtomicInteger): CallbackFailure =
    val effect = Future.failed[Unit](error)
    effect.value match
      case Some(Failure(original)) => CallbackFailure(effect, original, reads)
      case _                       => fail("failed synthetic callback did not contain a failure")

  private def selfCycle(): CallbackFailure =
    val reads = AtomicInteger(0)
    val node  = CauseNode(reads, IOException("unused initial cause"))
    node.pointsTo(node)
    captured(node, reads)

  private def multiCycle(): CallbackFailure =
    val reads  = AtomicInteger(0)
    val first  = CauseNode(reads, IOException("unused initial cause"))
    val second = CauseNode(reads, first)
    first.pointsTo(second)
    captured(first, reads)

  private def deepChain(): CallbackFailure =
    val reads = AtomicInteger(0)
    val root  = (0 until 16384).foldLeft[Throwable](IOException("ordinary terminal cause")): (cause, _) =>
      CauseNode(reads, cause)
    captured(root, reads)

  private def nestedFatal(): CallbackFailure =
    val reads = AtomicInteger(0)
    val fatal = LinkageError("synthetic fatal, no damaged runtime")
    captured(CauseNode(reads, PlainCauseNode(reads, fatal)), reads)

  private def plainFatal(): CallbackFailure =
    val reads = AtomicInteger(0)
    captured(PlainCauseNode(reads, LinkageError("synthetic only")), reads)

  private def libraryFailure(): CallbackFailure =
    val reads = AtomicInteger(0)
    captured(CauseNode(reads, CodebergException(apiError)), reads)

  private def ordinaryFailure(): CallbackFailure =
    val reads = AtomicInteger(0)
    captured(CauseNode(reads, IOException("ordinary failed telemetry")), reads)

  private def directFatal(): CallbackFailure =
    captured(InterruptedException("synthetic callback interruption"), AtomicInteger(0))

  private final case class Scenario(name: String, create: () => CallbackFailure, preserved: Boolean, maxReads: Int)

  private val scenarios = Vector(
    Scenario("self cycle", ()                          => selfCycle(), false, 1),
    Scenario("multi-node cycle", ()                    => multiCycle(), false, 2),
    // This deliberately exceeds any small traversal budget; unknown unseen causes cannot safely be swallowed.
    Scenario("deep chain budget", ()                   => deepChain(), true, 1024),
    Scenario("nested non-ExecutionException fatal", () => nestedFatal(), true, 3),
    Scenario("plain non-ExecutionException fatal", ()  => plainFatal(), true, 2),
    Scenario("ordinary failure", ()                    => ordinaryFailure(), false, 2),
    Scenario("callback library failure", ()            => libraryFailure(), false, 2),
    Scenario("direct interrupt", ()                    => directFatal(), true, 2),
  )

  private def sink(target: String, callback: => Future[Unit], calls: AtomicInteger): Telemetry[Future] =
    new Telemetry[Future]:
      private def deliver(kind: String): Future[Unit]                            =
        if target.equals(kind) then
          calls.incrementAndGet().discard
          callback
        else Future.unit
      override def onRequest(ctx: CallContext): Future[Unit]                     = deliver("request")
      override def onResponse(ctx: CallContext, status: Int): Future[Unit]       = deliver("response")
      override def onError(ctx: CallContext, error: CodebergError): Future[Unit] = deliver("error")

  private def callback(guarded: GuardedTelemetry, kind: String): Future[Unit] = kind match
    case "request"  => guarded.onRequest(context)
    case "response" => guarded.onResponse(context, 200)
    case "error"    => guarded.onError(context, apiError)
    case other      => fail(s"unknown callback fixture: $other")

  /** The waiter has its own worker; classification has another. A fatal transform can strand a Scala Future, so neither
    * the wait nor the buggy continuation is allowed onto munit/global executors.
    */
  private def bounded[A](use: ExecutionContext => Future[A]): Try[A] =
    val factory          = new ThreadFactory:
      override def newThread(task: Runnable): Thread =
        val thread = Thread(task, "codeberg4s-telemetry-regression")
        thread.setDaemon(true)
        thread.setUncaughtExceptionHandler((_, _) => ())
        thread
    val workers          = Executors.newFixedThreadPool(2, factory)
    val executionContext = ExecutionContext.fromExecutorService(workers, _ => ())
    try
      val task = workers.submit(new Callable[Try[A]]:
        override def call(): Try[A] =
          Try(Await.ready(use(executionContext), 2.seconds)).flatMap: completed =>
            completed.value match
              case Some(outcome) => outcome
              case None          => Failure(java.util.concurrent.TimeoutException("callback remained pending")))
      // Blocking waits are evaluated outside munit assertions and outside classifier continuations.
      Try(task.get(5L, TimeUnit.SECONDS)).flatten
    finally
      workers.shutdownNow().discard
      val terminated = workers.awaitTermination(5L, TimeUnit.SECONDS)
      assert(terminated, "owned daemon regression workers did not terminate")

  Vector[() => Throwable](
    () => LinkageError("synthetic suppressed observer cleanup"),
    () => InterruptedException("synthetic suppressed observer cleanup"),
  ).foreach: unsafe =>
    test(s"suppressed ${unsafe().getClass.getSimpleName} cannot be swallowed behind an ordinary callback error"):
      val primary   = IOException("ordinary callback primary")
      primary.addSuppressed(ExecutionException("boxed observer cleanup", unsafe()))
      val failure   = captured(primary, AtomicInteger(0))
      val calls     = AtomicInteger(0)
      val delivered = bounded: executionContext =>
        GuardedTelemetry(sink("request", failure.effect, calls))(using executionContext).onRequest(context)
      checkFailure(delivered, failure.original)
      assertEquals(calls.get(), 1)

  private def checkFailure(outcome: Try[?], original: Throwable): Unit = outcome match
    case Failure(delivered) => assert(delivered.eq(original), "guard changed or hid the original callback failure")
    case Success(_)         => fail("guard swallowed a fatal or budget-exhausted callback failure")

  Vector("request", "response", "error").foreach: kind =>
    scenarios.foreach: scenario =>
      test(s"$kind callback ${scenario.name} has bounded delivery with original-failure policy"):
        val failure   = scenario.create()
        val calls     = AtomicInteger(0)
        val delivered = bounded: executionContext =>
          callback(GuardedTelemetry(sink(kind, failure.effect, calls))(using executionContext), kind)
        assertEquals(calls.get(), 1)
        if scenario.preserved then checkFailure(delivered, failure.original)
        else assertEquals(delivered, Success(()))
        assert(failure.reads.get() <= scenario.maxReads, "cause traversal reread a node or exceeded its small budget")

    test(s"$kind callback direct synchronous fatal is not swallowed"):
      val fatal     = LinkageError("synthetic only; no broken linkage")
      val calls     = AtomicInteger(0)
      val guarded   = GuardedTelemetry(sink(kind, throw fatal, calls))(using ExecutionContext.parasitic)
      val delivered =
        try
          callback(guarded, kind).discard
          None
        catch case caught: LinkageError => Some(caught)
      assert(delivered.exists(_.eq(fatal)), "guard swallowed or replaced the direct fatal throwable")
      assertEquals(calls.get(), 1)

    scenarios.foreach: scenario =>
      test(s"$kind synchronous wrapped callback ${scenario.name} follows the same bounded original-failure policy"):
        val failure   = scenario.create()
        val calls     = AtomicInteger(0)
        val delivered = bounded: executionContext =>
          callback(GuardedTelemetry(sink(kind, throw failure.original, calls))(using executionContext), kind)
        assertEquals(calls.get(), 1)
        if scenario.preserved then checkFailure(delivered, failure.original)
        else assertEquals(delivered, Success(()))
        assert(failure.reads.get() <= scenario.maxReads, "synchronous cause traversal is unbounded")

    test(s"$kind callback ordinary synchronous failure is still swallowed"):
      val calls     = AtomicInteger(0)
      val delivered = bounded: executionContext =>
        callback(
          GuardedTelemetry(sink(kind, throw IOException("ordinary synchronous observer failure"), calls))(
            using executionContext
          ),
          kind
        )
      assertEquals(delivered, Success(()))
      assertEquals(calls.get(), 1)

  Vector[() => Throwable](
    () => OutOfMemoryError("synthetic only; no allocation pressure"),
    () => LinkageError("synthetic only; no broken linkage"),
  ).foreach: fatal =>
    test(s"direct ${fatal().getClass.getSimpleName} failed callback preserves its captured Future failure"):
      val failure   = captured(fatal(), AtomicInteger(0))
      val calls     = AtomicInteger(0)
      val delivered = bounded: executionContext =>
        GuardedTelemetry(sink("request", failure.effect, calls))(using executionContext).onRequest(context)
      checkFailure(delivered, failure.original)
      assertEquals(calls.get(), 1)

  Vector("request", "response", "error").foreach: kind =>
    scenarios.foreach: scenario =>
      Vector(false, true).foreach: typed =>
        Vector(false, true).foreach: synchronous =>
          test(
            s"public ${if typed then "typed" else "exception"} rail invokes $kind classifier for ${scenario.name} (${if synchronous then "sync" else "async"})"
          ):
            val failure     = scenario.create()
            val calls       = AtomicInteger(0)
            val owned       = AtomicReference[Option[CodebergClient]](None)
            val delivered   = try bounded: executionContext =>
                given ExecutionContext = executionContext
                val status             = if kind.equals("error") then 403 else 200
                val body               = if kind.equals("error") then "{}" else """{"version":"12.0.0"}"""
                val backend            = BackendStub.asynchronousFuture.whenAnyRequest
                  .thenRespond(ResponseStub.adjust(body, StatusCode(status)))
                val client             = CodebergClient.usingBackend(
                  CodebergConfig(Auth.Anonymous).copy(baseUri = instance),
                  backend,
                  sink(kind, if synchronous then throw failure.original else failure.effect, calls),
                )
                owned.set(Some(client))
                val result: Future[Either[CodebergError, String]] =
                  if typed then client.version.attempt.get().map(_.map(_.raw))
                  else client.version.get().map(value => Right(value.raw))
                result.transform: outcome =>
                  client.close()
                  outcome
            finally owned.get().foreach(_.close())
            assert(calls.get() > 0, "public caller never invoked the failing callback/classifier")
            if scenario.preserved then checkFailure(delivered, failure.original)
            else if kind.equals("error") then
              if typed then
                delivered match
                  case Success(Left(CodebergError.Api(_, 403, _, _))) => ()
                  case other => fail(s"ordinary callback replaced typed API failure: $other")
              else
                delivered match
                  case Failure(CodebergException(CodebergError.Api(_, 403, _, _))) => ()
                  case other => fail(s"ordinary callback replaced raised API failure: $other")
            else assertEquals(delivered, Success(Right("12.0.0")))
            if !scenario.name.equals("direct interrupt") then
              assert(failure.reads.get() > 0, "public callback skipped cause classification")
            // onError runs for both attempt and final reporting when an ordinary callback is swallowed.
            val invocations = if kind.equals("error") && !scenario.preserved then 2 else 1
            assertEquals(calls.get(), invocations)
            assert(failure.reads.get() <= scenario.maxReads * invocations, "public cause traversal is unbounded")
