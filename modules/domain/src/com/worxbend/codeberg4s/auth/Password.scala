package com.worxbend.codeberg4s.auth

import com.worxbend.codeberg4s.ValidationError

/** The password half of HTTP basic credentials.
  *
  * Same redaction discipline as [[ApiToken]], and a final class for the same reason: an opaque alias over `String`
  * cannot stop `toString` or string interpolation from printing the secret. The only way to observe the material is
  * [[Password.reveal]], whose single legitimate caller is the transport adapter encoding the `Authorization: Basic`
  * header.
  *
  * Basic auth is supported because some self-hosted Forgejo deployments still require it; prefer [[ApiToken]] wherever
  * the instance allows it.
  */
final class Password private (private val material: String):

  /** The raw password. The only sanctioned use is building an `Authorization` header. */
  def reveal: String = material

  /** The constant mask, safe to log and to embed anywhere. */
  def redacted: String = Password.Redacted

  /** Always the mask — a `Password` never renders its material. */
  override def toString: String = Password.Redacted

  /** Structural equality on the underlying material; not constant-time, see [[ApiToken]]. */
  override def equals(other: Any): Boolean =
    other match
      case that: Password => material.equals(that.material)
      case _              => false

  override def hashCode(): Int = material.hashCode

object Password:

  /** The mask returned by [[Password.redacted]] and by `toString`. */
  val Redacted: String = "***"

  /** Parses a password.
    *
    * Unlike [[ApiToken.from]] the value is '''not''' trimmed: leading and trailing whitespace can be significant in a
    * password. Rejects an empty value and any value containing a control character, which cannot survive header
    * encoding.
    *
    * The returned [[ValidationError]] describes the failure on the `"password"` field and never echoes the rejected
    * input.
    */
  def from(value: String): Either[ValidationError, Password] =
    if value.isEmpty then Left(ValidationError("password", "must not be empty"))
    else if value.exists(_.isControl) then Left(ValidationError("password", "must not contain a control character"))
    else Right(new Password(value))
