package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.users.User

import java.time.Instant

/** A comment on an issue or on a pull request.
  *
  * Owned by this group per `docs/LEDGER.md`. Forgejo has one `Comment` model for both, and tells them apart only by
  * which of [[issueUrl]] and [[pullRequestUrl]] is populated — on `golden/issue/comments-list.json` the pull-request
  * URL is `""`, which this model reads as absent. Nothing here interprets that difference; the pull-request wave layers
  * its own meaning on top rather than forking the model.
  *
  * A comment is the only thing in this group whose author may genuinely be missing. Issues and comments imported from
  * another forge keep the original author's display name in [[originalAuthor]] and carry a placeholder account, so
  * [[author]] is an `Option` and [[originalAuthor]] is what a renderer falls back to.
  *
  * @param id
  *   the instance-wide identifier; comments are addressed without their issue, see [[CommentId]]
  * @param body
  *   the comment text as Markdown source, absent when the comment is empty
  * @param author
  *   the account that wrote it, absent for imported content
  * @param originalAuthor
  *   the display name recorded by an import, absent for a comment written on this instance
  * @param issueUrl
  *   the API URL of the issue this comment belongs to
  * @param pullRequestUrl
  *   the API URL of the pull request this comment belongs to, absent when the comment is on a plain issue
  */
final case class Comment(
    id: CommentId,
    body: Option[String],
    author: Option[User],
    originalAuthor: Option[String],
    htmlUrl: Option[String],
    issueUrl: Option[String],
    pullRequestUrl: Option[String],
    createdAt: Option[Instant],
    updatedAt: Option[Instant],
)
