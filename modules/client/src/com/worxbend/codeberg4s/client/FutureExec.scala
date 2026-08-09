package com.worxbend.codeberg4s.client

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.CodebergException
import com.worxbend.codeberg4s.core.Exec

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

/** The [[com.worxbend.codeberg4s.core.Exec]] instance the published client runs on.
  *
  * Everything cross-cutting in this library — the retry engine, the request pipeline, the pagination driver — is
  * written once against an abstract `F` and unit-tested with `F = Either`. This class is the other instantiation, and
  * it is the only place where the difference between the two effects has to be thought about.
  *
  * Two of the six methods carry the whole weight:
  *
  *   - '''`suspend` genuinely defers.''' A `Future` starts running the moment it is created, so an implementation that
  *     evaluated its thunk eagerly would hand the retry engine the '''same''' already-running effect on every pass and
  *     the client would silently never retry. `Future.delegate` composes the thunk into the effect instead of calling
  *     it, which is what makes a second attempt a second request. `FutureExecSuite` pins this down by counting
  *     invocations against an execution context that runs nothing until it is told to.
  *   - '''`attempt` only catches this library's failures.''' A [[com.worxbend.codeberg4s.CodebergException]] becomes a
  *     `Left`; anything else stays a failed `Future`, because a `NullPointerException` from a caller's telemetry
  *     callback is a defect and must not be laundered into a [[com.worxbend.codeberg4s.CodebergError]] nobody can act
  *     on.
  *
  * @param executionContext
  *   where continuations run; supplied by the caller, never created by this library
  */
final class FutureExec(using executionContext: ExecutionContext) extends Exec[Future]:

  override def pure[A](value: A): Future[A] = Future.successful(value)

  /** Fails the effect with [[com.worxbend.codeberg4s.CodebergException]], the sole bridge into `Future`'s failure
    * channel.
    */
  override def raise[A](error: CodebergError): Future[A] = Future.failed(CodebergException(error))

  override def map[A, B](fa: Future[A])(f: A => B): Future[B] = fa.map(f)

  override def flatMap[A, B](fa: Future[A])(f: A => Future[B]): Future[B] = fa.flatMap(f)

  /** Materialises a [[com.worxbend.codeberg4s.CodebergError]] as a `Left`, leaving every other throwable failing.
    *
    * This is what the typed rail is built from: `client.repos.attempt.get(…)` is `attempt(client.repos.get(…))`, so the
    * two rails cannot drift apart.
    *
    * Written as one `transform` rather than `map(…).recover(…)`: the pair would build an intermediate `Future` and
    * dispatch to the execution context twice for every call, and this sits on the path of every request the typed rail
    * makes.
    */
  override def attempt[A](fa: Future[A]): Future[Either[CodebergError, A]] =
    fa.transform:
      case Success(value)                    => Success(Right(value))
      case Failure(CodebergException(error)) => Success(Left(error))
      case Failure(other)                    => Failure(other)

  /** Defers `thunk` until the returned effect is composed, so each retry issues a fresh request. See the class note. */
  override def suspend[A](thunk: () => Future[A]): Future[A] = Future.delegate(thunk())

object FutureExec:

  /** The instance for `Future`, resolved from the caller's execution context.
    *
    * Import it — `import com.worxbend.codeberg4s.client.FutureExec.given` — to build core components such as
    * [[com.worxbend.codeberg4s.core.ApiPipeline]] directly. [[com.worxbend.codeberg4s.CodebergClient]] does this for
    * you.
    */
  given futureExec(using ExecutionContext): Exec[Future] = FutureExec()
