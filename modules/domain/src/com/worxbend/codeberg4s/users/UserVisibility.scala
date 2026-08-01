package com.worxbend.codeberg4s.users

/** How much of an account Forgejo shows to whom.
  *
  * Forgejo's `VisibleType` has exactly these three values and renders them lowercase on the wire. Modelling it as an
  * enum rather than a `String` is the difference between `if user.visibility.contains("publik")` compiling and not.
  */
enum UserVisibility:

  /** Visible to anyone, including anonymous callers. This is what every account in the golden fixtures reports. */
  case Public

  /** Visible only to signed-in users. */
  case Limited

  /** Visible only to the account itself and to instance administrators. */
  case Private

object UserVisibility:

  /** Parses Forgejo's lowercase spelling.
    *
    * Answers `None` for anything unrecognised rather than failing. A future Forgejo release adding a fourth visibility
    * must not make an otherwise perfectly good user object undecodable — the field is descriptive, not load-bearing.
    * Matching is case-insensitive because nothing guarantees the casing but observation.
    */
  def parse(value: String): Option[UserVisibility] =
    value.trim.toLowerCase match
      case "public"  => Some(Public)
      case "limited" => Some(Limited)
      case "private" => Some(Private)
      case _         => None

  extension (visibility: UserVisibility)

    /** The lowercase spelling Forgejo uses on the wire. */
    def wireName: String =
      visibility match
        case Public  => "public"
        case Limited => "limited"
        case Private => "private"
