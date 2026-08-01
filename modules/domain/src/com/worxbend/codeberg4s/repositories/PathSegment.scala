package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.ValidationError

/** Validation shared by every identifier that becomes a single URI path segment.
  *
  * This is a security boundary, not a convenience: [[Owner]] and [[RepoName]] are interpolated into request paths, so a
  * value containing `/` would let a caller reach an endpoint the API surface never offered, and a control character
  * would corrupt the request line. Both are rejected here, once, rather than at each call site.
  */
private[repositories] object PathSegment:

  /** Trims `value` and accepts it only if it can stand alone as one path segment.
    *
    * Rejects an empty or blank value, a value containing `/`, and a value containing any control character.
    *
    * @param field
    *   the field name to report in a [[ValidationError]]
    */
  def from(field: String, value: String): Either[ValidationError, String] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left(ValidationError(field, "must not be blank"))
    else if trimmed.contains('/') then Left(ValidationError(field, "must not contain a slash"))
    else if trimmed.exists(_.isControl) then Left(ValidationError(field, "must not contain a control character"))
    else Right(trimmed)
