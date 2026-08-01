package com.worxbend.codeberg4s

import com.worxbend.codeberg4s.client.FutureExec
import com.worxbend.codeberg4s.client.FutureTimer
import com.worxbend.codeberg4s.codec.ApiErrorBodyCodec
import com.worxbend.codeberg4s.core.ApiPipeline
import com.worxbend.codeberg4s.core.Exec
import com.worxbend.codeberg4s.core.Telemetry
import com.worxbend.codeberg4s.repositories.RepositoryApi
import com.worxbend.codeberg4s.syntax.discard
import com.worxbend.codeberg4s.transport.SttpHttpPort

import sttp.client4.Backend

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

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
    pipeline: ApiPipeline[Future],
    timer: FutureTimer,
    ownedBackend: Option[Backend[Future]],
)(using Exec[Future]):

  /** `GET /version` — what software the instance is running. */
  val version: VersionApi = VersionApi(pipeline)

  /** Repository endpoints, such as `GET /repos/{owner}/{repo}`. */
  val repos: RepositoryApi = RepositoryApi(pipeline)

  private val closed: AtomicBoolean = AtomicBoolean(false)

  /** Releases the resources this client owns.
    *
    * Always releases the scheduler thread behind the retry backoff. Closes the HTTP backend '''only''' when this client
    * created it, that is when it was built by [[CodebergClient.apply]]; a backend passed to
    * [[CodebergClient.usingBackend]] belongs to the caller, who may well be sharing it with the rest of their
    * application, and closing it here would break them.
    *
    * Idempotent and safe to call from any thread: the second and later calls do nothing. Backend shutdown is
    * asynchronous in sttp, so this method returns before the backend's own connections are gone; nothing in this
    * library observes that, and waiting for it would make an ordinary `finally` block block.
    *
    * Using a client after closing it is a defect. Calls will fail with a rejected-execution failure from the scheduler
    * rather than with a [[CodebergError]], because a closed client is a programming mistake and not a remote failure to
    * be retried.
    */
  def close(): Unit =
    if closed.compareAndSet(false, true) then
      timer.close()
      ownedBackend.foreach(backend => backend.close().discard)

/** Ways to build a [[CodebergClient]], differing only in who owns the HTTP backend. */
object CodebergClient:

  /** Builds a client that creates and owns its own HTTP backend.
    *
    * The backend is a JDK-HTTP-client sttp backend configured with [[CodebergConfig.connectTimeout]], and [[close]]
    * shuts it down. This is the right constructor unless the application already has an sttp backend it wants reused.
    *
    * @param config
    *   the instance to talk to, the credentials, the retry policy and the timeouts
    */
  def apply(config: CodebergConfig)(using executionContext: ExecutionContext): CodebergClient =
    owning(config, SttpHttpPort.defaultBackend(config.connectTimeout, executionContext))

  /** Builds a client on a backend the caller owns.
    *
    * [[CodebergClient.close]] will '''not''' close `backend`; the caller closes it, after closing every client built on
    * it. Use this to share one connection pool across several clients, or to substitute a
    * `sttp.client4.testing.BackendStub` in a test.
    *
    * Note that sttp models the connect timeout as a property of the backend rather than of a request, so
    * [[CodebergConfig.connectTimeout]] is ignored here — configure it on `backend`. [[CodebergConfig.readTimeout]] is
    * applied per request and is honoured either way.
    *
    * @param config
    *   the instance to talk to, the credentials, the retry policy and the read timeout
    * @param backend
    *   the sttp backend to send on, owned and closed by the caller
    */
  def usingBackend(config: CodebergConfig, backend: Backend[Future])(using ExecutionContext): CodebergClient =
    build(config, backend, None)

  /** Builds a client that takes ownership of `backend` and closes it in [[CodebergClient.close]].
    *
    * Internal: this is what [[apply]] delegates to once it has created a backend, and it is what lets the ownership
    * rule be tested without opening a real connection pool. Callers outside the library choose between [[apply]] and
    * [[usingBackend]], which is the whole decision.
    */
  private[codeberg4s] def owning(config: CodebergConfig, backend: Backend[Future])(using
      ExecutionContext): CodebergClient =
    build(config, backend, Some(backend))

  private def build(
      config: CodebergConfig,
      backend: Backend[Future],
      owned: Option[Backend[Future]],
  )(using ExecutionContext): CodebergClient =
    given Exec[Future] = FutureExec()

    val timer = FutureTimer()

    val pipeline = ApiPipeline[Future](
      SttpHttpPort(backend, config),
      config,
      timer,
      Telemetry.noOp[Future],
      ApiErrorBodyCodec.parse,
    )

    new CodebergClient(pipeline, timer, owned)
