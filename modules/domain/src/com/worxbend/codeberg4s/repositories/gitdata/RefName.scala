package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.PathSegment
import com.worxbend.codeberg4s.ValidationError

/** The name of a Git reference, whole or partial — `refs/heads/main`, `heads/main`, `tags/v1.2`, `main`.
  *
  * The raw-git endpoints spell a ref in three different positions and this one type covers all three: as a wildcard
  * path (`GET /repos/{owner}/{repo}/git/refs/{ref}`), as the ref half of an archive name (`GET
  * /repos/{owner}/{repo}/archive/{archive}`), and as a `ref` query parameter on `/raw`, `/media` and `/editorconfig`.
  *
  * A ref name contains `/` by construction, which is exactly the trap
  * [[com.worxbend.codeberg4s.repositories.BranchName]] documents: percent-encoding `refs/heads/main` into one path
  * segment produces `refs%2Fheads%2Fmain` and a `404`, because Forgejo matches the route with a wildcard. [[segments]]
  * is what the request builder appends, one segment at a time, so the slashes reach the wire as real separators.
  *
  * Validation is therefore a security boundary rather than a formality — see
  * [[com.worxbend.codeberg4s.repositories.PathSegment]]. A `..` segment survives percent-encoding untouched and would
  * let a caller climb out of the route it was meant for, so it is rejected at construction and cannot reach a request.
  */
opaque type RefName = String

object RefName:

  /** Parses a ref name.
    *
    * Trims surrounding whitespace. Rejects a blank name, a control character, a leading or trailing `/`, an empty
    * segment (`refs//heads`), and a `.` or `..` segment. Git rejects all of those as ref names too, so nothing a real
    * repository can hold is lost.
    *
    * @return
    *   the trimmed name, or a [[ValidationError]] on the `"ref"` field
    */
  def from(value: String): Either[ValidationError, RefName] =
    PathSegment.segmented("ref", value)

  extension (ref: RefName)

    /** The name as Git spells it, slashes included. Also what goes into a `ref` query parameter. */
    def value: String = ref

    /** The name split on `/`, for appending to a request path one segment at a time.
      *
      * A slashed ref must not be percent-encoded into a single segment — see the type's own note.
      */
    def segments: List[String] = ref.split('/').toList
