package com.worxbend.codeberg4s

/** The user or organisation that owns a repository — the first segment of `owner/name`.
  *
  * Values are validated as URI path segments, so an `Owner` can be interpolated into a request path without further
  * escaping decisions. See [[PathSegment]] for why that matters.
  */
opaque type Owner = String

object Owner:

  /** Parses an owner.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `/`, a value containing a
    * control character, and the traversal segments `.` and `..`.
    *
    * @return
    *   the trimmed owner, or a [[ValidationError]] on the `"owner"` field
    */
  def from(value: String): Either[ValidationError, Owner] =
    PathSegment.from("owner", value)

  /** Builds an owner from a string literal, checked while the code compiles.
    *
    * `Owner("forgejo")` '''is''' the owner — there is no `Either` to unwrap, because a literal is either valid or it is
    * not, and which one it is can be decided before the program runs. An invalid literal is a compile error pointing at
    * the literal itself. The rules are [[from]]'s, minus the trim: surrounding whitespace is refused rather than
    * removed, so nothing silently rewrites what was written. See [[SegmentLiteral]].
    *
    * Use [[from]] for a value known only at run time — an argument, a config entry, a decoded field. Handing one to
    * this constructor is itself a compile error.
    */
  inline def apply[V <: String & Singleton](inline value: V): Owner =
    SegmentLiteral.plain("owner", value)

  extension (owner: Owner)

    /** The owner as a string, ready to be used as one path segment. */
    def value: String = owner
