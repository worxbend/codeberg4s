package com.worxbend.codeberg4s.core

import com.worxbend.codeberg4s.CodebergError
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.syntax.discard

import munit.FunSuite

import scala.collection.mutable.ListBuffer

final class ExecSuite extends FunSuite:

  private val exec: Exec[Exec.Result] = Exec[Exec.Result]

  private val failure: CodebergError = ValidationError("owner", "must not be blank")

  test("pure lifts a value into the success channel"):
    assertEquals(exec.pure(1), Right(1))

  test("raise puts the error into the failure channel"):
    assertEquals(exec.raise[Int](failure), Left(failure))

  test("map rewrites a success"):
    assertEquals(exec.map(exec.pure(1))(_ + 1), Right(2))

  test("map leaves a failure untouched"):
    assertEquals(exec.map(exec.raise[Int](failure))(_ + 1), Left(failure))

  test("flatMap sequences two successes"):
    assertEquals(exec.flatMap(exec.pure(1))(value => exec.pure(value + 1)), Right(2))

  test("flatMap short-circuits on a failure"):
    assertEquals(exec.flatMap(exec.raise[Int](failure))(value => exec.pure(value + 1)), Left(failure))

  test("attempt materialises a failure as a value in a successful effect"):
    val expected: Exec.Result[Either[CodebergError, Int]] = Right(Left(failure))

    assertEquals(exec.attempt(exec.raise[Int](failure)), expected)

  test("attempt keeps a success"):
    val expected: Exec.Result[Either[CodebergError, Int]] = Right(Right(1))

    assertEquals(exec.attempt(exec.pure(1)), expected)

  test("suspend evaluates its thunk exactly once"):
    val calls = ListBuffer.empty[Int]

    def thunk(): Exec.Result[Int] =
      calls.append(1).discard
      Right(7)

    assertEquals(exec.suspend(() => thunk()), Right(7))
    assertEquals(calls.size, 1)

  test("the infix syntax delegates to the instance"):
    val expected: Exec.Result[Either[CodebergError, Int]] = Right(Left(failure))

    assertEquals(materialise(exec.raise[Int](failure)), expected)

  /** Exercises the extension syntax the way core logic uses it: over an abstract `F`, not over a concrete `Either`. */
  private def materialise[F[_]](fa: F[Int])(using Exec[F]): F[Either[CodebergError, Int]] =
    import Exec.attempt
    fa.attempt
