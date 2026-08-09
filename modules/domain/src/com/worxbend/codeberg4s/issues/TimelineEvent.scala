package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.users.User

import java.time.Instant

/** One entry in an issue's timeline: a comment, or any of the two dozen things Forgejo records alongside comments — a
  * label added, a milestone changed, a title edited, a reference made from another issue.
  *
  * '''Derived from `spec/swagger.v1.json`''' — Forgejo's `TimelineComment`. No golden capture exists;
  * `golden/issue/comments-list.json` is the plain comment endpoint, which is a different, much smaller model.
  *
  * ==Why this is one flat model and not an enum==
  *
  * `docs/HAZARDS.md` §3 is emphatic that a payload with per-case fields belongs in a Scala 3 enum, and it names exactly
  * one endpoint where the spec documents such a union. This is not that endpoint. Forgejo declares a single object with
  * thirty optional properties and a `type` string; which properties are populated for which value of `type` is
  * '''nowhere in the spec''', and the set of values is not enumerated either. Inventing an enum here would mean
  * inventing the discriminator mapping, and a `type` this library had not heard of would then have nowhere to go. So
  * the model is the object the spec declares, [[eventType]] is the raw discriminator, and a caller who cares matches on
  * it and reads the fields that value populates.
  *
  * ==One field of the wire model is dropped==
  *
  * `assignee_team` is Forgejo's `Team`, which `docs/LEDGER.md` gives to the organisation group and which this group
  * must not fork. Its absence costs a caller the team half of a team-assignment event; the account half arrives on
  * [[assignee]] as usual, and the DTO ignores the key rather than failing on it.
  *
  * @param id
  *   the identifier of the underlying comment row — the same numbering as [[Comment.id]], which is why it is a
  *   [[CommentId]] and not a type of its own
  * @param eventType
  *   Forgejo's discriminator, verbatim and unmapped; see above for why it is a `String` and why it is optional
  * @param body
  *   the comment text, for the entries that have one
  * @param author
  *   who caused the event
  * @param assignee
  *   the account assigned or unassigned; read together with [[removedAssignee]] to know which happened
  * @param removedAssignee
  *   whether the assignment event removed rather than added; `false` when the instance did not say
  * @param label
  *   the label added or removed
  * @param milestone
  *   the milestone the issue was moved into
  * @param oldMilestone
  *   the milestone it was moved out of
  * @param refIssue
  *   the issue that referenced this one
  * @param refComment
  *   the comment the reference was made from
  * @param dependentIssue
  *   the issue added to or removed from this one's dependencies
  * @param resolvedBy
  *   who marked a review conversation resolved
  * @param trackedTime
  *   the time entry a timetracking event recorded
  */
final case class TimelineEvent private[codeberg4s] (
    id: CommentId,
    eventType: Option[String],
    body: Option[String],
    author: Option[User],
    assignee: Option[User],
    removedAssignee: Boolean,
    label: Option[Label],
    milestone: Option[Milestone],
    oldMilestone: Option[Milestone],
    oldTitle: Option[String],
    newTitle: Option[String],
    oldRef: Option[String],
    newRef: Option[String],
    refAction: Option[String],
    refCommitSha: Option[String],
    refIssue: Option[Issue],
    refComment: Option[Comment],
    dependentIssue: Option[Issue],
    resolvedBy: Option[User],
    reviewId: Option[Long],
    projectId: Option[Long],
    oldProjectId: Option[Long],
    trackedTime: Option[TrackedTime],
    htmlUrl: Option[String],
    issueUrl: Option[String],
    pullRequestUrl: Option[String],
    createdAt: Option[Instant],
    updatedAt: Option[Instant],
)
