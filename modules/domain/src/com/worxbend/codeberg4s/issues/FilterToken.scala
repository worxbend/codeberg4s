package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

/** Validation shared by every value that Forgejo expects inside a '''comma-separated''' query parameter.
  *
  * `GET /repos/{owner}/{repo}/issues` takes `labels` and `milestones` as one string holding several values joined by
  * commas. That encoding has no escape: a value containing a comma is silently read as two filters, so
  * `labels=needs,triage` asks for the labels `needs` and `triage` rather than for `needs,triage`. It is the
  * query-string cousin of the path forging [[com.worxbend.codeberg4s.PathSegment]] rejects, and it is rejected the same
  * way — at construction, once, rather than at each call site.
  *
  * ==A token can also become a path segment==
  *
  * [[LabelName]] is not only joined into a query parameter: [[LabelRef.ByName]] puts one into the `{identifier}`
  * segment of `DELETE /repos/{owner}/{repo}/issues/{index}/labels/{identifier}`. A `/` is allowed there on purpose —
  * Forgejo's scoped labels are spelled `scope/value` and the transport percent-encodes it — but the traversal segments
  * `.` and `..` are not: they carry no slash, they survive percent-encoding untouched, and one would reach the request
  * path as a dot segment rather than as a name, which is the rule [[com.worxbend.codeberg4s.PathSegment]] enforces for
  * every other identifier. Rejecting them here, for every token, keeps a name valid in each place it can be sent rather
  * than valid in one and not another.
  *
  * Only '''input''' types are validated this way. [[Label.name]] is a plain `String`, because the instance may already
  * hold a name this validation refuses and refusing to decode it would cost the caller a whole page of labels.
  */
private[issues] object FilterToken:

  /** Trims `value` and accepts it only if it can stand alone inside a comma-separated parameter and, should it be sent
    * that way, as one path segment.
    *
    * Rejects an empty or blank value, a value containing `,`, a value containing any control character, and the
    * traversal segments `.` and `..`.
    *
    * @param field
    *   the field name to report in a [[ValidationError]]
    */
  def from(field: String, value: String): Either[ValidationError, String] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left(ValidationError(field, "must not be blank"))
    else if trimmed.contains(',') then Left(ValidationError(field, "must not contain a comma"))
    else if trimmed.exists(_.isControl) then Left(ValidationError(field, "must not contain a control character"))
    else if isTraversal(trimmed) then Left(ValidationError(field, "must not be '.' or '..'"))
    else Right(trimmed)

  private def isTraversal(token: String): Boolean =
    token match
      case "." | ".." => true
      case _ => false
