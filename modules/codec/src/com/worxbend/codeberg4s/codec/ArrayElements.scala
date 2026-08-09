package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.core.DecodeFailure

import scala.annotation.tailrec

/** Applies a conversion that can fail to every element of an array, stopping at the first element that fails.
  *
  * Four places in this module wanted exactly this and each had grown its own copy: [[JsonDecoder.arrayOf]] and
  * [[JsonDecoder.all]] for `JSON → DTO`, and the two `wire` helpers
  * ([[com.worxbend.codeberg4s.repositories.wire.Elements]] and [[com.worxbend.codeberg4s.issues.wire.WireElements]])
  * for `DTO → domain`. The copies agreed on the contract, which is the only reason they were survivable; they are here
  * once so they cannot start disagreeing.
  *
  * '''One bad element fails the whole array.''' A listing that silently dropped a malformed element would under-report,
  * and a caller cannot tell an under-report from a short page.
  *
  * The conversion receives each element's zero-based position, because three of the four callers turn it into a
  * [[com.worxbend.codeberg4s.JsonPath]] segment so a failure reads `$[7].sha` rather than `$`.
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
