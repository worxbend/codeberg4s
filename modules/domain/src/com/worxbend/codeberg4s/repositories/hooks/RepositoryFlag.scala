package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.{PathSegment, SegmentLiteral, ValidationError}

/** One administrative flag attached to a repository — the `{flag}` of `/repos/{owner}/{repo}/flags/{flag}`.
  *
  * ==What a flag is==
  *
  * A Forgejo-only feature with no Gitea equivalent: an instance administrator can attach arbitrary named flags to a
  * repository, and templates and instance policy can then key off them. The vocabulary is chosen by whoever runs the
  * instance, not by Forgejo and certainly not by this library, which is why this is a validated string and not an enum.
  * The whole flag surface is disabled by default; an instance that has not enabled it answers `404` to every one of
  * these routes, exactly as it would for a repository that does not exist.
  *
  * Flags are administrative: reading them requires the repository to be visible, and every write requires site
  * administrator privileges. Both refusals arrive as a [[com.worxbend.codeberg4s.CodebergError.Api]] with `403`, never
  * as a [[ValidationError]].
  *
  * A flag becomes a path segment on three of the six routes, so it is validated as one.
  */
opaque type RepositoryFlag = String

object RepositoryFlag:

  /** Parses a flag name.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `/`, a value containing a
    * control character, and the traversal segments `.` and `..` — see
    * [[com.worxbend.codeberg4s.repositories.PathSegment]] for why that is a security boundary and not a convenience.
    * Nothing else is checked: the vocabulary belongs to the instance, and a flag this library refused would be one the
    * caller could not set.
    *
    * @return
    *   the flag, or a [[ValidationError]] on the `"repositoryFlag"` field
    */
  def from(value: String): Either[ValidationError, RepositoryFlag] =
    PathSegment.from("repositoryFlag", value)

  /** Builds a flag from a string literal, checked while the code compiles.
    *
    * `RepositoryFlag("...")` '''is''' the flag, with no `Either` to unwrap: a literal is either valid or it is not, and
    * an invalid one is a compile error pointing at the literal itself. The rules are [[from]]'s, minus the trim —
    * surrounding whitespace is refused rather than removed. See [[com.worxbend.codeberg4s.SegmentLiteral]], and use
    * [[from]] for a value known only at run time.
    */
  inline def apply[V <: String & Singleton](inline value: V): RepositoryFlag =
    SegmentLiteral.plain("repositoryFlag", value)

  extension (flag: RepositoryFlag)

    /** The flag as a string, ready to be used as one path segment or rendered into a request body. */
    def value: String = flag
