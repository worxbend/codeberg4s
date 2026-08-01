package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.LifecycleState
import com.worxbend.codeberg4s.issues.Milestone
import com.worxbend.codeberg4s.issues.MilestoneId

/** Forgejo's `Milestone` model, field for field.
  *
  * All ten declared keys appear on all three elements of `golden/issue/milestones-list.json` and on the milestone
  * embedded in the first element of `golden/issue/search.json`. The embedded form is the same object, not a reduced
  * one, which is why there is a single DTO.
  *
  * `state` and `closed_at` stay separate here and are folded into one [[com.worxbend.codeberg4s.issues.LifecycleState]]
  * during conversion — that fold is the whole reason the domain model cannot express "open with a closing timestamp".
  */
final case class MilestoneDto(
    id: Option[Long],
    title: Option[String],
    description: Option[String],
    state: Option[String],
    openIssues: Option[Long],
    closedIssues: Option[Long],
    dueOn: Option[String],
    closedAt: Option[String],
    createdAt: Option[String],
    updatedAt: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * Three things are required. `id` addresses the milestone and goes through
    * [[com.worxbend.codeberg4s.issues.MilestoneId.from]]; `title` is what a milestone is; and `state` is required
    * because [[com.worxbend.codeberg4s.issues.LifecycleState]] has no third case to put an unknown one in — a milestone
    * that is neither open nor closed is not something a caller can act on, so it is reported as a failure at `$.state`
    * rather than guessed at.
    *
    * Both counts default to `0` when absent, which is the answer the instance gives for an empty milestone.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, Milestone] =
    for
      identifier <- Wire.validated(at, "id", id)(MilestoneId.from)
      text       <- Wire.required(at, "title", title)
      lifecycle  <- Wire.validated(at, "state", state)(value =>
                      LifecycleState.from(value, Timestamps.parseOptional(closedAt))
                    )
    yield Milestone(
      id               = identifier,
      title            = text,
      description      = description,
      state            = lifecycle,
      openIssueCount   = openIssues.getOrElse(0L),
      closedIssueCount = closedIssues.getOrElse(0L),
      dueOn            = Timestamps.parseOptional(dueOn),
      createdAt        = Timestamps.parseOptional(createdAt),
      updatedAt        = Timestamps.parseOptional(updatedAt),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, Milestone] =
    toDomainAt(JsonPath.Root)

object MilestoneDto:

  /** Reads a `Milestone` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given upickle.default.Reader[MilestoneDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object. Used by the reader above and by [[IssueDto]], which embeds a milestone. */
  def fromFields(fields: JsonFields): MilestoneDto =
    MilestoneDto(
      id           = fields.number("id"),
      title        = fields.text("title"),
      description  = fields.text("description"),
      state        = fields.text("state"),
      openIssues   = fields.number("open_issues"),
      closedIssues = fields.number("closed_issues"),
      dueOn        = fields.text("due_on"),
      closedAt     = fields.text("closed_at"),
      createdAt    = fields.text("created_at"),
      updatedAt    = fields.text("updated_at"),
    )

  /** Converts a decoded array of milestones, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[MilestoneDto]): Either[DecodeFailure, Vector[Milestone]] =
    WireElements.at(base, dtos)((dto, path) => dto.toDomainAt(path))
