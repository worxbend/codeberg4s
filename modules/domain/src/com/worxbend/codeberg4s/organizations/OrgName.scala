package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.PathSegment
import com.worxbend.codeberg4s.ValidationError

/** The handle that names an organisation — the `{org}` of `/orgs/{org}`.
  *
  * This is deliberately neither [[com.worxbend.codeberg4s.repositories.Owner]] nor
  * [[com.worxbend.codeberg4s.users.Username]], even though all three are the same characters on the wire. An `Owner`
  * answers "who does this repository belong to?" and may be a person; a `Username` answers "which account is this?" and
  * is a person; an `OrgName` answers "which organisation is this?". Giving the three one type would let
  * `client.organizations.members(repository.slug.owner)` compile against a personal account, which has no members and
  * answers `404`. Converting is a deliberate step through [[from]], not an implicit widening.
  *
  * Values are validated as URI path segments, so an `OrgName` can be interpolated into a request path without further
  * escaping decisions — see [[OrgName.from]] for what that rejects and why.
  */
opaque type OrgName = String

object OrgName:

  /** The field name a rejected value is reported under, stable enough for a caller to branch on. */
  private val Field: String = "orgName"

  /** Parses an organisation name.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `/`, a value containing a
    * control character, and the traversal segments `.` and `..`.
    *
    * '''This is a security boundary, not a convenience.''' An `OrgName` is interpolated into a request path, so a value
    * containing `/` would let a caller reach an endpoint the API surface never offered — `client.organizations.get` on
    * `"forgejo/../../admin"` — and a control character would corrupt the request line. Both are rejected here, once,
    * rather than at each call site. A bare `.` or `..` is rejected for the same reason and needs saying separately: it
    * carries no slash, so the slash rule never sees it, and it survives percent-encoding untouched, so it would reach
    * the request path as a dot segment rather than as an organisation name. Forgejo's own rules for what an
    * organisation may be called are narrower still, but they are the instance's business: this type promises only that
    * the value cannot forge a path.
    *
    * The rules are deliberately no narrower than that, because `golden/organization/org-list.json` contains
    * organisations named `_CYBER_STONES_`, `-_` and `-_-`. An identifier validator that assumed alphanumerics would
    * refuse to decode the very first page of `GET /orgs`.
    *
    * @return
    *   the trimmed name, or a [[ValidationError]] on the `"orgName"` field
    */
  def from(value: String): Either[ValidationError, OrgName] =
    PathSegment.from(Field, value)

  extension (name: OrgName)

    /** The name as a string, ready to be used as one path segment.
      *
      * This is also the value to hand to [[com.worxbend.codeberg4s.repositories.Owner.from]] when an organisation's
      * repositories are to be reached through `client.repos` rather than through `client.organizations`; the two types
      * accept the same characters, so that conversion cannot fail in practice, but it is written out rather than
      * assumed.
      */
    def value: String = name
