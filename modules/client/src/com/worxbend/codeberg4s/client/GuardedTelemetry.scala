package com.worxbend.codeberg4s.client

import com.worxbend.codeberg4s.core.Telemetry
import com.worxbend.codeberg4s.{CallContext, CodebergError}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

import java.util.concurrent.ExecutionException

/** Wraps a caller's [[com.worxbend.codeberg4s.core.Telemetry]] so that a broken sink cannot break the call it is
  * watching.
  *
  * A sink is application code that this library agreed to run on its own request path, and there are exactly two ways
  * for that code to go wrong: it throws where it stands, before any `Future` exists to carry the failure, or it returns
  * a `Future` that fails later. Neither is a [[com.worxbend.codeberg4s.CodebergError]], so neither can be materialised
  * by [[com.worxbend.codeberg4s.core.Exec.attempt]] — which catches this library's own failures and deliberately lets
  * every other throwable stay failed, so that a defect in caller code is never laundered into an error the caller is
  * told to act on. Correct as that is, it left an unobserved gap: a `NullPointerException` from a logging callback
  * reached the caller of `repos.get` as the outcome of a request the server had already answered successfully.
  *
  * This class closes the gap at the only place where it can be closed without weakening `attempt`: the boundary where
  * the caller's sink enters the library. Both failure modes end as a successful `Future[Unit]`, and the observation is
  * lost — which is the intended trade, because instrumentation that breaks must not break the application it
  * instruments.
  *
  * '''Fatal errors are not swallowed.''' A `VirtualMachineError` such as `OutOfMemoryError`, a `LinkageError`, a
  * `ControlThrowable` or an `InterruptedException` passes straight through, on either rail. Those say the process is no
  * longer sound, or that someone asked for cancellation; hiding one to protect a single API call would trade a visible
  * crash for a silent corruption, or a cancellation for a hang.
  *
  * Only a sink the caller supplied is wrapped. [[com.worxbend.codeberg4s.core.Telemetry.noOp]] is this library's own
  * code, it cannot fail, and it is on the path of every request an unconfigured client makes, so it is left alone
  * rather than paying for a guard it does not need.
  *
  * @param sink
  *   the caller's observer, called exactly once per callback
  * @param executionContext
  *   where the guard's continuation runs; the caller's, as everywhere else in this library
  */
private[codeberg4s] final class GuardedTelemetry(sink: Telemetry[Future])(using executionContext: ExecutionContext)
    extends Telemetry[Future]:

  override def onRequest(ctx: CallContext): Future[Unit] = guarded(sink.onRequest(ctx))

  override def onResponse(ctx: CallContext, status: Int): Future[Unit] = guarded(sink.onResponse(ctx, status))

  override def onError(ctx: CallContext, error: CodebergError): Future[Unit] = guarded(sink.onError(ctx, error))

  /** Runs one callback and reports success whatever it does, short of a fatal error.
    *
    * `callback` is by-name because evaluating it is itself one of the two failure modes: the `try` has to be around the
    * call to the sink, not only around the `Future` the call produced.
    */
  private def guarded(callback: => Future[Unit]): Future[Unit] =
    try callback.transform(GuardedTelemetry.swallowed)
    catch case NonFatal(_) => Future.unit

private[codeberg4s] object GuardedTelemetry:

  /** The one outcome a guarded callback reports. Held as a value so that no callback allocates it. */
  private val Observed: Try[Unit] = Success(())

  /** Turns any hideable outcome into success, and leaves a fatal one exactly as it was. */
  private val swallowed: Try[Unit] => Try[Unit] =
    case failure @ Failure(error) if isFatal(error) => failure
    case _                                          => Observed

  /** Whether `error` says the process is no longer sound — seeing through the box a `Future` puts one in.
    *
    * `scala.util.control.NonFatal` answers this directly for a throwable that arrives unaltered, which is what happens
    * when a callback throws where it stands. It does not answer it for one that arrives through a `Future`: completing
    * a promise with a `VirtualMachineError`, a `LinkageError`, an `InterruptedException` or a `ControlThrowable`
    * replaces it with a `java.util.concurrent.ExecutionException` wrapping the original, and that wrapper is an
    * ordinary exception. Asking `NonFatal` alone would therefore hide the one class of failure that must never be
    * hidden — an interrupt above all, because a swallowed `InterruptedException` is a cancellation the caller asked for
    * and did not get.
    *
    * The recursion is for a cause that is itself boxed, which costs one line and removes the question.
    */
  private def isFatal(error: Throwable): Boolean =
    error match
      case boxed: ExecutionException => Option(boxed.getCause).exists(isFatal)
      case other                     => !NonFatal(other)
