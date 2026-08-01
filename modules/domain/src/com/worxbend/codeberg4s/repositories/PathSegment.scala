package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.ValidationError

/** Validation shared by every identifier that becomes part of a URI path.
  *
  * This is a security boundary, not a convenience: [[Owner]] and [[RepoName]] are interpolated into request paths, so a
  * value containing `/` would let a caller reach an endpoint the API surface never offered, and a control character
  * would corrupt the request line. Both are rejected here, once, rather than at each call site.
  *
  * [[from]] is for an identifier that must occupy exactly one segment. [[segmented]] is for the two that legitimately
  * span several — [[BranchName]] and [[ContentPath]], whose routes Forgejo matches with a wildcard — and it rejects the
  * traversal segments that would otherwise turn a slash into an escape hatch.
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

  /** Trims `value` and accepts it only if every `/`-separated part can stand alone as one path segment.
    *
    * Rejects an empty or blank value, any control character, a leading or trailing `/`, an empty segment such as the
    * middle of `a//b`, and a `.` or `..` segment. The last of those is the one that matters: `..` survives
    * percent-encoding untouched, so a value carrying it would walk out of the route it was meant for.
    *
    * @param field
    *   the field name to report in a [[ValidationError]]
    */
  def segmented(field: String, value: String): Either[ValidationError, String] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left(ValidationError(field, "must not be blank"))
    else if trimmed.exists(_.isControl) then Left(ValidationError(field, "must not contain a control character"))
    else if trimmed.startsWith("/") || trimmed.endsWith("/") then
      Left(ValidationError(field, "must not start or end with a slash"))
    else if trimmed.split('/').exists(_.isEmpty) then Left(ValidationError(field, "must not contain an empty segment"))
    else if trimmed.split('/').exists(isTraversal) then
      Left(ValidationError(field, "must not contain a '.' or '..' segment"))
    else Right(trimmed)

  private def isTraversal(segment: String): Boolean =
    segment match
      case "." | ".." => true
      case _ => false
