package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.IssueDeadline

/** Forgejo's `IssueDeadline` model — the one-key body `POST /repos/{owner}/{repo}/issues/{index}/deadline` answers.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response''': setting a deadline requires a token and the
  * harvest was anonymous. The model declares exactly one property, `due_date`, and marks it optional.
  *
  * `due_date` is parsed by [[com.worxbend.codeberg4s.codec.Timestamps]], which folds Forgejo's zero-time and Unix-epoch
  * sentinels into absence — so an instance that answers a cleared deadline as `"0001-01-01T00:00:00Z"` rather than as
  * `null` still yields `None`.
  */
final case class IssueDeadlineDto(dueDate: Option[String]):

  /** Converts to the domain.
    *
    * '''Cannot fail, and there is no `toDomainAt`.''' Nothing is required — the endpoint's '''request''' model marks
    * `due_date` required, its response model does not, and an instance that echoes nothing is still telling the caller
    * the request succeeded. No endpoint returns this object inside an array either, so there is no nested path for a
    * failure to be reported at. The `Either` is kept so the DTO composes with
    * [[com.worxbend.codeberg4s.client.WireDecode]] exactly like every other one.
    */
  def toDomain: Either[DecodeFailure, IssueDeadline] =
    Right(IssueDeadline(dueDate = Timestamps.parseOptional(dueDate)))

object IssueDeadlineDto:

  /** Reads an `IssueDeadline` object. Absent and `null` are the same thing; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[IssueDeadlineDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. */
  def fromFields(fields: JsonFields): IssueDeadlineDto =
    IssueDeadlineDto(dueDate = fields.text("due_date"))
