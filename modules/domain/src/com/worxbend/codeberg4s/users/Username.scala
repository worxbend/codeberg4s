package com.worxbend.codeberg4s.users

import com.worxbend.codeberg4s.{PathSegment, SegmentLiteral, ValidationError}

/** The handle that names a person — the `{username}` of `/users/{username}`.
  *
  * This is deliberately '''not''' [[com.worxbend.codeberg4s.Owner]], even though the two are the same characters on the
  * wire. An `Owner` answers "who does this repository belong to?", and the answer may be an organisation; a `Username`
  * answers "which account is this?", and the endpoints that take one — `/users/{u}/followers`, `/users/{u}/keys` — are
  * about a person. Giving them one type would let `client.users.keys(repository.slug.owner)` compile against an
  * organisation that has no keys, which is exactly the confusion the type is here to prevent. Converting is a
  * deliberate step through [[from]], not an implicit widening.
  *
  * Values are validated as URI path segments, so a `Username` can be interpolated into a request path without further
  * escaping decisions — see [[Username.from]] for what that rejects and why.
  */
opaque type Username = String

object Username:

  /** The field name a rejected value is reported under, stable enough for a caller to branch on. */
  private val Field: String = "username"

  /** Parses a username.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value, a value containing `/`, a value containing a
    * control character, and the traversal segments `.` and `..`.
    *
    * '''This is a security boundary, not a convenience.''' A `Username` is interpolated into a request path, so a value
    * containing `/` would let a caller reach an endpoint the API surface never offered — `client.users.keys` on
    * `"someone/../../admin"` — and a control character would corrupt the request line. Both are rejected here, once,
    * rather than at each call site. A bare `.` or `..` is rejected for the same reason and needs saying separately: it
    * carries no slash, so the slash rule never sees it, and it survives percent-encoding untouched, so it would reach
    * the request path as a dot segment rather than as an account name. Forgejo's own rules for what an account may be
    * called are narrower still, but they are the instance's business: this type promises only that the value cannot
    * forge a path.
    *
    * @return
    *   the trimmed username, or a [[ValidationError]] on the `"username"` field
    */
  def from(value: String): Either[ValidationError, Username] =
    PathSegment.from(Field, value)

  /** Builds a username from a string literal, checked while the code compiles.
    *
    * `Username("...")` '''is''' the username, with no `Either` to unwrap: a literal is either valid or it is not, and
    * an invalid one is a compile error pointing at the literal itself. The rules are [[from]]'s, minus the trim —
    * surrounding whitespace is refused rather than removed. See [[com.worxbend.codeberg4s.SegmentLiteral]], and use
    * [[from]] for a value known only at run time.
    */
  inline def apply[V <: String & Singleton](inline value: V): Username =
    SegmentLiteral.plain("username", value)

  extension (username: Username)

    /** The username as a string, ready to be used as one path segment. */
    def value: String = username
