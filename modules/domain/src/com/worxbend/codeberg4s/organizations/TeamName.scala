package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.ValidationError

/** A team's name as a caller '''supplies''' it — creating a team, or renaming one.
  *
  * '''Not an address.''' No endpoint in this API takes a team name: `/teams/{id}` is rooted at the instance and every
  * team route addresses the team by [[TeamId]], because two organisations may each own a team called `owners`. This
  * type therefore exists to stop an empty or control-character-bearing name reaching a request body, not to make a
  * value safe for a path.
  *
  * A team name '''read back''' from the instance is a plain `String` on [[Team.name]] and deliberately not this type,
  * for the reason [[com.worxbend.codeberg4s.issues.LabelName]] gives: a team that already exists with a name this
  * library would refuse must still decode. Converting in that direction is the caller's explicit step through
  * [[TeamName.from]].
  *
  * Forgejo's own rules for a team name are narrower — it reserves `owners` and applies its own length and character
  * limits — and they are the instance's business. This type promises only that the value is a name at all; a name the
  * instance dislikes comes back as a `422`.
  */
opaque type TeamName = String

object TeamName:

  /** The field name a rejected value is reported under. */
  private val Field: String = "teamName"

  /** Parses a team name.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value and a value containing a control character. A `/` is
    * '''allowed''', unlike [[OrgName]]: this value never becomes a path segment, so a slash cannot forge a request, and
    * refusing one would be a rule this library invented rather than one Forgejo has.
    *
    * @return
    *   the trimmed name, or a [[ValidationError]] on the `"teamName"` field
    */
  def from(value: String): Either[ValidationError, TeamName] =
    val trimmed = value.trim

    if trimmed.isEmpty then Left(ValidationError(Field, "must not be blank"))
    else if trimmed.exists(_.isControl) then Left(ValidationError(Field, "must not contain a control character"))
    else Right(trimmed)

  extension (name: TeamName)

    /** The name as a string, ready to be sent as `CreateTeamOption.name` or `EditTeamOption.name`. */
    def value: String = name
