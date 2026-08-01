package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

/** A label name as a caller '''supplies''' it — creating a label, or filtering a listing by label.
  *
  * Both uses are wire-sensitive. [[IssueQuery.withLabels]] joins names with commas into one `labels` parameter, an
  * encoding with no escape, so a name containing a comma quietly becomes two filters; see [[FilterToken]].
  *
  * A label '''read back''' from the instance is a plain `String` on [[Label.name]] and deliberately not this type.
  * Forgejo does not forbid commas in a label name, so an existing label may well carry one, and a decoder that insisted
  * on this type would fail a whole page of labels over a name it merely cannot filter by. Converting in that direction
  * is the caller's explicit step, and [[LabelName.from]] tells them when it is not possible.
  */
opaque type LabelName = String

object LabelName:

  /** Parses a label name.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `,`, and a value containing a
    * control character.
    *
    * @return
    *   the trimmed name, or a [[ValidationError]] on the `"labelName"` field
    */
  def from(value: String): Either[ValidationError, LabelName] =
    FilterToken.from("labelName", value)

  extension (name: LabelName)

    /** The name as a string, ready to be joined into a `labels` parameter or sent as `CreateLabelOption.name`. */
    def value: String = name
