package com.worxbend.codeberg4s.examples

import com.worxbend.codeberg4s.auth.Auth
import com.worxbend.codeberg4s.core.Telemetry
import com.worxbend.codeberg4s.{
  CallContext,
  CodebergClient,
  CodebergConfig,
  CodebergError,
  Owner,
  RepoName,
  ValidationError
}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

/** Seeing what the client does, by implementing the one observation port it offers.
  *
  * ==Running it==
  *
  * {{{
  * ./mill modules.examples.runMain com.worxbend.codeberg4s.examples.ObservingRequests
  * }}}
  *
  * ==Environment==
  *
  * None. It makes two anonymous calls against codeberg.org, one of which fails with a `404` so that the error callback
  * fires as well.
  *
  * ==Why a port and not a logger==
  *
  * The library has no logging dependency and writes nothing anywhere. A published artifact that drags SLF4J, Logback
  * and a configuration file behind it is a nuisance to embed, so the client reports what it does to a
  * [[com.worxbend.codeberg4s.core.Telemetry]] the application supplies and stays silent otherwise. Wire it to whatever
  * you already use; [[com.worxbend.codeberg4s.core.Telemetry.noOp]] is the default and allocates nothing per call.
  *
  * ==The contract an implementation must respect==
  *
  *   - '''Callbacks run on the client's execution path.''' One that blocks slows every request down. Hand work to the
  *     application's own executor rather than doing it inline; this example prints, which is cheap and is the point.
  *   - '''A telemetry failure never fails the call it observed.''' Instrumentation that breaks must not break the
  *     application it instruments.
  *   - '''`onRequest` fires once per attempt.''' A retried call produces several, which is exactly how a retry storm
  *     becomes visible.
  *   - '''`onError` fires once per failed attempt, and once more for the failure the caller finally receives.''' A line
  *     appearing twice is the contract, not a bug.
  *
  * ==Nothing here can leak a credential==
  *
  * A [[com.worxbend.codeberg4s.CallContext]] carries the redacted URI — the transport redacts before the context is
  * built, and nothing downstream re-derives a URI from the configuration — and a
  * [[com.worxbend.codeberg4s.CodebergError]] renders through `describe`, which is built only from that context and from
  * server-supplied text. An implementation therefore cannot leak a token by logging what it is handed.
  */
object ObservingRequests:

  private val AwaitLimit: FiniteDuration = 2.minutes

  /** A real owner and a repository name that will not resolve, so the second call below is a `404`. */
  private val missing: Either[ValidationError, (Owner, RepoName)] =
    for
      owner <- Owner.from("forgejo")
      name  <- RepoName.from("codeberg4s-no-such-repository")
    yield (owner, name)

  def main(args: Array[String]): Unit =
    given ExecutionContext = ExecutionContext.global

    // The observing constructor. Everything else about the client is unchanged;
    // there is no separate "instrumented client" type.
    val client: CodebergClient = CodebergClient(CodebergConfig(Auth.Anonymous), PrintingTelemetry)

    try
      ExampleConsole.heading("a call that succeeds")
      ExampleConsole.line(s"  version ${Await.result(client.version.get(), AwaitLimit).raw}")

      missing match
        case Left(problem) =>
          ExampleConsole.line(s"invalid ${problem.field}: ${problem.message}")

        case Right((owner, name)) =>
          ExampleConsole.heading("a call that fails, so onError fires too")
          // The typed rail, so the 404 does not end the program before the
          // telemetry lines have been printed.
          val outcome = Await.result(client.repos.attempt.get(owner, name), AwaitLimit)
          ExampleConsole.line(s"  the caller received a Left: ${outcome.isLeft}")
    finally client.close()

  /** A telemetry that writes one line per event to standard output.
    *
    * `Telemetry` is parameterised by the effect the client runs in, which for the published client is always `Future`.
    * Each callback returns `F[Unit]`; `Future.successful` is the right answer for work that is already done by the time
    * the method returns.
    *
    * Every field of [[com.worxbend.codeberg4s.CallContext]] appears below, because between them they are what a bug
    * report needs: a stable operation id that is safe to alert on, the method, the redacted URI, the instance's
    * `x-request-id` when it sent one, and how long the attempt took.
    */
  private object PrintingTelemetry extends Telemetry[Future]:

    override def onRequest(ctx: CallContext): Future[Unit] =
      Future.successful(ExampleConsole.line(s"  -> ${render(ctx)}"))

    override def onResponse(ctx: CallContext, status: Int): Future[Unit] =
      Future.successful(ExampleConsole.line(s"  <- $status ${render(ctx)}"))

    override def onError(ctx: CallContext, error: CodebergError): Future[Unit] =
      // `describe` is bounded and secret-free, which is what makes it safe to
      // put straight into a log line.
      Future.successful(ExampleConsole.line(s"  !! ${ctx.operation}: ${error.describe}"))

    private def render(ctx: CallContext): String =
      val requestId = ctx.requestId.getOrElse("none")
      s"${ctx.operation} ${ctx.method.wireName} ${ctx.uri} [request-id $requestId] ${ctx.durationMs}ms"
