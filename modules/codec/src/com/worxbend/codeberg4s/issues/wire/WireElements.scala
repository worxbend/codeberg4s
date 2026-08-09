package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.ArrayElements
import com.worxbend.codeberg4s.core.DecodeFailure

/** Converts the elements of a decoded JSON array, reporting the position of whichever one failed.
  *
  * [[com.worxbend.codeberg4s.codec.Wire]] does this for a field; there is no equivalent for an element, and this group
  * needs one five times over — four list endpoints plus the `labels` and `assignees` arrays nested inside every issue.
  * Writing the path construction once means a decoding failure says `$[2].id` or `$.labels[1].name` rather than `$`,
  * and means the five call sites cannot drift into disagreeing about whether one bad element fails the page. The walk
  * over the elements is [[com.worxbend.codeberg4s.codec.ArrayElements]]'s.
  *
  * '''One bad element fails the whole conversion.''' That is the same contract [[com.worxbend.codeberg4s.codec.Json]]
  * gives a list body, and the same one [[com.worxbend.codeberg4s.repositories.wire.RepositoryDto]] gives a nested
  * model: a caller that silently received nineteen of twenty issues would have no way to notice.
  *
  * Internal to this group's wire package, and a candidate to move into `com.worxbend.codeberg4s.codec` once a second
  * endpoint group decodes a list.
  */
private[codeberg4s] object WireElements:

  /** Converts every element of `dtos`, stopping at the first failure.
    *
    * @param base
    *   the path of the '''array''' inside the response document — [[com.worxbend.codeberg4s.JsonPath.Root]] for a body
    *   that is the array itself, `at.field("labels")` for an array nested in an object
    * @param dtos
    *   the already-decoded elements, in the order the server returned them
    * @param convert
    *   an element's own conversion, given the element's path
    */
  def at[D, A](base: JsonPath, dtos: Vector[D])(
      convert: (D, JsonPath) => Either[DecodeFailure, A]
  ): Either[DecodeFailure, Vector[A]] =
    ArrayElements.convert(dtos)((dto, position) => convert(dto, base.index(position)))
