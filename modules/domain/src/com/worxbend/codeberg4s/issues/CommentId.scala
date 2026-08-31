package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.{PositiveId, ValidationError}

/** The instance-wide identifier of a [[Comment]].
  *
  * Comment ids are global rather than per-issue: Forgejo addresses a single comment as
  * `/repos/{owner}/{repo}/issues/comments/{id}`, with no issue number in the path at all. The value is therefore
  * meaningless relative to an [[IssueNumber]] and must never be confused with one, which is what this type prevents.
  *
  * The endpoints that take a comment id — editing and deleting a comment — are not implemented by this wave; the type
  * exists because [[Comment]] carries it and because the wave that adds those endpoints must not invent a second
  * spelling for the same concept.
  */
opaque type CommentId = Long

object CommentId:

  /** Parses a comment id.
    *
    * Rejects anything below `1`.
    *
    * @return
    *   the id, or a [[ValidationError]] on the `"commentId"` field
    */
  def from(value: Long): Either[ValidationError, CommentId] =
    PositiveId.from("commentId", value)

  extension (id: CommentId)

    /** The id as a `Long`. */
    def value: Long = id
