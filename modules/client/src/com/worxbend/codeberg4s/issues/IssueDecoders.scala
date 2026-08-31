package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.client.WireDecode
import com.worxbend.codeberg4s.codec.ArrayElements
import com.worxbend.codeberg4s.codec.Json
import com.worxbend.codeberg4s.core.Decode
import com.worxbend.codeberg4s.core.ResponseBody
import com.worxbend.codeberg4s.issues.wire.AttachmentDto
import com.worxbend.codeberg4s.issues.wire.CommentDto
import com.worxbend.codeberg4s.issues.wire.IssueDeadlineDto
import com.worxbend.codeberg4s.issues.wire.IssueDto
import com.worxbend.codeberg4s.issues.wire.IssueSubscriptionDto
import com.worxbend.codeberg4s.issues.wire.LabelDto
import com.worxbend.codeberg4s.issues.wire.MilestoneDto
import com.worxbend.codeberg4s.issues.wire.ReactionDto
import com.worxbend.codeberg4s.issues.wire.TimelineCommentDto
import com.worxbend.codeberg4s.issues.wire.TrackedTimeDto
import com.worxbend.codeberg4s.users.User
import com.worxbend.codeberg4s.users.wire.UserDto

/** Every response shape the issue group's sub-APIs can receive, decoded once and shared.
  *
  * The instances are stateless and immutable, so they are built as `val`s rather than per call, exactly as
  * [[com.worxbend.codeberg4s.repositories.actions.RepositoryActionDecoders]] does. [[IssueApi]] and every sub-API read
  * their decoders from here, so a change to how one shape is decoded lands everywhere at once.
  *
  * ==Every listing in this group is a bare array==
  *
  * There is no `{ok, data}` envelope anywhere here, '''including on the search endpoint''' — which is worth saying,
  * because `GET /repos/search` and `GET /users/search` do use one and `GET /repos/issues/search` does not.
  * `golden/issue/search.json` is a capture of that endpoint and its top level is `[`, not `{`; `docs/HAZARDS.md` §3
  * records the two envelope endpoints by name and this is not one of them. Decoding it as an envelope fails.
  */
private[issues] object IssueDecoders:

  /** One issue object, as the blocking and dependency writes return it. */
  val issue: Decode[Issue] =
    WireDecode.single(Json.decoder[IssueDto])(_.toDomain)

  /** A bare array of issue objects — the cross-repository search, the blocks listing and the dependency listing. */
  val issues: Decode[Vector[Issue]] =
    WireDecode.vector(Json.decoder[Vector[IssueDto]])(IssueDto.toDomainAll)

  /** One comment object on an endpoint that always sends a body — posting a comment, where `201` is the only success.
    *
    * The difference from [[comment]] is only what an empty body means: nothing on those endpoints declares `204`, so a
    * blank body is a malformed response and is reported as one rather than being read as "no comment".
    */
  val presentComment: Decode[Comment] =
    WireDecode.single(Json.decoder[CommentDto])(_.toDomain)

  /** One comment object, on the endpoints where an empty body is also a success.
    *
    * '''An empty body is a success here, not a decoding failure.''' Both the single-comment read and the comment edit
    * declare `204` alongside `200` in `spec/swagger.v1.json`, and Forgejo answers `204` when the row behind the id is
    * not a user-written comment but one of the system entries the timeline is made of. Both statuses are `2xx`, so both
    * reach this decoder; treating the empty body as `None` is what lets one method serve both, rather than the caller
    * having to know which status they will get before they call. Anything non-blank is decoded as a comment, so an
    * instance that pads a `204` with whitespace is still understood and one that answers with a malformed body still
    * fails. See [[com.worxbend.codeberg4s.repositories.actions.RepositoryActionDecoders]] for the same shape.
    */
  val comment: Decode[Option[Comment]] =
    (body: ResponseBody) => if body.isBlank then Right(None) else presentComment(body).map(Some.apply)

  /** A bare array of comment objects, as the repository-wide comment listing returns it. */
  val comments: Decode[Vector[Comment]] =
    WireDecode.vector(Json.decoder[Vector[CommentDto]])(CommentDto.toDomainAll)

  /** One label object. */
  val label: Decode[Label] =
    WireDecode.single(Json.decoder[LabelDto])(_.toDomain)

  /** A bare array of label objects, as the per-issue label calls return it. */
  val labels: Decode[Vector[Label]] =
    WireDecode.vector(Json.decoder[Vector[LabelDto]])(LabelDto.toDomainAll)

  /** One milestone object. */
  val milestone: Decode[Milestone] =
    WireDecode.single(Json.decoder[MilestoneDto])(_.toDomain)

  /** A bare array of milestone objects, as the repository's milestone listing returns it. */
  val milestones: Decode[Vector[Milestone]] =
    WireDecode.vector(Json.decoder[Vector[MilestoneDto]])(MilestoneDto.toDomainAll)

  /** One attachment object. */
  val attachment: Decode[IssueAttachment] =
    WireDecode.single(Json.decoder[AttachmentDto])(_.toDomain)

  /** A bare array of attachment objects. */
  val attachments: Decode[Vector[IssueAttachment]] =
    WireDecode.vector(Json.decoder[Vector[AttachmentDto]])(AttachmentDto.toDomainAll)

  /** One reaction object, as adding a reaction returns it. */
  val reaction: Decode[Reaction] =
    WireDecode.single(Json.decoder[ReactionDto])(_.toDomain)

  /** A bare array of reaction objects — one element per account per emoji, never a tally. */
  val reactions: Decode[Vector[Reaction]] =
    WireDecode.vector(Json.decoder[Vector[ReactionDto]])(ReactionDto.toDomainAll)

  /** The one-key object the deadline endpoint answers. */
  val deadline: Decode[IssueDeadline] =
    WireDecode.single(Json.decoder[IssueDeadlineDto])(_.toDomain)

  /** The `WatchInfo` object the subscription check answers. */
  val subscription: Decode[IssueSubscription] =
    WireDecode.single(Json.decoder[IssueSubscriptionDto])(_.toDomain)

  /** A bare array of user objects, as the subscriber listing returns it.
    *
    * `UserDto` has no `toDomainAll` of its own — it is not this group's model — so the element fold is spelled out
    * here, exactly as [[com.worxbend.codeberg4s.organizations.OrganizationDecoders]] spells it out for the member
    * listings. One bad element still fails the page, and reports its position.
    */
  val users: Decode[Vector[User]] =
    WireDecode.vector(Json.decoder[Vector[UserDto]]): (at, dtos) =>
      ArrayElements.convert(at, dtos)(_.toDomainAt(_))

  /** One tracked-time entry, as adding time returns it. */
  val trackedTime: Decode[TrackedTime] =
    WireDecode.single(Json.decoder[TrackedTimeDto])(_.toDomain)

  /** A bare array of tracked-time entries. */
  val trackedTimes: Decode[Vector[TrackedTime]] =
    WireDecode.vector(Json.decoder[Vector[TrackedTimeDto]])(TrackedTimeDto.toDomainAll)

  /** A bare array of timeline entries. */
  val timeline: Decode[Vector[TimelineEvent]] =
    WireDecode.vector(Json.decoder[Vector[TimelineCommentDto]])(TimelineCommentDto.toDomainAll)
