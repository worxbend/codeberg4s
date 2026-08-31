package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

/** What `POST /repos/{owner}/{repo}/issues/{index}/comments` is told.
  *
  * A one-field command type rather than a bare `String` parameter, for two reasons that outlive its current size:
  * `createComment(owner, name, number, "…")` gives the reader nothing to check the fourth argument against, and
  * Forgejo's `CreateIssueCommentOption` already carries a second field (`updated_at`, for imports) that a later wave
  * will want without changing the method signature.
  *
  * @param body
  *   the comment text as Markdown source; the only thing Forgejo requires, validated by [[CreateComment.of]]
  */
final case class CreateComment private[codeberg4s] (body: String)

object CreateComment:

  /** Builds the command, trimming the body and rejecting a blank one.
    *
    * Checked here rather than remotely for the same reason as [[CreateIssue.of]]: a blank body costs a round trip and
    * comes back as an unhelpful `422`.
    *
    * @return
    *   the command, or a [[ValidationError]] on the `"body"` field
    */
  def of(body: String): Either[ValidationError, CreateComment] =
    val trimmed = body.trim
    if trimmed.isEmpty then Left(ValidationError("body", "must not be blank"))
    else Right(CreateComment(trimmed))
