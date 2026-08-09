package com.worxbend.codeberg4s.repositories.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.ArrayElements
import com.worxbend.codeberg4s.core.DecodeFailure

/** Converting a JSON array of DTOs into domain values, with each failure reported at its own index.
  *
  * Rule 5 of [[com.worxbend.codeberg4s.codec.WireConventions]] says a `toDomain` reports the JSON path of whatever it
  * could not convert. For an array that means `$[7].sha` and not `$`, and getting there requires turning each element's
  * position into a path segment. Doing it once here is what stops every list-shaped model from growing its own copy —
  * and a copy that quietly forgot the index would be indistinguishable from one that did not, until someone tried to
  * debug a bad payload.
  *
  * The walk itself lives in [[com.worxbend.codeberg4s.codec.ArrayElements]]; this adds the path.
  *
  * Used from two sides: by the DTOs below for arrays nested inside a model — a commit's parents, a release's assets —
  * and by the client module for a response body that is an array at the top level.
  */
private[codeberg4s] object Elements:

  /** Converts every element, stopping at the first failure.
    *
    * One bad element fails the whole array, which is the same contract a bare list body already has: a caller asking
    * for a page of commits cannot act on "forty-nine of the fifty decoded".
    *
    * @param at
    *   the path of the array itself — [[com.worxbend.codeberg4s.JsonPath.Root]] for a response body that is an array,
    *   the field's path for an array nested in an object
    * @param dtos
    *   the decoded elements, in wire order
    * @param one
    *   converts a single element, given the path that element sits at
    */
  def convert[D, A](at: JsonPath, dtos: Vector[D])(
      one: (D, JsonPath) => Either[DecodeFailure, A]
  ): Either[DecodeFailure, Vector[A]] =
    ArrayElements.convert(dtos)((dto, position) => one(dto, at.index(position)))
