package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.PathSegment
import com.worxbend.codeberg4s.ValidationError

/** The name of a Git tag — `v16.0.2` on `golden/repository/tags-list.json`.
  *
  * A tag name is a ref name, so it obeys the same rules as [[BranchName]]: it may contain `/` (`release/2026-08`), and
  * Forgejo puts it in a request path (`/repos/{owner}/{repo}/tags/{tag}`, `/releases/tags/{tag}`). It is validated and
  * decomposed the same way, for the same reason.
  */
opaque type TagName = String

object TagName:

  /** Parses a tag name.
    *
    * Trims surrounding whitespace. Rejects a blank name, a control character, a leading or trailing `/`, an empty
    * segment, and a `.` or `..` segment.
    *
    * @return
    *   the trimmed name, or a [[ValidationError]] on the `"tag"` field
    */
  def from(value: String): Either[ValidationError, TagName] =
    PathSegment.segmented("tag", value)

  extension (tag: TagName)

    /** The name as Forgejo spells it. */
    def value: String = tag

    /** The name split on `/`, for appending to a request path one segment at a time. */
    def segments: List[String] = tag.split('/').toList
