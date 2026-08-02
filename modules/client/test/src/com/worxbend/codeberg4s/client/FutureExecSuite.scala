package com.worxbend.codeberg4s.client

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.core.Exec

import munit.FunSuite

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import java.util.concurrent.atomic.AtomicInteger

/** The `Future` instance of [[com.worxbend.codeberg4s.core.Exec]].
  *
  * Every test runs on a [[QueuedExecutionContext]], so each one is synchronous and deterministic: no `Await`, no
  * timeout, no flake. That matters most for `suspend`, whose contract is about *when* something runs.
  */
final class FutureExecSuite extends FunSuite:

  private val Failed: CodebergError = CodebergError.Validation(ValidationError("owner", "must not be blank"))

  private val Defect: Throwable = IllegalStateException("a defect is not a CodebergError")

  test("pure lifts a value into the success channel"):
    val context = QueuedExecutionContext()

    assertEquals(settled(execOn(context).pure(1), context), Success(1))

  test("raise fails the future with a CodebergException carrying the error"):
    val context = QueuedExecutionContext()

    assertEquals(settled(execOn(context).raise[Int](Failed), context), Failure(CodebergException(Failed)))

  test("map rewrites a success"):
    val context = QueuedExecutionContext()
    val exec    = execOn(context)

    assertEquals(settled(exec.map(exec.pure(1))(_ + 1), context), Success(2))

  test("flatMap sequences two effects"):
    val context = QueuedExecutionContext()
    val exec    = execOn(context)

    assertEquals(settled(exec.flatMap(exec.pure(1))(value => exec.pure(value + 1)), context), Success(2))

  test("attempt materialises a raised failure as a Left in a successful future"):
    val context = QueuedExecutionContext()
    val exec    = execOn(context)

    assertEquals(settled(exec.attempt(exec.raise[Int](Failed)), context), Success(Left(Failed)))

  test("attempt keeps a success"):
    val context = QueuedExecutionContext()
    val exec    = execOn(context)

    assertEquals(settled(exec.attempt(exec.pure(1)), context), Success(Right(1)))

  test("attempt leaves a defect failing, because a defect is not a CodebergError"):
    val context = QueuedExecutionContext()
    val exec    = execOn(context)

    assertEquals(settled(exec.attempt(Future.failed[Int](Defect)), context), Failure(Defect))

  test("suspend does not evaluate its thunk before the effect it belongs to runs"):
    val context = QueuedExecutionContext()
    val calls   = AtomicInteger(0)

    val effect = execOn(context).suspend(() => counting(calls))

    assertEquals(calls.get(), 0, "suspend evaluated its thunk eagerly; a retry would re-use one running request")
    assertEquals(settled(effect, context), Success(1))

  test("suspend evaluates its thunk once per effect, which is what makes a retry a second request"):
    val context = QueuedExecutionContext()
    val calls   = AtomicInteger(0)
    val exec    = execOn(context)

    val first  = exec.suspend(() => counting(calls))
    val second = exec.suspend(() => counting(calls))

    assertEquals(settled(first, context), Success(1))
    assertEquals(settled(second, context), Success(2))

  // --- fixtures -------------------------------------------------------------

  private def execOn(context: QueuedExecutionContext): Exec[Future] =
    FutureExec(using context)

  /** A thunk that reports how many times it has been called, as the value it produces. */
  private def counting(calls: AtomicInteger): Future[Int] =
    Future.successful(calls.incrementAndGet())

  /** Drains `context` and reads the outcome the effect settled on. */
  private def settled[A](effect: Future[A], context: QueuedExecutionContext): Try[A] =
    context.runAll()
    effect.value match
      case Some(outcome) => outcome
      case None          => fail("the effect never completed, even after every queued task had run")
