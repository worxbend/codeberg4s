package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.client.{FutureExec, FutureTimer, GuardedTelemetry}
import com.worxbend.codeberg4s.codec.ApiErrorBodyCodec
import com.worxbend.codeberg4s.core.{ApiPipeline, Exec, Telemetry}
import com.worxbend.codeberg4s.issues.IssueApi
import com.worxbend.codeberg4s.miscellaneous.MiscellaneousApi
import com.worxbend.codeberg4s.notifications.NotificationApi
import com.worxbend.codeberg4s.organizations.OrganizationApi
import com.worxbend.codeberg4s.paging.{PageNumber, PageParams}
import com.worxbend.codeberg4s.pulls.PullRequestApi
import com.worxbend.codeberg4s.repositories.RepositoryApi
import com.worxbend.codeberg4s.syntax.discard
import com.worxbend.codeberg4s.transport.SttpHttpPort
import com.worxbend.codeberg4s.users.UserApi

import sttp.client4.Backend

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

import java.util.concurrent.atomic.AtomicBoolean

/** The published entry point: one configured connection to one Forgejo or Codeberg instance.
  *
  * {{{
  * given ExecutionContext = ExecutionContext.global
  *
  * val client = CodebergClient(CodebergConfig(Auth.Token(token)))
  * try client.repos.get(owner, name).map(_.starsCount)
  * finally client.close()
  * }}}
  *
  * '''Two rails, one implementation.''' Every operation appears twice — once on the resource group, failing the
  * `Future` with [[CodebergException]], and once under `.attempt`, returning `Either[CodebergError, A]` and never
  * failing. The second is the first with its failure channel materialised by
  * [[com.worxbend.codeberg4s.core.Exec.attempt]], so choosing a rail is a choice of error style and never a choice of
  * behaviour (ADR-0005).
  *
  * {{{
  * client.repos.get(owner, name)         // Future[Repository]
  * client.repos.attempt.get(owner, name) // Future[Either[CodebergError, Repository]]
  * }}}
  *
  * '''Thread safety.''' A client is immutable apart from its closed flag and is meant to be shared: build one per
  * instance you talk to, for the lifetime of the application, and hand it around. Building one per request would create
  * a scheduler thread and, on [[CodebergClient.apply]], an HTTP connection pool per request.
  *
  * '''Lifecycle.''' [[close]] releases what this client owns. What that includes depends on how it was built — see
  * [[CodebergClient.apply]] and [[CodebergClient.usingBackend]].
  */
final class CodebergClient private (
    config: CodebergConfig,
    pipeline: ApiPipeline[Future],
    timer: FutureTimer,
    ownedBackend: Option[Backend[Future]],
)(using Exec[Future]):

  /** The first page at this client's [[CodebergConfig.defaultPageSize]] — where a listing or a
    * [[com.worxbend.codeberg4s.paging.PageWalk]] against this client starts.
    *
    * This is how `defaultPageSize` reaches the listings: every listing takes explicit
    * [[com.worxbend.codeberg4s.paging.PageParams]], and this value is those params pre-filled from the configuration.
    * [[com.worxbend.codeberg4s.paging.PageParams.First]] is the config-free constant with the library-wide default
    * size; prefer `client.firstPage` when the client is in hand so a configured size is honoured.
    */
  val firstPage: PageParams = PageParams(PageNumber.First, config.defaultPageSize)

  /** `GET /version` — what software the instance is running. */
  val version: VersionApi = VersionApi(pipeline)

  /** Repository endpoints, such as `GET /repos/{owner}/{repo}`. */
  val repos: RepositoryApi = RepositoryApi(pipeline)

  /** User endpoints, such as `GET /user` and `GET /users/{username}`. */
  val users: UserApi = UserApi(pipeline)

  /** Issue endpoints, including comments, labels and milestones. */
  val issues: IssueApi = IssueApi(pipeline)

  /** Pull-request endpoints, including merge, reviews and changed files. */
  val pulls: PullRequestApi = PullRequestApi(pipeline)

  /** Organisation and team endpoints. */
  val organizations: OrganizationApi = OrganizationApi(pipeline)

  /** Notification endpoints. All of them require authentication. */
  val notifications: NotificationApi = NotificationApi(pipeline)

  /** Instance-level endpoints: server settings, the signing key and markdown rendering. */
  val misc: MiscellaneousApi = MiscellaneousApi(pipeline)

  private val closing: AtomicBoolean = AtomicBoolean(false)

  private val release: Promise[Unit] = Promise[Unit]()

  /** Releases the resources this client owns.
    *
    * Always releases the scheduler thread behind the retry backoff and the read-timeout deadlines. Closes the HTTP
    * backend '''only''' when this client created it, that is when it was built by [[CodebergClient.apply]]; a backend
    * passed to [[CodebergClient.usingBackend]] belongs to the caller, who may well be sharing it with the rest of their
    * application, and closing it here would break them.
    *
    * Closing an owned backend ends the JDK `java.net.http.HttpClient` under it: this library calls that client's
    * `shutdown()`, which refuses new requests, lets the ones already sent run to completion, and returns without
    * waiting for them. The JDK also offers a `close()` that waits, and this method deliberately does not use it — see
    * "Idempotent" below. The `ExecutionContext` the client was built with is untouched, because that one belongs to the
    * caller.
    *
    * Idempotent and safe to call from any thread: the second and later calls do nothing. Nothing here blocks, so this
    * method returns before the last in-flight response has been delivered; nothing in this library observes that, and
    * waiting for it would make an ordinary `finally` block block.
    *
    * '''What this method does not report.''' sttp answers a backend's own `close()` with a `Future[Unit]`, and this
    * method neither waits for it nor returns it — it returns before the pool has finished shutting down, and a
    * `finally client.close()` stays a one-liner. That outcome is available as [[released]]; an application that wants
    * to know whether its connection pool shut down cleanly, or to log a backend that failed to, observes that `Future`.
    * Nothing else in this library looks at it, so a failed backend close that nobody observes is discarded silently.
    *
    * '''Using a client after closing it is a defect, and not one this library detects up front.''' A call made
    * afterwards fails only when it reaches something this method released, and succeeds otherwise:
    *
    *   - a call that needs the scheduler for a retry backoff fails with a
    *     `java.util.concurrent.RejectedExecutionException` from it, and a call that was already sitting in a backoff
    *     when `close()` arrived fails with a `java.util.concurrent.CancellationException` — see
    *     [[com.worxbend.codeberg4s.client.FutureTimer.close]]. The read-timeout deadline every send arms on the same
    *     scheduler does not count: a deadline the scheduler refuses is simply not armed, so the send goes on unbounded
    *     rather than failing;
    *   - a call that reaches a backend this client owns is refused by the shut-down JDK client with an `IOException` —
    *     measured, on JDK 25: `java.io.IOException: closed` — which the transport classifies as
    *     [[TransportCause.ConnectionFailed]]. Under a retry policy that repeats such a failure, the repeat's backoff
    *     then meets the released scheduler, so what the caller sees is the `RejectedExecutionException` above; under
    *     [[com.worxbend.codeberg4s.retry.RetryPolicy.Off]], or an eligibility that forbids repeating, it is a
    *     [[CodebergError.Transport]] carrying that cause;
    *   - a first attempt on a backend the caller supplied reaches neither, goes through, and succeeds.
    *
    * None of the failures is laundered into a [[CodebergError]] the caller would be told to retry, because a closed
    * client is a programming mistake and not a remote failure. And every one of them '''is''' a failure, which is the
    * guarantee that matters at shutdown: no `Future` this client handed out is left without an outcome, so an
    * application closing down never waits on one that cannot finish.
    */
  def close(): Unit =
    if closing.compareAndSet(false, true) then
      timer.close()
      release.completeWith(closeOwnedBackend()).discard

  /** The outcome of releasing the backend this client owns — where a failed backend close is surfaced.
    *
    * Completes once [[close]] has been called '''and''' the owned backend's own `close()` has completed; fails when
    * that `close()` failed, with whatever it failed with, and also when it threw instead of returning a `Future`. For a
    * client built by [[CodebergClient.usingBackend]] there is nothing to release, so it completes as soon as [[close]]
    * is called. It never completes on a client that is never closed.
    *
    * This exists because [[close]] returns nothing about the backend: a `finally` block has no use for a `Future`, and
    * most applications have no use for the outcome of a connection pool's shutdown. The ones that do — a service that
    * logs every resource it failed to release, a test that asserts the pool really went away — observe this instead of
    * being made to wait on it.
    */
  def released: Future[Unit] = release.future

  /** `close()` on the owned backend as a `Future`, whether it completed, failed, threw or does not exist. A backend
    * that throws from `close()` — a user-supplied one, say — becomes a failed `Future` rather than an exception out of
    * [[close]], so a `finally` block that closes this client can never itself blow up.
    */
  private def closeOwnedBackend(): Future[Unit] =
    ownedBackend.fold(Future.unit)(backend => Try(backend.close()).fold(Future.failed, identity))

/** Ways to build a [[CodebergClient]], differing only in who owns the HTTP backend. */
object CodebergClient:

  /** Builds a client that creates and owns its own HTTP backend.
    *
    * The backend is a JDK-HTTP-client sttp backend configured with [[CodebergConfig.connectTimeout]], and [[close]]
    * shuts it down — the connection pool and the JDK client's own selector thread go with it. This is the right
    * constructor unless the application already has an sttp backend it wants reused.
    *
    * @param config
    *   the instance to talk to, the credentials, the retry policy and the timeouts
    */
  def apply(config: CodebergConfig)(using executionContext: ExecutionContext): CodebergClient =
    owning(config, SttpHttpPort.defaultBackend(config.connectTimeout, executionContext))

  /** Builds a client that reports what it does to `telemetry`.
    *
    * This library has no logging dependency and writes nothing anywhere, so this is the only way to see requests. A
    * [[com.worxbend.codeberg4s.core.Telemetry]] failure never fails the call it was observing — instrumentation that
    * breaks must not break the application it instruments. That covers both ways a callback can go wrong: throwing
    * where it stands, and returning a `Future` that fails afterwards. Either way the observation is lost and the
    * request's own outcome is what the caller receives. A fatal error — an `OutOfMemoryError`, say — is not swallowed,
    * because it says the process is no longer sound.
    *
    * The callbacks receive a [[CallContext]] whose URI is already redacted, so an implementation cannot leak a token by
    * logging what it is handed.
    *
    * @param config
    *   the instance to talk to, the credentials, the retry policy and the timeouts
    * @param telemetry
    *   the observer; use [[com.worxbend.codeberg4s.core.Telemetry.noOp]] to disable
    */
  def apply(config: CodebergConfig, telemetry: Telemetry[Future])(using
      executionContext: ExecutionContext): CodebergClient =
    val backend = SttpHttpPort.defaultBackend(config.connectTimeout, executionContext)
    build(config, backend, Some(backend), Some(telemetry))

  /** Builds a client on a backend the caller owns.
    *
    * [[CodebergClient.close]] will '''not''' close `backend`; the caller closes it, after closing every client built on
    * it. Use this to share one connection pool across several clients, or to substitute a
    * `sttp.client4.testing.BackendStub` in a test.
    *
    * Note that sttp models the connect timeout as a property of the backend rather than of a request, so
    * [[CodebergConfig.connectTimeout]] is ignored here — configure it on `backend`. [[CodebergConfig.readTimeout]]
    * bounds the whole exchange of each request, headers and body, and is honoured either way; see
    * [[com.worxbend.codeberg4s.transport.SttpHttpPort]].
    *
    * @param config
    *   the instance to talk to, the credentials, the retry policy and the read timeout
    * @param backend
    *   the sttp backend to send on, owned and closed by the caller
    */
  def usingBackend(config: CodebergConfig, backend: Backend[Future])(using ExecutionContext): CodebergClient =
    build(config, backend, None, None)

  /** [[usingBackend]] with an observer. Ownership is unchanged: the caller closes `backend`. */
  def usingBackend(config: CodebergConfig, backend: Backend[Future], telemetry: Telemetry[Future])(using
      ExecutionContext): CodebergClient =
    build(config, backend, None, Some(telemetry))

  /** Builds a client that takes ownership of `backend` and closes it in [[CodebergClient.close]].
    *
    * Internal: this is what [[apply]] delegates to once it has created a backend, and it is what lets the ownership
    * rule be tested without opening a real connection pool. Callers outside the library choose between [[apply]] and
    * [[usingBackend]], which is the whole decision.
    */
  private[codeberg4s] def owning(config: CodebergConfig, backend: Backend[Future])(using
      ExecutionContext): CodebergClient =
    build(config, backend, Some(backend), None)

  private def build(
      config: CodebergConfig,
      backend: Backend[Future],
      owned: Option[Backend[Future]],
      telemetry: Option[Telemetry[Future]],
  )(using ExecutionContext): CodebergClient =
    given Exec[Future] = FutureExec()

    val timer = FutureTimer()

    // Resolved here rather than at the call site because Telemetry.noOp needs
    // the Exec[Future] that only exists once this method has built it.
    //
    // A caller's sink is wrapped so that a callback which throws, or which
    // returns a failed Future, cannot fail the request it was watching; see
    // GuardedTelemetry for why the guard belongs here and not in Exec.attempt.
    // Telemetry.noOp is this library's own code and cannot fail, so it is left
    // unwrapped rather than paying for a guard on every unconfigured request.
    val observer = telemetry.fold(Telemetry.noOp[Future])(GuardedTelemetry(_))

    // The port shares the retry scheduler: every send races the whole exchange
    // against a readTimeout deadline on it, so the deadline costs no thread and
    // is released with the client. See SttpHttpPort's "Timeouts" note.
    val port = SttpHttpPort(backend, config, timer)

    val pipeline = ApiPipeline[Future](
      port,
      config,
      timer,
      observer,
      ApiErrorBodyCodec.parse,
    )

    new CodebergClient(config, pipeline, timer, owned)
