package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.{PositiveId, ValidationError}

/** The instance-wide identifier of an [[IssueAttachment]] — the `{attachment_id}` of
  * `/repos/{owner}/{repo}/issues/{index}/assets/{attachment_id}` and of the matching comment route.
  *
  * Distinct from [[CommentId]] and from [[IssueNumber]] even though all three are `int64` and all three land in the
  * same request path: `/issues/12/assets/12` is a perfectly well-formed URL whichever way round the two numbers go, so
  * a transposition is a `404` at best and a successful read of the wrong attachment at worst. That is the confusion
  * this type exists to prevent, exactly as [[com.worxbend.codeberg4s.PositiveId]] describes.
  *
  * ==Error contract==
  *
  * Construction produces [[ValidationError]] on the `"attachmentId"` field and nothing else; it performs no I/O.
  */
opaque type AttachmentId = Long

object AttachmentId:

  /** Parses an attachment identifier.
    *
    * Rejects anything below `1`.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"attachmentId"` field
    */
  def from(value: Long): Either[ValidationError, AttachmentId] =
    PositiveId.from("attachmentId", value)

  extension (id: AttachmentId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id
