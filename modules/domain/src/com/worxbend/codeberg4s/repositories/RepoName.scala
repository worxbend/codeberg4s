package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.ValidationError

/** The repository half of `owner/name`.
  *
  * Validated exactly like [[Owner]], as a URI path segment, so it cannot forge a path. See [[PathSegment]].
  */
opaque type RepoName = String

object RepoName:

  /** Parses a repository name.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `/`, and a value containing a
    * control character.
    *
    * @return
    *   the trimmed name, or a [[ValidationError]] on the `"repoName"` field
    */
  def from(value: String): Either[ValidationError, RepoName] =
    PathSegment.from("repoName", value)

  extension (name: RepoName)

    /** The repository name as a string, ready to be used as one path segment. */
    def value: String = name
