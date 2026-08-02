package com.worxbend.codeberg4s.repositories.gitdata

import com.worxbend.codeberg4s.ValidationError
import com.worxbend.codeberg4s.repositories.PathSegment

/** The `basehead` path parameter of `GET /repos/{owner}/{repo}/compare/{basehead}` — two refs joined by `...`.
  *
  * Forgejo spells a comparison as one path parameter, `base...head`, and matches it with a wildcard because either half
  * may itself contain `/`. `main...renovate/deps` is three URI segments (`main...renovate`, `deps`) and not one, which
  * is why this is an opaque type over the joined string with a [[segments]] accessor rather than a pair of refs the
  * request builder would have to re-join. See [[RefName]] for the same argument in more detail.
  *
  * The separator is `...`, three dots, which is Git's symmetric-difference spelling and the only one this endpoint
  * accepts. Nothing here rewrites `..` into `...`: a caller who wrote the wrong separator gets a `404` from the
  * instance rather than a silently different comparison.
  */
opaque type CompareRange = String

object CompareRange:

  /** The separator Forgejo puts between the two refs. */
  val Separator: String = "..."

  /** The comparison from `base` to `head`.
    *
    * '''Cannot fail.''' Both halves are already valid multi-segment paths, and joining two of them with `...` can
    * introduce neither an empty segment nor a `.`/`..` segment: the last segment of `base` and the first of `head` are
    * both non-empty, so the segment they fuse into is at least five characters long.
    */
  def between(base: RefName, head: RefName): CompareRange =
    s"${base.value}$Separator${head.value}"

  /** Parses a `basehead` a caller already holds as one string, for example one copied out of a browser URL.
    *
    * Trims surrounding whitespace. Rejects anything [[RefName]] rejects — a blank value, a control character, a leading
    * or trailing `/`, an empty segment, a `.` or `..` segment — and additionally rejects a value with no `...` in it,
    * because a comparison of one ref with nothing is not a comparison.
    *
    * @return
    *   the trimmed range, or a [[ValidationError]] on the `"basehead"` field
    */
  def from(value: String): Either[ValidationError, CompareRange] =
    PathSegment
      .segmented("basehead", value)
      .filterOrElse(_.contains(Separator), ValidationError("basehead", s"must join two refs with '$Separator'"))

  extension (range: CompareRange)

    /** The range as Forgejo spells it, `base...head`. */
    def value: String = range

    /** The range split on `/`, for appending to a request path one segment at a time. */
    def segments: List[String] = range.split('/').toList
