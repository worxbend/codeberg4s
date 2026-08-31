package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

/** Validation shared by every value that Forgejo expects inside a '''comma-separated''' query parameter.
  *
  * `GET /repos/{owner}/{repo}/issues` takes `labels` and `milestones` as one string holding several values joined by
  * commas. That encoding has no escape: a value containing a comma is silently read as two filters, so
  * `labels=needs,triage` asks for the labels `needs` and `triage` rather than for `needs,triage`. It is the
  * query-string cousin of the path forging [[com.worxbend.codeberg4s.PathSegment]] rejects, and it is
  * rejected the same way — at construction, once, rather than at each call site.
  *
  * Only '''input''' types are validated this way. [[Label.name]] is a plain `String`, because the instance may already
  * hold a name this validation refuses and refusing to decode it would cost the caller a whole page of labels.
  */
private[issues] object FilterToken:

  /** Trims `value` and accepts it only if it can stand alone inside a comma-separated parameter.
    *
    * Rejects an empty or blank value, a value containing `,`, and a value containing any control character.
    *
    * @param field
    *   the field name to report in a [[ValidationError]]
    */
  def from(field: String, value: String): Either[ValidationError, String] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left(ValidationError(field, "must not be blank"))
    else if trimmed.contains(',') then Left(ValidationError(field, "must not contain a comma"))
    else if trimmed.exists(_.isControl) then Left(ValidationError(field, "must not contain a control character"))
    else Right(trimmed)
