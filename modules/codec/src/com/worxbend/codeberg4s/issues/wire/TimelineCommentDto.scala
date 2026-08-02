package com.worxbend.codeberg4s.issues.wire

import com.worxbend.codeberg4s.JsonPath
import com.worxbend.codeberg4s.codec.JsonDecoder
import com.worxbend.codeberg4s.codec.JsonFields
import com.worxbend.codeberg4s.codec.Timestamps
import com.worxbend.codeberg4s.codec.Wire
import com.worxbend.codeberg4s.core.DecodeFailure
import com.worxbend.codeberg4s.issues.Comment
import com.worxbend.codeberg4s.issues.CommentId
import com.worxbend.codeberg4s.issues.Issue
import com.worxbend.codeberg4s.issues.Label
import com.worxbend.codeberg4s.issues.Milestone
import com.worxbend.codeberg4s.issues.TimelineEvent
import com.worxbend.codeberg4s.issues.TrackedTime
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.wire.UserDto

/** Forgejo's `TimelineComment` model — one element of `GET /repos/{owner}/{repo}/issues/{index}/timeline`.
  *
  * '''Derived from `spec/swagger.v1.json`, not from a captured response.''' `golden/issue/comments-list.json` is the
  * plain comment endpoint, which returns a different and much smaller model; nothing in the golden harvest covers the
  * timeline. Twenty-nine of the model's thirty declared properties are represented here.
  *
  * The thirtieth, `assignee_team`, is Forgejo's `Team`, which `docs/LEDGER.md` assigns to the organisation group.
  * Decoding it would mean this group inventing a second `Team` model that the organisation group would then have to
  * keep in step. [[com.worxbend.codeberg4s.codec.JsonFields]] ignores a key the DTO does not name, so a team-assignment
  * event still decodes — it simply arrives without the team.
  *
  * ==Deeply nested, and every nesting reports its own path==
  *
  * A timeline event can embed a whole [[IssueDto]] twice over, a [[CommentDto]], a [[LabelDto]], two [[MilestoneDto]]s,
  * three users and a [[TrackedTimeDto]]. Each is converted through its own `toDomainAt`, so a decoding failure says
  * `$[3].ref_issue.number` rather than `$[3]`.
  */
final case class TimelineCommentDto(
    id: Option[Long],
    commentType: Option[String],
    body: Option[String],
    user: Option[UserDto],
    assignee: Option[UserDto],
    removedAssignee: Option[Boolean],
    label: Option[LabelDto],
    milestone: Option[MilestoneDto],
    oldMilestone: Option[MilestoneDto],
    oldTitle: Option[String],
    newTitle: Option[String],
    oldRef: Option[String],
    newRef: Option[String],
    refAction: Option[String],
    refCommitSha: Option[String],
    refIssue: Option[IssueDto],
    refComment: Option[CommentDto],
    dependentIssue: Option[IssueDto],
    resolveDoer: Option[UserDto],
    reviewId: Option[Long],
    projectId: Option[Long],
    oldProjectId: Option[Long],
    trackedTime: Option[TrackedTimeDto],
    htmlUrl: Option[String],
    issueUrl: Option[String],
    pullRequestUrl: Option[String],
    createdAt: Option[String],
    updatedAt: Option[String],
):

  /** Converts to the domain, reporting failure paths relative to `at`.
    *
    * '''Only `id` is required''', and it goes through [[com.worxbend.codeberg4s.issues.CommentId.from]] because a
    * timeline entry is a comment row and shares that numbering.
    *
    * `type` is '''not''' required, deliberately. It is the discriminator a caller matches on, so demanding it is
    * tempting — but the timeline is a listing, Forgejo's event vocabulary grows, and failing an entire page because one
    * entry arrived without a type would cost a caller the twenty entries they could have read. It arrives as
    * [[com.worxbend.codeberg4s.issues.TimelineEvent.eventType]] and is `None` in that case.
    *
    * `removed_assignee` defaults to `false`, which is the reading that matches the field's own name: absent means the
    * assignment was not a removal.
    */
  def toDomainAt(at: JsonPath): Either[DecodeFailure, TimelineEvent] =
    for
      identifier <- Wire.validated(at, "id", id)(CommentId.from)
      actor      <- userAt(at, "user", user)
      assigned   <- userAt(at, "assignee", assignee)
      resolver   <- userAt(at, "resolve_doer", resolveDoer)
      tag        <- labelAt(at)
      target     <- milestoneAt(at, "milestone", milestone)
      previous   <- milestoneAt(at, "old_milestone", oldMilestone)
      referring  <- issueAt(at, "ref_issue", refIssue)
      dependent  <- issueAt(at, "dependent_issue", dependentIssue)
      quoted     <- commentAt(at)
      logged     <- trackedTimeAt(at)
    yield TimelineEvent(
      id              = identifier,
      eventType       = commentType,
      body            = body,
      author          = actor,
      assignee        = assigned,
      removedAssignee = removedAssignee.getOrElse(false),
      label           = tag,
      milestone       = target,
      oldMilestone    = previous,
      oldTitle        = oldTitle,
      newTitle        = newTitle,
      oldRef          = oldRef,
      newRef          = newRef,
      refAction       = refAction,
      refCommitSha    = refCommitSha,
      refIssue        = referring,
      refComment      = quoted,
      dependentIssue  = dependent,
      resolvedBy      = resolver,
      reviewId        = reviewId,
      projectId       = projectId,
      oldProjectId    = oldProjectId,
      trackedTime     = logged,
      htmlUrl         = htmlUrl,
      issueUrl        = issueUrl,
      pullRequestUrl  = pullRequestUrl,
      createdAt       = Timestamps.parseOptional(createdAt),
      updatedAt       = Timestamps.parseOptional(updatedAt),
    )

  /** [[toDomainAt]] for a payload that is the whole response body. */
  def toDomain: Either[DecodeFailure, TimelineEvent] =
    toDomainAt(JsonPath.Root)

  private def userAt(at: JsonPath, field: String, dto: Option[UserDto]): Either[DecodeFailure, Option[User]] =
    dto.fold(Right(None))(value => value.toDomainAt(at.field(field)).map(Some.apply))

  private def labelAt(at: JsonPath): Either[DecodeFailure, Option[Label]] =
    label.fold(Right(None))(dto => dto.toDomainAt(at.field("label")).map(Some.apply))

  private def milestoneAt(
      at: JsonPath,
      field: String,
      dto: Option[MilestoneDto],
  ): Either[DecodeFailure, Option[Milestone]] =
    dto.fold(Right(None))(value => value.toDomainAt(at.field(field)).map(Some.apply))

  private def issueAt(at: JsonPath, field: String, dto: Option[IssueDto]): Either[DecodeFailure, Option[Issue]] =
    dto.fold(Right(None))(value => value.toDomainAt(at.field(field)).map(Some.apply))

  private def commentAt(at: JsonPath): Either[DecodeFailure, Option[Comment]] =
    refComment.fold(Right(None))(dto => dto.toDomainAt(at.field("ref_comment")).map(Some.apply))

  private def trackedTimeAt(at: JsonPath): Either[DecodeFailure, Option[TrackedTime]] =
    trackedTime.fold(Right(None))(dto => dto.toDomainAt(at.field("tracked_time")).map(Some.apply))

object TimelineCommentDto:

  /** Reads a `TimelineComment` object. Absent and `null` are the same thing for every field; see
    * [[com.worxbend.codeberg4s.codec.JsonFields]].
    */
  given JsonDecoder[TimelineCommentDto] =
    JsonFields.reader(fromFields)

  /** Projects an already-decoded object, reusing the `fromFields` of every model it embeds so that no field spelling is
    * written twice.
    */
  def fromFields(fields: JsonFields): TimelineCommentDto =
    TimelineCommentDto(
      id              = fields.number("id"),
      commentType     = fields.text("type"),
      body            = fields.text("body"),
      user            = fields.nested("user").map(UserDto.fromFields),
      assignee        = fields.nested("assignee").map(UserDto.fromFields),
      removedAssignee = fields.boolean("removed_assignee"),
      label           = fields.nested("label").map(LabelDto.fromFields),
      milestone       = fields.nested("milestone").map(MilestoneDto.fromFields),
      oldMilestone    = fields.nested("old_milestone").map(MilestoneDto.fromFields),
      oldTitle        = fields.text("old_title"),
      newTitle        = fields.text("new_title"),
      oldRef          = fields.text("old_ref"),
      newRef          = fields.text("new_ref"),
      refAction       = fields.text("ref_action"),
      refCommitSha    = fields.text("ref_commit_sha"),
      refIssue        = fields.nested("ref_issue").map(IssueDto.fromFields),
      refComment      = fields.nested("ref_comment").map(CommentDto.fromFields),
      dependentIssue  = fields.nested("dependent_issue").map(IssueDto.fromFields),
      resolveDoer     = fields.nested("resolve_doer").map(UserDto.fromFields),
      reviewId        = fields.number("review_id"),
      projectId       = fields.number("project_id"),
      oldProjectId    = fields.number("old_project_id"),
      trackedTime     = fields.nested("tracked_time").map(TrackedTimeDto.fromFields),
      htmlUrl         = fields.text("html_url"),
      issueUrl        = fields.text("issue_url"),
      pullRequestUrl  = fields.text("pull_request_url"),
      createdAt       = fields.text("created_at"),
      updatedAt       = fields.text("updated_at"),
    )

  /** Converts a decoded array of timeline entries, reporting the position of whichever element failed. */
  def toDomainAll(base: JsonPath, dtos: Vector[TimelineCommentDto]): Either[DecodeFailure, Vector[TimelineEvent]] =
    WireElements.at(base, dtos)((dto, path) => dto.toDomainAt(path))
