package com.worxbend.codeberg4s

/** The `User-Agent` header this client sends.
  *
  * Forgejo instances use it to attribute traffic, and some deployments reject requests without one, so it is required
  * configuration rather than an option. Control characters are rejected because a `\r` or `\n` in a header value is a
  * response-splitting vector.
  */
opaque type UserAgent = String

object UserAgent:

  /** The value used when [[CodebergConfig.apply]] is given only an [[auth.Auth]]. */
  val Default: UserAgent = "codeberg4s"

  /** Parses a user agent.
    *
    * Trims surrounding whitespace. Rejects an empty or blank value and any value containing a control character.
    *
    * @return
    *   the trimmed value, or a [[ValidationError]] on the `"userAgent"` field
    */
  def from(value: String): Either[ValidationError, UserAgent] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left(ValidationError("userAgent", "must not be blank"))
    else if trimmed.exists(_.isControl) then Left(ValidationError("userAgent", "must not contain a control character"))
    else Right(trimmed)

  extension (agent: UserAgent)

    /** The header value as a string. */
    def value: String = agent
