package com.worxbend.codeberg4s.users

import com.worxbend.codeberg4s.WireVocabulary

/** How much of an account Forgejo shows to whom.
  *
  * Forgejo's `VisibleType` has exactly these three values and renders them lowercase on the wire. Modelling it as an
  * enum rather than a `String` is the difference between `if user.visibility.contains("publik")` compiling and not.
  */
enum UserVisibility(val wireName: String) extends WireVocabulary:

  /** Visible to anyone, including anonymous callers. This is what every account in the golden fixtures reports. */
  case Public extends UserVisibility("public")

  /** Visible only to signed-in users. */
  case Limited extends UserVisibility("limited")

  /** Visible only to the account itself and to instance administrators. */
  case Private extends UserVisibility("private")

object UserVisibility:

  /** Parses Forgejo's lowercase spelling.
    *
    * Answers `None` for anything unrecognised rather than failing. A future Forgejo release adding a fourth visibility
    * must not make an otherwise perfectly good user object undecodable — the field is descriptive, not load-bearing.
    * Matching is case-insensitive because nothing guarantees the casing but observation.
    */
  def parse(value: String): Option[UserVisibility] =
    WireVocabulary.parse(values, value)
