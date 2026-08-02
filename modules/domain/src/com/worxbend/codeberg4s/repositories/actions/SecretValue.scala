package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.ValidationError

/** The material of an Actions secret, on its way '''to''' the instance and never back.
  *
  * Same redaction discipline as [[com.worxbend.codeberg4s.auth.ApiToken]], and a final class for the same reason: an
  * `opaque type SecretValue = String` has `Any` as its visible upper bound, so outside its defining scope
  * `value.toString` and `s"$value"` both dispatch to `String`'s `toString` and print the secret. The only way to
  * observe the material is [[SecretValue.reveal]], whose single legitimate caller is the request renderer that puts it
  * in the `PUT` body.
  *
  * '''Nothing in this library can put this value in a failure or a log.''' `toString` is the mask, so the generated
  * `toString` of any case class holding one is too; [[com.worxbend.codeberg4s.CallContext]] carries a redacted URI and
  * never a request body; and [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] snippets the '''response''' body,
  * which for these endpoints is empty. The value reaches exactly one place: the bytes of the request.
  *
  * '''There is no way back.''' Forgejo never returns a secret's value — see [[ActionSecret]] — so this type is
  * write-only by construction and no decoder produces one.
  *
  * Instances compare structurally on the underlying material, so a configuration value stays comparable in a test. The
  * comparison is not constant-time; this type guards against accidental disclosure, not against a timing oracle.
  */
final class SecretValue private (private val material: String):

  /** The raw secret.
    *
    * The only sanctioned use is rendering the body of `PUT /repos/{owner}/{repo}/actions/secrets/{secretname}`. Never
    * log it, never interpolate it, never put it in an error payload.
    */
  def reveal: String = material

  /** The constant mask, safe to log and to embed anywhere. */
  def redacted: String = SecretValue.Redacted

  /** Always the mask — a `SecretValue` never renders its material. */
  override def toString: String = SecretValue.Redacted

  /** Structural equality on the underlying material; not constant-time, see the class note. */
  override def equals(other: Any): Boolean =
    other match
      case that: SecretValue => material.equals(that.material)
      case _                 => false

  override def hashCode(): Int = material.hashCode

object SecretValue:

  /** The mask returned by [[SecretValue.redacted]] and by `toString`. */
  val Redacted: String = "***"

  /** Parses a secret value.
    *
    * '''Deliberately permissive, and deliberately not trimmed.''' A secret is routinely a PEM block, a JSON service
    * account or a password whose leading and trailing whitespace is significant, so unlike
    * [[com.worxbend.codeberg4s.auth.ApiToken.from]] nothing is trimmed and — unlike it again — a control character is
    * '''accepted''': a newline inside a private key is data, not an injection, because this value travels in a JSON
    * body where it is escaped, never in a header. Only an empty value is rejected, because the spec marks `data` as
    * required and an empty secret is far more likely to be a bug than an intention.
    *
    * Forgejo normalises line endings to LF on the way in, as browsers do. A caller who needs CRLF preserved must
    * Base64-encode the value themselves; that is the API's instruction, not this library's.
    *
    * The returned [[ValidationError]] describes the failure on the `"secretValue"` field and never echoes the rejected
    * input.
    */
  def from(value: String): Either[ValidationError, SecretValue] =
    if value.isEmpty then Left(ValidationError("secretValue", "must not be empty"))
    else Right(new SecretValue(value))
