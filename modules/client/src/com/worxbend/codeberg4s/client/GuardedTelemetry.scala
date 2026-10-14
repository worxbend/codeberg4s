package com.worxbend.codeberg4s.client

import com.worxbend.codeberg4s.core.{FailureCauses, Telemetry}
import com.worxbend.codeberg4s.{CallContext, CodebergError}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/** Wraps a caller's [[com.worxbend.codeberg4s.core.Telemetry]] so that a broken sink cannot break the call it is
  * watching.
  *
  * A sink is application code that this library agreed to run on its own request path, and there are exactly two ways
  * for that code to go wrong: it throws where it stands, before any `Future` exists to carry the failure, or it returns
  * a `Future` that fails later. Ordinary sink defects are not [[com.worxbend.codeberg4s.CodebergError]] values, so they
  * cannot be materialised by [[com.worxbend.codeberg4s.core.Exec.attempt]] — which catches this library's own failures
  * and deliberately lets every other throwable stay failed, so that a defect in caller code is never laundered into an
  * error the caller is told to act on. Correct as that is, it left an unobserved gap: a `NullPointerException` from a
  * logging callback reached the caller of `repos.get` as the outcome of a request the server had already answered
  * successfully.
  *
  * This class closes the gap at the only place where it can be closed without weakening `attempt`: the boundary where
  * the caller's sink enters the library. Ordinary failures on both boundaries end as a successful `Future[Unit]`, and
  * the observation is lost — which is the intended trade, because instrumentation that breaks must not break the
  * application it instruments.
  *
  * '''Fatal errors are not swallowed.''' A `VirtualMachineError` such as `OutOfMemoryError`, a `LinkageError`, a
  * `ControlThrowable` or an `InterruptedException` passes straight through, on either rail. Those say the process is no
  * longer sound, or that someone asked for cancellation; hiding one to protect a single API call would trade a visible
  * crash for a silent corruption, or a cancellation for a hang. Fatal causes inside ordinary wrappers and uncertain
  * over-budget cause chains retain the original failed outcome.
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
    catch
      case NonFatal(error) =>
        if GuardedTelemetry.isFatal(error) then Future.failed(error) else Future.unit

private[codeberg4s] object GuardedTelemetry:

  /** The one outcome a guarded callback reports. Held as a value so that no callback allocates it. */
  private val Observed: Try[Unit] = Success(())

  /** Turns any hideable outcome into success, and leaves a fatal one exactly as it was. */
  private val swallowed: Try[Unit] => Try[Unit] =
    case failure @ Failure(error) if isFatal(error) => failure
    case _                                          => Observed

  /** Inspect every cause, not just the ExecutionException box Scala Futures use for fatal failures. Identity tracking
    * makes ordinary cycles finite; a small budget prevents hostile deep chains from overflowing a continuation and
    * leaving its Future pending. An unseen tail is uncertain, so preserve the original failure rather than swallow it.
    * Interrupts remain fatal here, independently of the transport's interrupt classification policy.
    */
  private def isFatal(error: Throwable): Boolean =
    FailureCauses.inspect(error).fold(true)(_.all.exists(cause => !NonFatal(cause)))
