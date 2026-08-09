package com.worxbend.codeberg4s

/** Validation shared by every identifier that becomes part of a URI path.
  *
  * This is a security boundary, not a convenience: an identifier such as [[com.worxbend.codeberg4s.repositories.Owner]]
  * or [[com.worxbend.codeberg4s.users.Username]] is interpolated into a request path, so a value containing `/` would
  * let a caller reach an endpoint the API surface never offered, and a control character would corrupt the request
  * line. Both are rejected here, once, rather than at each call site.
  *
  * '''It lives in the root package so that "once" is true.''' The rule used to sit in
  * `com.worxbend.codeberg4s.repositories` and be visible only there, which meant the three identifiers outside that
  * package — [[com.worxbend.codeberg4s.users.Username]], [[com.worxbend.codeberg4s.organizations.OrgName]] and
  * [[com.worxbend.codeberg4s.users.social.AccessTokenName]] — spelled the same four checks out inline. Four copies of a
  * security rule is four places to forget the next clause, which is exactly what happened: the traversal check reached
  * `segmented` and not the copies. A rule that every package can reach cannot drift that way.
  *
  * [[from]] is for an identifier that must occupy exactly one segment. [[segmented]] is for the two that legitimately
  * span several — [[com.worxbend.codeberg4s.repositories.BranchName]] and
  * [[com.worxbend.codeberg4s.repositories.ContentPath]], whose routes Forgejo matches with a wildcard. Both reject the
  * traversal segments `.` and `..`: they carry no slash, so a slash rule alone never sees them, and they survive
  * percent-encoding untouched, so one would reach the request path as a dot segment rather than as a name.
  */
private[codeberg4s] object PathSegment:

  /** Trims `value` and accepts it only if it can stand alone as one path segment.
    *
    * Rejects an empty or blank value, a value containing `/`, a value containing any control character, and the
    * traversal segments `.` and `..`.
    *
    * @param field
    *   the field name to report in a [[ValidationError]]
    */
  def from(field: String, value: String): Either[ValidationError, String] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left(ValidationError(field, "must not be blank"))
    else if trimmed.contains('/') then Left(ValidationError(field, "must not contain a slash"))
    else if trimmed.exists(_.isControl) then Left(ValidationError(field, "must not contain a control character"))
    else if isTraversal(trimmed) then Left(ValidationError(field, "must not be '.' or '..'"))
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
    else
      val parts = trimmed.split('/')
      if parts.exists(_.isEmpty) then Left(ValidationError(field, "must not contain an empty segment"))
      else if parts.exists(isTraversal) then Left(ValidationError(field, "must not contain a '.' or '..' segment"))
      else Right(trimmed)

  private def isTraversal(segment: String): Boolean =
    segment match
      case "." | ".." => true
      case _ => false
