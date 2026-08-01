package com.worxbend.codeberg4s.auth

import com.worxbend.codeberg4s.ValidationError

/** A Codeberg / Forgejo personal access token.
  *
  * The only way to observe the material is [[ApiToken.reveal]], whose single legitimate caller is the transport adapter
  * building the `Authorization: token <value>` header. Every other rendering path — `toString`, string interpolation,
  * the generated `toString` of any case class or enum case holding one, and therefore [[CodebergError.describe]] — sees
  * the constant mask `"***"`.
  *
  * '''Why this is a final class and not an opaque type.''' An `opaque type ApiToken = String` has `Any` as its visible
  * upper bound, so outside its defining scope `token.toString` and `s"$token"` both dispatch to `String`'s `toString`
  * and print the secret. That defeats the whole point of the wrapper, so the redaction guarantee is bought with an
  * ordinary final class that overrides `toString`. `ApiTokenSuite` pins this behaviour down.
  *
  * Instances compare structurally on the underlying material, so configuration values remain comparable in tests. The
  * comparison is not constant-time; this type guards against accidental disclosure in logs, not against a timing
  * oracle, and a client library holds its own token rather than checking someone else's.
  */
final class ApiToken private (private val material: String):

  /** The raw token.
    *
    * The only sanctioned use is building an `Authorization` header. Never log it, never interpolate it, never put it in
    * an error payload.
    */
  def reveal: String = material

  /** The constant mask, safe to log and to embed anywhere. */
  def redacted: String = ApiToken.Redacted

  /** Always the mask — an `ApiToken` never renders its material. */
  override def toString: String = ApiToken.Redacted

  /** Structural equality on the underlying material. */
  override def equals(other: Any): Boolean =
    other match
      case that: ApiToken => material.equals(that.material)
      case _              => false

  override def hashCode(): Int = material.hashCode

object ApiToken:

  /** The mask returned by [[ApiToken.redacted]] and by `toString`. */
  val Redacted: String = "***"

  /** Parses a token.
    *
    * Trims surrounding whitespace, because tokens are usually read from an environment variable or a file that carries
    * a trailing newline. Rejects an empty or blank value and any value containing a control character, since such a
    * value cannot be put in a header without splitting the request.
    *
    * The returned [[ValidationError]] describes the failure on the `"apiToken"` field and never echoes the rejected
    * input.
    */
  def from(value: String): Either[ValidationError, ApiToken] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left(ValidationError("apiToken", "must not be blank"))
    else if trimmed.exists(_.isControl) then Left(ValidationError("apiToken", "must not contain a control character"))
    else Right(new ApiToken(trimmed))
