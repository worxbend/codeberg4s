package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.ValidationError

import java.util.Locale

/** A Git object identifier — a commit, a tree or a blob hash.
  *
  * Forgejo renders these as lowercase hexadecimal, and every endpoint that takes one puts it in a request path
  * (`/git/commits/{sha}`, `/git/blobs/{sha}`). A raw `String` would therefore be both a lie about the shape of the
  * value and a path-forging hazard, which is why this type validates rather than merely wrapping. See [[PathSegment]]
  * for the rest of that argument.
  *
  * Both hash algorithms Forgejo supports are accepted: SHA-1 renders as 40 characters and SHA-256 as 64. Abbreviated
  * ids are accepted down to four characters, because that is the shortest prefix Git itself will resolve.
  */
opaque type CommitSha = String

object CommitSha:

  /** The shortest abbreviation Git resolves, and therefore the shortest value accepted here. */
  val MinLength: Int = 4

  /** The length of a full SHA-256 object id, and therefore the longest value accepted here. */
  val MaxLength: Int = 64

  private val HexDigits: String = "0123456789abcdefABCDEF"

  /** Parses an object id.
    *
    * Trims surrounding whitespace and normalises to lowercase, so two spellings of the same commit compare equal.
    * Rejects anything shorter than [[MinLength]] or longer than [[MaxLength]] characters, and anything containing a
    * character that is not a hexadecimal digit — which is also what makes the value safe as a path segment.
    *
    * @return
    *   the normalised id, or a [[ValidationError]] on the `"commitSha"` field
    */
  def from(value: String): Either[ValidationError, CommitSha] =
    val trimmed = value.trim
    if trimmed.length < MinLength || trimmed.length > MaxLength then
      Left(ValidationError("commitSha", s"must be between $MinLength and $MaxLength characters"))
    else if !trimmed.forall(HexDigits.contains) then
      Left(ValidationError("commitSha", "must be hexadecimal"))
    else Right(trimmed.toLowerCase(Locale.ROOT))

  extension (sha: CommitSha)

    /** The id as a lowercase hexadecimal string, ready to be used as one path segment. */
    def value: String = sha

    /** The seven-character prefix Forgejo and Git use when displaying a commit. Not unique; for display only. */
    def short: String = sha.take(7)
