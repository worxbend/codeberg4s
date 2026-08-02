package com.worxbend.codeberg4s.repositories.actions.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.repositories.actions.ActionRun
import com.worxbend.codeberg4s.repositories.actions.RunId
import com.worxbend.codeberg4s.repositories.wire.Elements
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.wire.UserDto

import scala.concurrent.duration.FiniteDuration

import java.util.concurrent.TimeUnit

/** Forgejo's `ActionRun` model, field for field.
  *
  * '''Derived from the spec, not from a capture'''; see [[ActionArtifactDto]] for what that costs and why it is said
  * out loud.
  *
  * ==Two spellings worth knowing about==
  *
  *   - `ScheduleID` is capitalised in the payload. It is the only key in this group that is not `snake_case`, because
  *     the Go field carries no `x-go-name` override, and reading it as `schedule_id` would silently lose it.
  *   - `duration` is a Go `time.Duration`, that is an integer '''nanosecond''' count, not seconds and not milliseconds.
  *     Reading it as either would be wrong by three or nine orders of magnitude with no symptom other than an
  *     implausible number.
  *
  * ==The one field that is deliberately not projected==
  *
  * `event_payload` is the raw webhook document that triggered the run — a whole `push` or `pull_request` payload,
  * routinely tens of kilobytes and occasionally far more. It is kept here so the DTO can be diffed against a captured
  * body without a mental exception list, and it is '''not''' carried into
  * [[com.worxbend.codeberg4s.repositories.actions.ActionRun]]: a domain model that holds an unparsed JSON document
  * offers a caller nothing that re-reading the webhook would not, and would put an unbounded string behind every run in
  * a listing.
  */
final case class ActionRunDto(
    id: Option[Long],
    indexInRepo: Option[Long],
    title: Option[String],
    status: Option[String],
    workflowId: Option[String],
    event: Option[String],
    triggerEvent: Option[String],
    eventPayload: Option[String],
    commitSha: Option[String],
    prettyRef: Option[String],
    htmlUrl: Option[String],
    repository: Option[ActionRepoRefDto],
    triggerUser: Option[UserDto],
    isForkPullRequest: Option[Boolean],
    needApproval: Option[Boolean],
    isRefDeleted: Option[Boolean],
    approvedBy: Option[Long],
    scheduleId: Option[Long],
    durationNanos: Option[Long],
    created: Option[String],
    started: Option[String],
    stopped: Option[String],
    updated: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Only `id` is required: it is what addresses the run, and a run that cannot address itself is of no use to a
    * caller. Everything else is optional or defaulted — the three flags become `false` when absent, and every value
    * that has to pass a smart constructor (`workflow_id`, `commit_sha`, the embedded repository) is '''dropped''' when
    * it fails rather than failing the run, because none of them is what the caller asked for.
    *
    * `trigger_user` is the one exception: a nested user that cannot be converted fails the conversion, at that user's
    * own path, because [[com.worxbend.codeberg4s.users.wire.UserDto]] owns that decision and disagreeing with it here
    * would mean two readings of the same payload.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, ActionRun] =
    for
      identifier <- Wire.validated(at, "id", id)(RunId.from)
      author     <- triggerUserAt(at)
    yield ActionRun(
      id                = identifier,
      indexInRepo       = indexInRepo,
      title             = title,
      status            = ActionWire.status(status),
      workflowId        = ActionWire.workflow(workflowId),
      event             = event,
      triggerEvent      = triggerEvent,
      commitSha         = ActionWire.commit(commitSha),
      prettyRef         = prettyRef,
      htmlUrl           = htmlUrl,
      repository        = repository.flatMap(_.toSlug),
      triggerUser       = author,
      isForkPullRequest = isForkPullRequest.getOrElse(false),
      needApproval      = needApproval.getOrElse(false),
      isRefDeleted      = isRefDeleted.getOrElse(false),
      approvedBy        = ActionWire.identifier(approvedBy),
      scheduleId        = ActionWire.identifier(scheduleId),
      duration          = ActionRunDto.duration(durationNanos),
      createdAt         = Timestamps.parseOptional(created),
      startedAt         = Timestamps.parseOptional(started),
      stoppedAt         = Timestamps.parseOptional(stopped),
      updatedAt         = Timestamps.parseOptional(updated),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, ActionRun] =
    toDomainAt(JsonPath.Root)

  private def triggerUserAt(at: JsonPath): Either[DecodeFailure, Option[User]] =
    triggerUser.fold(Right(None))(dto => dto.toDomainAt(at.field("trigger_user")).map(Some.apply))

object ActionRunDto:

  /** The wire key of the cron entry that started a scheduled run — capitalised, unlike every other key here. */
  private val ScheduleKey: String = "ScheduleID"

  /** Reads an `ActionRun` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[ActionRunDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, reusing the `fromFields` of every model it embeds. */
  def fromFields(fields: JsonFields): ActionRunDto =
    ActionRunDto(
      id                = fields.number("id"),
      indexInRepo       = fields.number("index_in_repo"),
      title             = fields.text("title"),
      status            = fields.text("status"),
      workflowId        = fields.text("workflow_id"),
      event             = fields.text("event"),
      triggerEvent      = fields.text("trigger_event"),
      eventPayload      = fields.text("event_payload"),
      commitSha         = fields.text("commit_sha"),
      prettyRef         = fields.text("prettyref"),
      htmlUrl           = fields.text("html_url"),
      repository        = fields.nested("repository").map(ActionRepoRefDto.fromFields),
      triggerUser       = fields.nested("trigger_user").map(UserDto.fromFields),
      isForkPullRequest = fields.boolean("is_fork_pull_request"),
      needApproval      = fields.boolean("need_approval"),
      isRefDeleted      = fields.boolean("is_ref_deleted"),
      approvedBy        = fields.number("approved_by"),
      scheduleId        = fields.number(ScheduleKey),
      durationNanos     = fields.number("duration"),
      created           = fields.text("created"),
      started           = fields.text("started"),
      stopped           = fields.text("stopped"),
      updated           = fields.text("updated"),
    )

  /** Converts a decoded array of runs, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[ActionRunDto]): Either[DecodeFailure, Vector[ActionRun]] =
    Elements.convert(base, dtos)((dto, path) => dto.toDomainAt(path))

  /** A Go `time.Duration` as a [[scala.concurrent.duration.FiniteDuration]].
    *
    * Zero is kept, because a run that has not started has genuinely taken no time and reporting that as "the instance
    * did not say" would be a different claim. A negative value is dropped: no elapsed time is negative, so such a value
    * is a payload this library has no reading of.
    */
  private def duration(nanos: Option[Long]): Option[FiniteDuration] =
    nanos.filter(_ >= 0L).map(value => FiniteDuration(value, TimeUnit.NANOSECONDS))
