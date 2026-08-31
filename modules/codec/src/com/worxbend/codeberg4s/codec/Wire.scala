package com.worxbend.codeberg4s.codec

import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.{JsonPath, ValidationError}

/** The `wire → domain` half of a DTO conversion.
  *
  * Rule 2 of [[WireConventions]] makes every DTO field optional, so every `toDomain` has the same shape: name the
  * handful of fields the domain genuinely cannot do without, and report the first one that is missing. These helpers
  * are that report, so the wording and the path construction are identical across models instead of being re-invented
  * per DTO.
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

  /** Runs an optional field through a smart constructor, keeping absence and rejecting a bad value.
    *
    * The third combination [[required]] and [[validated]] leave open, and the one a field that is '''legitimately
    * absent''' needs: a pull request's `merge_commit_sha` is missing after a fast-forward merge, and a review's
    * `commit_id` pins no commit until the review is submitted. Absence there is information. A value that is present
    * but that the constructor rejects is not — it means the payload is not what it claims to be — so it fails rather
    * than quietly becoming `None`.
    *
    * @param at
    *   the path of the model being converted
    * @param field
    *   the '''wire''' (snake_case) field name, so the message matches what a reader sees in the payload
    * @return
    *   `None` when the field was absent, the constructed value when it was present and valid, and a failure at
    *   `at.field(field)` when it was present and rejected
    */
  def optional[A, B](at: JsonPath, field: String, value: Option[A])(
      construct: A => Either[ValidationError, B]
  ): Either[DecodeFailure, Option[B]] =
    value match
      case None          => Right(None)
      case Some(present) =>
        construct(present) match
          case Right(built) => Right(Some(built))
          case Left(error)  => Left(DecodeFailure(at.field(field), error.message))

  /** Converts an optional nested DTO, rooting its own failures at the field it was read from.
    *
    * Rule 2 makes a nested model optional too, so `owner`, `milestone` and `repository` all arrive as an `Option[…Dto]`
    * whose conversion is itself fallible. Absence stays absence; a DTO that is present is converted at
    * `at.field(field)` so a failure inside it reads `$.milestone.title` rather than `$.title`.
    *
    * `convert` is the nested DTO's own `toDomainAt`, passed as `_.toDomainAt(_)` — taking it as a function rather than
    * demanding a common trait keeps the DTOs plain case classes.
    *
    * @param at
    *   the path of the '''enclosing''' model being converted
    * @param field
    *   the '''wire''' (snake_case) field name the nested model was read from
    * @return
    *   `None` when the field was absent, otherwise the converted model or the nested failure
    */
  def nested[A, B](at: JsonPath, field: String, dto: Option[A])(
      convert: (A, JsonPath) => Either[DecodeFailure, B]
  ): Either[DecodeFailure, Option[B]] =
    dto match
      case None        => Right(None)
      case Some(value) => convert(value, at.field(field)).map(Some.apply)
