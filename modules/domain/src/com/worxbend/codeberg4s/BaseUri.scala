package com.worxbend.codeberg4s

/** The root of a Forgejo API v1 deployment, for example `https://codeberg.org/api/v1`.
  *
  * The base URI is configuration, not a constant: the same client must work against codeberg.org and against any
  * self-hosted Forgejo instance. Values are normalised without a trailing slash so that request building can join
  * segments with a single `/` and never produce `//`.
  */
opaque type BaseUri = String

object BaseUri:

  /** The public Codeberg deployment, used when [[CodebergConfig.apply]] is given only an [[auth.Auth]]. */
  val Codeberg: BaseUri = "https://codeberg.org/api/v1"

  private val HttpPrefix: String      = "http://"
  private val HttpsPrefix: String     = "https://"
  private val TrailingSlashes: String = "/+$"

  /** Parses a base URI.
    *
    * Accepts an absolute `http://` or `https://` URI with something after the scheme, trims surrounding whitespace and
    * removes any trailing slashes. Rejects an empty or blank value, a value carrying a control character, a scheme this
    * library cannot speak, a scheme with no authority, and — because a base URI is prefixed to every rendered URI, and
    * every rendered URI can reach a log file — user information before the host, a query string and a fragment.
    *
    * A rejection message never repeats the offending value: user information is a credential, and reporting it would
    * put it exactly where this validation exists to keep it out of.
    *
    * @return
    *   the normalised URI, or a [[ValidationError]] on the `"baseUri"` field
    */
  def from(value: String): Either[ValidationError, BaseUri] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left(invalid("must not be blank"))
    else if trimmed.exists(_.isControl) then Left(invalid("must not contain a control character"))
    else
      schemeOf(trimmed) match
        case None         => Left(invalid("must start with http:// or https://"))
        case Some(scheme) =>
          val normalised = trimmed.replaceAll(TrailingSlashes, "")
          if normalised.length <= scheme.length then Left(invalid("must have a host after the scheme"))
          else if hasUserInfo(normalised, scheme) then
            Left(invalid("must not carry user information before the host"))
          else if normalised.indexOf('?') >= 0 then Left(invalid("must not carry a query string"))
          else if normalised.indexOf('#') >= 0 then Left(invalid("must not carry a fragment"))
          else Right(normalised)

  private def schemeOf(value: String): Option[String] =
    if value.startsWith(HttpsPrefix) then Some(HttpsPrefix)
    else if value.startsWith(HttpPrefix) then Some(HttpPrefix)
    else None

  /** Whether the authority carries `user:password@` before the host.
    *
    * Only the authority is inspected — the run between the scheme and the first `/`, `?` or `#`. An `@` further along
    * is an ordinary path character, as in `https://forge.example/api/v1/@me`, and is left alone.
    */
  private def hasUserInfo(value: String, scheme: String): Boolean =
    value.substring(scheme.length, authorityEnd(value, scheme)).indexOf('@') >= 0

  /** The index at which the authority ends: the first `/`, `?` or `#` after the scheme, or the end of the value. */
  private def authorityEnd(value: String, scheme: String): Int =
    val boundary = value.indexWhere(endsAuthority, scheme.length)
    if boundary < 0 then value.length else boundary

  private def endsAuthority(char: Char): Boolean =
    char match
      case '/' | '?' | '#' => true
      case _ => false

  private def invalid(message: String): ValidationError =
    ValidationError("baseUri", message)

  extension (uri: BaseUri)

    /** The normalised URI as a string, without a trailing slash. */
    def value: String = uri
