package com.worxbend.codeberg4s.organizations

import com.worxbend.codeberg4s.ValidationError

/** The instance-wide identifier of a team, as `GET /teams/{id}` takes it.
  *
  * A team is addressed by this number and never by its name: `/teams/{id}` is rooted at the instance rather than at the
  * organisation, so two organisations may each own a team called `owners` and only the id tells them apart. Everything
  * else a team endpoint could be handed — an organisation id, a repository id, a user id — is `Long`-shaped too, which
  * is why this is a type and not a bare `Long`.
  */
opaque type TeamId = Long

object TeamId:

  private val MinValue: Long = 1L

  /** Parses a team identifier.
    *
    * Rejects zero and negatives: Forgejo's identifiers are database row ids and start at one.
    *
    * @return
    *   the identifier, or a [[ValidationError]] on the `"teamId"` field
    */
  def from(value: Long): Either[ValidationError, TeamId] =
    if value < MinValue then Left(ValidationError("teamId", s"must be at least $MinValue")) else Right(value)

  extension (id: TeamId)

    /** The identifier as a `Long`, ready to be rendered into a path segment. */
    def value: Long = id
