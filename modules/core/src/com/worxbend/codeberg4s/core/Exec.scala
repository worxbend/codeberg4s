package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.CodebergError

/** The whole effect abstraction this library needs.
  *
  * Core logic is written once against an abstract `F[_]` and instantiated twice: with `Future` in the published client,
  * and with [[Exec.Result]] in every core unit test. Keeping the interface this small is deliberate — the published
  * artifact must not drag a `cats-effect` or `zio` dependency behind it, and six methods are enough for the retry
  * engine, the pagination driver and the request pipeline.
  *
  * An instance is expected to be lawful in the usual sense: `map` preserves identity and composition, `flatMap` is
  * associative with `pure` as its unit, `attempt` turns a raised error into a `Left` without losing it, and `suspend`
  * defers evaluation of its thunk until the effect runs. Core logic tested with [[Exec.Result]] therefore behaves
  * identically under `Future`.
  *
  * @tparam F
  *   the effect the client runs in
  */
trait Exec[F[_]]:

  /** Lifts an already-computed value. */
  def pure[A](value: A): F[A]

  /** Fails the effect with `error`. Recovered by [[attempt]], never by an exception handler in core. */
  def raise[A](error: CodebergError): F[A]

  /** Applies `f` to the successful value, leaving a failure untouched. */
  def map[A, B](fa: F[A])(f: A => B): F[B]

  /** Sequences a dependent effect, leaving a failure untouched. */
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]

  /** Materialises the error channel, so a failure becomes a `Left` value the caller can branch on.
    *
    * The resulting effect always succeeds. Only [[CodebergError]] is caught; a defect stays a defect.
    */
  def attempt[A](fa: F[A]): F[Either[CodebergError, A]]

  /** Defers `thunk` until the effect runs.
    *
    * This is what keeps pagination and retry lazy under an eager `F`: nothing calls the next page's fetch function
    * before the previous page has been folded.
    */
  def suspend[A](thunk: () => F[A]): F[A]

object Exec:

  /** The synchronous effect every core unit test runs against.
    *
    * Being an alias for `Either` makes core logic testable without a scheduler, a thread pool or a timeout, which is
    * also what makes it mutation-testable.
    */
  type Result[A] = Either[CodebergError, A]

  private object EitherExec extends Exec[Result]:

    override def pure[A](value: A): Result[A] = Right(value)

    override def raise[A](error: CodebergError): Result[A] = Left(error)

    override def map[A, B](fa: Result[A])(f: A => B): Result[B] =
      fa match
        case Right(value) => Right(f(value))
        case Left(error)  => Left(error)

    override def flatMap[A, B](fa: Result[A])(f: A => Result[B]): Result[B] =
      fa match
        case Right(value) => f(value)
        case Left(error)  => Left(error)

    override def attempt[A](fa: Result[A]): Result[Either[CodebergError, A]] = Right(fa)

    override def suspend[A](thunk: () => Result[A]): Result[A] = thunk()

  /** Summons the instance for `F`. */
  def apply[F[_]](using exec: Exec[F]): Exec[F] = exec

  /** The synchronous instance backing [[Exec.Result]].
    *
    * `attempt` cannot fail here, and `suspend` evaluates its thunk exactly once, when the surrounding expression is
    * evaluated.
    */
  given eitherExec: Exec[Result] = EitherExec

  extension [F[_], A](fa: F[A])(using exec: Exec[F])

    /** Infix form of [[Exec.map]]. Shadowed by the receiver's own `map` when it has one, which is intentional. */
    def map[B](f: A => B): F[B] = exec.map(fa)(f)

    /** Infix form of [[Exec.flatMap]]. Shadowed by the receiver's own `flatMap` when it has one. */
    def flatMap[B](f: A => F[B]): F[B] = exec.flatMap(fa)(f)

    /** Infix form of [[Exec.attempt]]. */
    def attempt: F[Either[CodebergError, A]] = exec.attempt(fa)
