package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.CallContext
import com.worxbend.codeberg4s.CodebergError

/** Observation hooks for applications that want to see what the client is doing.
  *
  * This library has no logging dependency and never will: a published artifact that drags SLF4J, Logback or a
  * configuration file behind it is a nuisance to embed. Applications implement this trait and wire it to whatever they
  * already use; the default is [[Telemetry.noOp]], so a client that is never configured stays completely silent.
  *
  * Everything a callback receives is already redacted — a [[com.worxbend.codeberg4s.CallContext]] holds a redacted URI
  * and a [[com.worxbend.codeberg4s.CodebergError]] renders through [[com.worxbend.codeberg4s.CodebergError.describe]] —
  * so no implementation can leak a credential by accident.
  *
  * Callbacks run on the client's execution path. An implementation that blocks slows every request down; hand work to
  * the application's own executor instead of doing it here.
  *
  * @tparam F
  *   the effect the client runs in
  */
trait Telemetry[F[_]]:

  /** Called once per attempt, before the request leaves. Retries produce one call each. */
  def onRequest(ctx: CallContext): F[Unit]

  /** Called once per attempt that produced a response, whatever the status. */
  def onResponse(ctx: CallContext, status: Int): F[Unit]

  /** Called once per attempt that failed, and once more for the failure the caller finally receives. */
  def onError(ctx: CallContext, error: CodebergError): F[Unit]

object Telemetry:

  /** A sink that observes nothing and allocates nothing per call. The default for every client. */
  def noOp[F[_]](using exec: Exec[F]): Telemetry[F] = new NoOp[F](exec)

  private final class NoOp[F[_]](exec: Exec[F]) extends Telemetry[F]:

    /** The one effect this sink ever returns. Built once per instance so that no callback allocates: a client that
      * leaves telemetry unconfigured still reaches this class three times per attempt, on every request it makes.
      */
    private val done: F[Unit] = exec.pure(())

    override def onRequest(ctx: CallContext): F[Unit] = done

    override def onResponse(ctx: CallContext, status: Int): F[Unit] = done

    override def onError(ctx: CallContext, error: CodebergError): F[Unit] = done
