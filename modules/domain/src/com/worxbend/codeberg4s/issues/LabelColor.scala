package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

import scala.util.matching.Regex

import java.util.Locale

/** The background colour of a [[Label]], as a hexadecimal RGB triplet.
  *
  * Forgejo is inconsistent about the leading `#`: every colour on `golden/issue/labels-repo.json` and on the labels
  * embedded in `golden/issue/list-closed.json` comes back '''without''' one — `"eb6420"`, `"ee0701"` — while the
  * documented input form for `CreateLabelOption.color` carries it. Both spellings are accepted here and normalised to
  * the bare, lowercase form the API returns, so a round trip through this type is stable and two labels that differ
  * only in spelling compare equal.
  *
  * Three-digit shorthand is accepted because CSS-minded callers write it and Forgejo takes it; it is '''not''' expanded
  * to six digits, because that would make [[value]] disagree with what the instance stores.
  */
opaque type LabelColor = String

object LabelColor:

  private val Hexadecimal: Regex = "^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$".r

  /** Parses a label colour.
    *
    * Trims surrounding whitespace, drops a leading `#`, and lowercases the digits. Rejects anything that is not three
    * or six hexadecimal digits.
    *
    * @return
    *   the normalised colour, or a [[ValidationError]] on the `"labelColor"` field
    */
  def from(value: String): Either[ValidationError, LabelColor] =
    Hexadecimal.findFirstMatchIn(value.trim) match
      case Some(digits) => Right(digits.group(1).toLowerCase(Locale.ROOT))
      case None         =>
        Left(ValidationError("labelColor", "must be three or six hexadecimal digits, optionally prefixed with '#'"))

  extension (color: LabelColor)

    /** The colour as bare lowercase hexadecimal digits, without a `#` — the form Forgejo returns. */
    def value: String = color

    /** The colour with a leading `#`, the form Forgejo documents for `CreateLabelOption.color`. */
    def hashed: String = s"#$color"
