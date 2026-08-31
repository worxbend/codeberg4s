package com.worxbend.codeberg4s

/** The repository half of `owner/name`.
  *
  * Validated exactly like [[Owner]], as a URI path segment, so it cannot forge a path. See [[PathSegment]].
  */
opaque type RepoName = String

object RepoName:

  /** Parses a repository name.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `/`, a value containing a
    * control character, and the traversal segments `.` and `..`.
    *
    * @return
    *   the trimmed name, or a [[ValidationError]] on the `"repoName"` field
    */
  def from(value: String): Either[ValidationError, RepoName] =
    PathSegment.from("repoName", value)

  /** Builds a repository name from a string literal, checked while the code compiles.
    *
    * `RepoName("forgejo")` '''is''' the name, with no `Either` to unwrap: an invalid literal is a compile error
    * pointing at the literal itself. The rules are [[from]]'s, minus the trim. See [[SegmentLiteral]], and use [[from]]
    * for a value known only at run time.
    */
  inline def apply[V <: String & Singleton](inline value: V): RepoName =
    SegmentLiteral.plain("repoName", value)

  extension (name: RepoName)

    /** The repository name as a string, ready to be used as one path segment. */
    def value: String = name
