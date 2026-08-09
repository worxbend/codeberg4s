package com.worxbend.codeberg4s.issues

import com.worxbend.codeberg4s.ValidationError

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

  /** Parses a label colour.
    *
    * Trims surrounding whitespace, drops a leading `#`, and lowercases the digits. Rejects anything that is not three
    * or six hexadecimal digits.
    *
    * Checked by counting the digits and then scanning them, rather than by a regular expression. Every label Forgejo
    * returns carries a colour, so this runs once per label on every issue and label listing; a `Pattern` match
    * allocates a `Matcher` and a match result per call, and the grammar it encodes — a length and an alphabet — is
    * cheaper to state directly.
    *
    * @return
    *   the normalised colour, or a [[ValidationError]] on the `"labelColor"` field
    */
  def from(value: String): Either[ValidationError, LabelColor] =
    val trimmed = value.trim
    val digits  = if trimmed.startsWith("#") then trimmed.substring(1) else trimmed
    if isTripletLength(digits.length) && digits.forall(isHexadecimalDigit) then
      Right(digits.toLowerCase(Locale.ROOT))
    else Left(ValidationError("labelColor", "must be three or six hexadecimal digits, optionally prefixed with '#'"))

  /** Whether `length` is one of the two digit counts Forgejo takes: three-digit shorthand or a six-digit triplet. */
  private def isTripletLength(length: Int): Boolean =
    length match
      case 3 | 6 => true
      case _ => false

  /** Whether `digit` is one of `0`-`9`, `a`-`f` or `A`-`F`.
    *
    * Spelled out as range comparisons rather than delegating to `Character.digit`, which also accepts the non-ASCII
    * decimal digits of every Unicode script — `٣` and `३` are digits to the JDK, and neither belongs in a colour.
    */
  private def isHexadecimalDigit(digit: Char): Boolean =
    (digit >= '0' && digit <= '9') ||
    (digit >= 'a' && digit <= 'f') ||
    (digit >= 'A' && digit <= 'F')

  extension (color: LabelColor)

    /** The colour as bare lowercase hexadecimal digits, without a `#` — the form Forgejo returns. */
    def value: String = color

    /** The colour with a leading `#`, the form Forgejo documents for `CreateLabelOption.color`. */
    def hashed: String = s"#$color"
