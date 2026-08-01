package com.worxbend.codeberg4s.pulls.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.core.DecodeFailure

/** The one conversion shape [[com.worxbend.codeberg4s.codec.Wire]] does not already cover.
  *
  * `Wire.required` demands a field, and `Wire.validated` demands it and runs it through a smart constructor. This group
  * needs the third combination four times over — `base.sha`, `head.sha`, `merge_base`, `merge_commit_sha` and a
  * review's `commit_id` are all fields that are '''legitimately absent''' and, when present, must be a real
  * [[com.worxbend.codeberg4s.repositories.CommitSha]] rather than quietly dropped.
  *
  * The distinction matters: absence is information (a fast-forward merge produces no merge commit, a review request
  * pins no commit), whereas a present-but-unparseable object id means the payload is not what it claims to be, and
  * silently answering `None` there would hide it.
  */
private[pulls] object PullWire:

  /** Runs an optional field through a smart constructor, keeping absence and rejecting a bad value.
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
