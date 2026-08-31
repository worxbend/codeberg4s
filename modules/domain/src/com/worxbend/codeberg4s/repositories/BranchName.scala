package com.worxbend.codeberg4s.repositories

import com.worxbend.codeberg4s.{PathSegment, SegmentLiteral, ValidationError}

/** The name of a branch, as `GET /repos/{owner}/{repo}/branches/{branch}` spells it.
  *
  * Unlike [[com.worxbend.codeberg4s.Owner]] and [[com.worxbend.codeberg4s.RepoName]], a branch name may legitimately
  * contain `/`: `golden/repository/branches-list.json` captures
  * `renovate/forgejo-github.com-go-swagger-go-swagger-cmd-swagger-0.x` and `v16.0/forgejo`, both from
  * `forgejo/forgejo`. Forgejo routes those with a wildcard, so the slash has to reach the wire as a real separator; a
  * name percent-encoded whole into a single segment answers `404`. [[segments]] exists for exactly that reason, and it
  * is what the request builder uses.
  *
  * That makes the validation below a security boundary rather than a formality: a name is decomposed into segments
  * here, and a `.` or `..` segment — the one thing that would let a caller climb out of the branch route — is rejected
  * before any request is built.
  */
opaque type BranchName = String

object BranchName:

  /** Parses a branch name.
    *
    * Trims surrounding whitespace. Rejects a blank name, a control character, a leading or trailing `/`, an empty
    * segment (`a//b`), and a `.` or `..` segment. Git rejects all of those as ref names too, so nothing valid is lost.
    *
    * @return
    *   the trimmed name, or a [[ValidationError]] on the `"branch"` field
    */
  def from(value: String): Either[ValidationError, BranchName] =
    PathSegment.segmented("branch", value)

  /** Builds a branch name from a string literal, checked while the code compiles.
    *
    * `BranchName("...")` '''is''' the branch name, with no `Either` to unwrap: a literal is either valid or it is not,
    * and an invalid one is a compile error pointing at the literal itself. The rules are [[from]]'s, minus the trim —
    * surrounding whitespace is refused rather than removed. See [[com.worxbend.codeberg4s.SegmentLiteral]], and use
    * [[from]] for a value known only at run time.
    */
  inline def apply[V <: String & Singleton](inline value: V): BranchName =
    SegmentLiteral.segmented("branch", value)

  extension (branch: BranchName)

    /** The name as Forgejo spells it, slashes included. */
    def value: String = branch

    /** The name split on `/`, for appending to a request path one segment at a time.
      *
      * A slashed branch name must not be percent-encoded into a single segment — see the type's own note.
      */
    def segments: List[String] = branch.split('/').toList
