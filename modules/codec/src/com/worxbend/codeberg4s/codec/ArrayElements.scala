package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.core.DecodeFailure

import scala.annotation.tailrec

/** Applies a conversion that can fail to every element of an array, stopping at the first element that fails.
  *
  * Four places in this module wanted exactly this and each had grown its own copy: [[JsonDecoder.arrayOf]] and
  * [[JsonDecoder.all]] for `JSON → DTO`, and a `wire` helper per endpoint group for `DTO → domain`. The copies agreed
  * on the contract, which is the only reason they were survivable; they are here once so they cannot start disagreeing.
  *
  * '''One bad element fails the whole array.''' A listing that silently dropped a malformed element would under-report,
  * and a caller cannot tell an under-report from a short page.
  *
  * Two shapes are offered, because callers arrive from two directions. The `JSON → DTO` decoders think in positions;
  * every `DTO → domain` conversion instead has to name a [[com.worxbend.codeberg4s.JsonPath]], so that rule 5 of
  * [[WireConventions]] can report `$[7].sha` rather than `$`. The path-shaped overload turns the position into that
  * path segment, which is the whole of what the per-group helpers used to do.
  */
private[codeberg4s] object ArrayElements:

  /** Converts every element in order, answering the '''first''' failure or all of the converted values.
    *
    * Written as a loop over a `Vector.newBuilder` rather than as a fold over `Either`, because the fold cost one tuple,
    * one `Either` and one whole-vector copy per element and kept walking the rest of the array after it already knew
    * the answer. The builder is local and never escapes, so the mutation is not observable.
    *
    * @param values
    *   the elements, in the order the server sent them
    * @param one
    *   converts a single element, given the element and its zero-based position
    */
  def convert[D, A](values: Vector[D])(one: (D, Int) => Either[DecodeFailure, A]): Either[DecodeFailure, Vector[A]] =
    val converted = Vector.newBuilder[A]
    converted.sizeHint(values.size)

    @tailrec
    def loop(index: Int): Either[DecodeFailure, Vector[A]] =
      if index < values.size then
        one(values(index), index) match
          case Left(failure)  => Left(failure)
          case Right(element) =>
            converted.addOne(element)
            loop(index + 1)
      else Right(converted.result())

    loop(0)

  /** Converts every element in order, giving each one its own path inside the response document.
    *
    * The same first-failure-wins contract as the overload above; the only addition is the index segment, so a failure
    * inside the third element of a `labels` array reads `$.labels[2].name`.
    *
    * @param at
    *   the path of the '''array''' itself — [[com.worxbend.codeberg4s.JsonPath.Root]] for a response body that is an
    *   array, the field's path for an array nested in an object
    * @param values
    *   the decoded elements, in the order the server sent them
    * @param one
    *   converts a single element, given the element and the path that element sits at
    */
  def convert[D, A](at: JsonPath, values: Vector[D])(
      one: (D, JsonPath) => Either[DecodeFailure, A]
  ): Either[DecodeFailure, Vector[A]] =
    convert(values)((value, position) => one(value, at.index(position)))
