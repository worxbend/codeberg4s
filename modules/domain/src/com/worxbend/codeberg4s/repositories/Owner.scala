package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.ValidationError

/** The user or organisation that owns a repository — the first segment of `owner/name`.
  *
  * Values are validated as URI path segments, so an `Owner` can be interpolated into a request path without further
  * escaping decisions. See [[PathSegment]] for why that matters.
  */
opaque type Owner = String

object Owner:

  /** Parses an owner.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `/`, and a value containing a
    * control character.
    *
    * @return
    *   the trimmed owner, or a [[ValidationError]] on the `"owner"` field
    */
  def from(value: String): Either[ValidationError, Owner] =
    PathSegment.from("owner", value)

  extension (owner: Owner)

    /** The owner as a string, ready to be used as one path segment. */
    def value: String = owner
