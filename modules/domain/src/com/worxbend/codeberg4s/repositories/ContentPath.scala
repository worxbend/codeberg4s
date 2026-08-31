package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.{PathSegment, SegmentLiteral, ValidationError}

/** A path to a file or directory inside a repository, as `GET /repos/{owner}/{repo}/contents/{filepath}` spells it.
  *
  * Repository-relative and slash-separated: `README.md`, `models/user.go`. Like [[BranchName]] it spans several URI
  * segments — Forgejo matches the route with a wildcard — so it is decomposed by [[segments]] rather than
  * percent-encoded whole, and the `.`/`..` segments that would escape the route are rejected at construction. See
  * [[PathSegment]].
  */
opaque type ContentPath = String

object ContentPath:

  /** Parses a repository-relative path.
    *
    * Trims surrounding whitespace. Rejects a blank path, a control character, a leading or trailing `/` — the API takes
    * the path relative to the repository root, so a leading slash is a caller mistake rather than an absolute path — an
    * empty segment, and a `.` or `..` segment.
    *
    * @return
    *   the trimmed path, or a [[ValidationError]] on the `"filepath"` field
    */
  def from(value: String): Either[ValidationError, ContentPath] =
    PathSegment.segmented("filepath", value)

  /** Builds a repository-relative path from a string literal, checked while the code compiles.
    *
    * `ContentPath("...")` '''is''' the repository-relative path, with no `Either` to unwrap: a literal is either valid
    * or it is not, and an invalid one is a compile error pointing at the literal itself. The rules are [[from]]'s,
    * minus the trim — surrounding whitespace is refused rather than removed. See
    * [[com.worxbend.codeberg4s.SegmentLiteral]], and use [[from]] for a value known only at run time.
    */
  inline def apply[V <: String & Singleton](inline value: V): ContentPath =
    SegmentLiteral.segmented("filepath", value)

  extension (path: ContentPath)

    /** The path as Forgejo spells it, slashes included. */
    def value: String = path

    /** The path split on `/`, for appending to a request path one segment at a time. */
    def segments: List[String] = path.split('/').toList

    /** The last segment — the file or directory's own name.
      *
      * `models/user.go` gives `user.go`; a path with no `/` at all is already its own name and is returned unchanged.
      * Read off the last `/` rather than by splitting, so the answer costs one substring instead of an array holding
      * every segment of the path. [[from]] has already rejected a trailing `/`, so the last segment is never empty.
      */
    def name: String = path.substring(path.lastIndexOf('/') + 1)
