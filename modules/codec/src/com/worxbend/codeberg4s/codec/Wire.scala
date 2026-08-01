package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.core.DecodeFailure

/** The `wire → domain` half of a DTO conversion.
  *
  * Rule 2 of [[WireConventions]] makes every DTO field optional, so every `toDomain` has the same shape: name the
  * handful of fields the domain genuinely cannot do without, and report the first one that is missing. These two
  * helpers are that report, so the wording and the path construction are identical across models instead of being
  * re-invented per DTO.
  *
  * A path passed here is the path of the '''enclosing model''' inside the response document — `JsonPath.Root` for a
  * top-level object, `JsonPath.Root.index(2).field("owner")` for the owner of the third element of a list. Every DTO
  * exposes a `toDomainAt` taking that path precisely so a nested failure points at the right place.
  */
object Wire:

  /** Demands a field the domain cannot represent without.
    *
    * @param at
    *   the path of the model being converted
    * @param field
    *   the '''wire''' (snake_case) field name, so the message matches what a reader sees in the payload
    * @return
    *   the value, or a failure at `at.field(field)`
    */
  def required[A](at: JsonPath, field: String, value: Option[A]): Either[DecodeFailure, A] =
    value.toRight(DecodeFailure(at.field(field), s"required field '$field' is missing"))

  /** Demands a field and puts it through a domain smart constructor in one step.
    *
    * A value the constructor rejects is reported at the same path as a missing one, because a caller reacts to both the
    * same way: the payload cannot be turned into the model. The [[com.worxbend.codeberg4s.ValidationError]]'s own
    * message is preserved so the reason survives.
    */
  def validated[A, B](at: JsonPath, field: String, value: Option[A])(
      construct: A => Either[ValidationError, B]
  ): Either[DecodeFailure, B] =
    for
      present   <- required(at, field, value)
      validated <- construct(present).left.map(error => DecodeFailure(at.field(field), error.message))
    yield validated
