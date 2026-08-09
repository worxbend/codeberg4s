package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.CallContext
import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.syntax.discard

import scala.collection.mutable.ListBuffer

/** A [[Telemetry]] that writes every callback down, and optionally fails each one.
  *
  * Two things need pinning down and both need this fake: that the hooks fire in the documented order, and that a sink
  * which fails cannot fail the request it is only watching.
  *
  * @param failing
  *   when `true`, every callback records its event and then reports a failure
  */
final class RecordingTelemetry(failing: Boolean) extends Telemetry[Exec.Result]:

  private val recorded = ListBuffer.empty[String]
  private val contexts = ListBuffer.empty[CallContext]

  override def onRequest(ctx: CallContext): Exec.Result[Unit] =
    record(ctx, s"request ${ctx.operation}")

  override def onResponse(ctx: CallContext, status: Int): Exec.Result[Unit] =
    record(ctx, s"response $status")

  override def onError(ctx: CallContext, error: CodebergError): Exec.Result[Unit] =
    record(ctx, s"error ${RecordingTelemetry.nameOf(error)}")

  /** Every callback, rendered as a short label, in the order it fired. */
  def events: Vector[String] = recorded.toVector

  /** The call context each callback received, in the same order as [[events]]. */
  def seen: Vector[CallContext] = contexts.toVector

  private def record(ctx: CallContext, event: String): Exec.Result[Unit] =
    recorded.append(event).discard
    contexts.append(ctx).discard
    if failing then Left(RecordingTelemetry.Exploded) else Right(())

object RecordingTelemetry:

  /** What a failing sink reports. It must never reach the caller of a request. */
  val Exploded: CodebergError =
    CodebergError.Validation(ValidationError("telemetry", "the sink itself failed"))

  /** The case name of an error, which is all an ordering assertion needs. */
  def nameOf(error: CodebergError): String =
    error match
      case CodebergError.Transport(_, _)            => "Transport"
      case CodebergError.Api(_, _, _)               => "Api"
      case CodebergError.DecodingFailed(_, _, _, _) => "DecodingFailed"
      case CodebergError.Validation(_)              => "Validation"
      case CodebergError.RetriesExhausted(_, _, _)  => "RetriesExhausted"
      case CodebergError.WalkTruncated(_, _)        => "WalkTruncated"
