package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.{JsonDecoder, JsonFields, Timestamps, Wire, WireModel}
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.{TrackedTime, TrackedTimeId}

import scala.concurrent.duration.{DurationLong, FiniteDuration}

/** Forgejo's `TrackedTime` model, field for field.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response''': every timetracking endpoint requires a
  * token and the harvest was anonymous. All seven declared properties are represented; two of them, `issue_id` and
  * `user_id`, are kept here and dropped in conversion because the spec marks both "deprecated (only for backwards
  * compatibility)" and both duplicate something the domain already has.
  *
  * ==Seconds become a duration here, and only here==
  *
  * `time` is an `int64` the spec documents as "Time in seconds". The conversion to
  * `scala.concurrent.duration.FiniteDuration` happens at this boundary so that the unit exists in exactly one place;
  * see [[com.worxbend.codeberg4s.issues.TrackedTime.spent]] for why the domain does not keep the raw number.
  */
final case class TrackedTimeDto(
    id: Option[Long],
    issue: Option[IssueDto],
    issueId: Option[Long],
    time: Option[Long],
    userId: Option[Long],
    userName: Option[String],
    created: Option[String],
) extends WireModel[TrackedTime]:

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Two things are required. `id` addresses the entry — it is what `DELETE …/times/{id}` takes — and goes through
    * [[com.worxbend.codeberg4s.issues.TrackedTimeId.from]]. `time` is required because a time entry that does not say
    * how long is not an entry; an absent one is reported at `$.time` rather than becoming a silent zero, which would
    * quietly corrupt any total a caller computed.
    *
    * '''A negative `time` is accepted.''' Forgejo records a correction as a negative entry, so refusing one would lose
    * a legitimate row; the resulting [[scala.concurrent.duration.FiniteDuration]] is negative, and a caller summing
    * entries gets the right answer.
    *
    * A failure inside `issue` is reported at `$.issue`, not at the entry's own path.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, TrackedTime] =
    for
      identifier <- Wire.validated(at, "id", id)(TrackedTimeId.from)
      seconds    <- Wire.required(at, "time", time)
      target     <- Wire.nested(at, "issue", issue)(_.toDomainAt(_))
    yield TrackedTime(
      id        = identifier,
      issue     = target,
      spent     = TrackedTimeDto.asDuration(seconds),
      userName  = userName,
      createdAt = Timestamps.parseOptional(created),
    )

object TrackedTimeDto:

  /** Reads a `TrackedTime` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[TrackedTimeDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, reusing [[IssueDto.fromFields]] for the embedded issue. */
  def fromFields(fields: JsonFields): TrackedTimeDto =
    TrackedTimeDto(
      id       = fields.number("id"),
      issue    = fields.nested("issue").map(IssueDto.fromFields),
      issueId  = fields.number("issue_id"),
      time     = fields.number("time"),
      userId   = fields.number("user_id"),
      userName = fields.text("user_name"),
      created  = fields.text("created"),
    )

  /** The wire's seconds as a duration. One line, in one place, so the unit is never re-derived. */
  def asDuration(seconds: Long): FiniteDuration =
    seconds.seconds
