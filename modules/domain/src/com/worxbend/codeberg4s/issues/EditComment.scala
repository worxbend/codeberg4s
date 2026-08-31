package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

import java.time.Instant

/** What `PATCH /repos/{owner}/{repo}/issues/comments/{id}` is told — Forgejo's `EditIssueCommentOption`.
  *
  * '''Derived from `spec/swagger.v1.json`''', which marks `body` required and adds one optional `updated_at`. No golden
  * capture of this request exists; the response is a `Comment`, which `golden/issue/comments-list.json` covers.
  *
  * '''This replaces the whole body.''' There is no partial edit of a comment: whatever is sent becomes the comment. A
  * caller appending to an existing comment must read it first, and must accept that somebody else may have edited it in
  * between — which is exactly why the call is never retried, see [[com.worxbend.codeberg4s.issues.IssueCommentApi]].
  *
  * @param body
  *   the replacement text as Markdown source; the one thing Forgejo requires, validated by [[EditComment.of]]
  * @param updatedAt
  *   the timestamp to record the edit under. The spec notes it "needs admin or repository owner permission"; an
  *   ordinary token that sends it has it ignored rather than rejected
  */
final case class EditComment private[codeberg4s] (body: String, updatedAt: Option[Instant]):

  /** Records the edit as having happened at `moment`; see [[updatedAt]]. */
  def recordedAt(moment: Instant): EditComment = copy(updatedAt = Some(moment))

object EditComment:

  /** Builds the command, trimming the body and rejecting a blank one.
    *
    * Checked here rather than remotely for the reason [[CreateComment.of]] gives: a blank body costs a round trip and
    * comes back as an unhelpful `422`. Clearing a comment is not what this endpoint is for — deleting it is.
    *
    * @return
    *   the command, or a [[ValidationError]] on the `"body"` field
    */
  def of(body: String): Either[ValidationError, EditComment] =
    val trimmed = body.trim
    if trimmed.isEmpty then Left(ValidationError("body", "must not be blank"))
    else Right(EditComment(body = trimmed, updatedAt = None))
