package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.PathSegment
import com.worxbend.codeberg4s.SegmentLiteral
import com.worxbend.codeberg4s.ValidationError

/** The name of a wiki page, as `GET /repos/{owner}/{repo}/wiki/page/{pageName}` spells it.
  *
  * ==Spaces and slashes are both ordinary here==
  *
  * A wiki page is a file in a Git repository whose name is its title, so `Getting Started` and `Deployment/Kubernetes`
  * are both perfectly good names — the first because Forgejo maps spaces to dashes itself when it stores the file, the
  * second because a wiki has sub-pages. The slash is the reason this validates with
  * [[com.worxbend.codeberg4s.repositories.PathSegment.segmented]] rather than
  * [[com.worxbend.codeberg4s.repositories.PathSegment.from]], and the reason [[segments]] exists: exactly as for
  * [[com.worxbend.codeberg4s.repositories.BranchName]], a slashed name has to reach the wire as a real separator, and a
  * name percent-encoded whole into one segment would address a page that does not exist.
  *
  * That makes the validation a security boundary rather than a formality. A name is decomposed into segments here, and
  * the `.` and `..` segments that would climb out of the wiki route are rejected before any request is built.
  *
  * The name is '''not''' normalised. Forgejo's own dash-for-space mapping is applied by the instance, and a client that
  * anticipated it would send a name the caller never wrote and would disagree with the instance the moment the mapping
  * changed.
  */
opaque type WikiPageName = String

object WikiPageName:

  /** Parses a wiki page name.
    *
    * Trims surrounding whitespace. Rejects a blank name, a control character, a leading or trailing `/`, an empty
    * segment (`a//b`), and a `.` or `..` segment. Internal spaces are kept, because they are part of the title.
    *
    * @return
    *   the trimmed name, or a [[ValidationError]] on the `"pageName"` field
    */
  def from(value: String): Either[ValidationError, WikiPageName] =
    PathSegment.segmented("pageName", value)

  /** Builds a wiki page name from a string literal, checked while the code compiles.
    *
    * `WikiPageName("...")` '''is''' the wiki page name, with no `Either` to unwrap: a literal is either valid or it is
    * not, and an invalid one is a compile error pointing at the literal itself. The rules are [[from]]'s, minus the
    * trim — surrounding whitespace is refused rather than removed. See [[com.worxbend.codeberg4s.SegmentLiteral]], and
    * use [[from]] for a value known only at run time.
    */
  inline def apply[V <: String & Singleton](inline value: V): WikiPageName =
    SegmentLiteral.segmented("pageName", value)

  extension (name: WikiPageName)

    /** The name as Forgejo spells it, slashes and spaces included. */
    def value: String = name

    /** The name split on `/`, for appending to a request path one segment at a time.
      *
      * A slashed page name must not be percent-encoded into a single segment — see the type's own note.
      */
    def segments: List[String] = name.split('/').toList
