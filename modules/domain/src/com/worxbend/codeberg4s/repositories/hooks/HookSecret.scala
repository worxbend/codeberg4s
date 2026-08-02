package com.worxbend.codeberg4s.repositories.hooks

import com.worxbend.codeberg4s.ValidationError

/** Credential material a webhook carries, on its way '''to''' the instance and never back.
  *
  * Two fields of a hook are credentials, and this one type covers both because they are the same kind of thing and
  * deserve the same discipline:
  *
  *   - the `secret` entry of [[HookConfig]], which Forgejo uses to sign each delivery so the receiver can verify it;
  *   - the `authorization_header` of the `Hook` model, which is sent verbatim as an `Authorization` header on every
  *     delivery — a bearer token by any other name.
  *
  * Same redaction discipline as [[com.worxbend.codeberg4s.repositories.actions.SecretValue]] and
  * [[com.worxbend.codeberg4s.auth.ApiToken]], and a final class for the same reason: an `opaque type HookSecret =
  * String` has `Any` as its visible upper bound, so outside its defining scope `value.toString` and `s"$value"` both
  * dispatch to `String`'s `toString` and print the credential. The only way to observe the material is
  * [[HookSecret.reveal]], whose sole legitimate callers are the request renderers that put it in a `POST` or `PATCH`
  * body.
  *
  * '''Nothing in this library can put this value in a failure or a log.''' `toString` is the mask, so the generated
  * `toString` of any case class holding one is too; [[com.worxbend.codeberg4s.CallContext]] carries a redacted URI and
  * never a request body; and [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] snippets the '''response''' body,
  * which for these endpoints never contains one — see [[HookConfig]] for how that is enforced on the way in as well.
  *
  * '''There is no way back.''' No decoder in this library produces a `HookSecret`, so the type is write-only by
  * construction rather than by convention.
  *
  * Instances compare structurally on the underlying material, so a configuration value stays comparable in a test. The
  * comparison is not constant-time; this type guards against accidental disclosure, not against a timing oracle.
  */
final class HookSecret private (private val material: String):

  /** The raw credential.
    *
    * The only sanctioned use is rendering the body of `POST /repos/{owner}/{repo}/hooks` or
    * `PATCH /repos/{owner}/{repo}/hooks/{id}`. Never log it, never interpolate it, never put it in an error payload.
    */
  def reveal: String = material

  /** The constant mask, safe to log and to embed anywhere. */
  def redacted: String = HookSecret.Redacted

  /** Always the mask — a `HookSecret` never renders its material. */
  override def toString: String = HookSecret.Redacted

  /** Structural equality on the underlying material; not constant-time, see the class note. */
  override def equals(other: Any): Boolean =
    other match
      case that: HookSecret => material.equals(that.material)
      case _                => false

  override def hashCode(): Int = material.hashCode

object HookSecret:

  /** The mask returned by [[HookSecret.redacted]] and by `toString`. */
  val Redacted: String = "***"

  /** Parses webhook credential material.
    *
    * '''Deliberately permissive, and deliberately not trimmed.''' A signing secret is arbitrary bytes chosen by whoever
    * operates the receiving endpoint, and an `Authorization` header value can legitimately be long and unusual;
    * trimming either would change the credential and produce deliveries the receiver rejects for reasons no one could
    * see. Only an empty value is refused, because an empty secret is far more likely to be a bug than an intention —
    * and because Forgejo reads an empty `secret` as "no signing at all", which is a security decision a caller should
    * make explicitly by omitting the secret rather than by passing `""`.
    *
    * A control character is accepted for the same reason it is accepted by
    * [[com.worxbend.codeberg4s.repositories.actions.SecretValue.from]]: this value travels inside a JSON body, where it
    * is escaped, and never in a request path or a header this library builds.
    *
    * The returned [[ValidationError]] describes the failure on the `"hookSecret"` field and never echoes the rejected
    * input.
    */
  def from(value: String): Either[ValidationError, HookSecret] =
    if value.isEmpty then Left(ValidationError("hookSecret", "must not be empty"))
    else Right(new HookSecret(value))
