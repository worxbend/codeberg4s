package com.worxbend.codeberg4s.repositories.admin

import com.worxbend.codeberg4s.ValidationError

/** A credential for a '''third-party''' Git host, on its way out in a request body and never back.
  *
  * Two endpoints in this group take one: `POST /repos/migrate` accepts `auth_password` or `auth_token` for the remote
  * being cloned, and `POST /repos/{owner}/{repo}/push_mirrors` accepts `remote_password` for the remote being pushed
  * to. Neither is a Forgejo credential — it is a GitHub token, a GitLab password, an access key for somebody else's
  * forge — which is precisely why it must not end up in this library's logs: the blast radius of leaking it is a system
  * this library has never heard of.
  *
  * Same redaction discipline as [[com.worxbend.codeberg4s.repositories.actions.SecretValue]] and
  * [[com.worxbend.codeberg4s.auth.ApiToken]], and a final class for the same reason: an
  * `opaque type RemoteCredential = String` has `Any` as its visible upper bound, so outside its defining scope
  * `value.toString` and `s"$value"` both dispatch to `String`'s `toString` and print the credential. The only way to
  * observe the material is [[RemoteCredential.reveal]], whose only legitimate callers are the two request renderers
  * that put it in a body.
  *
  * '''Nothing in this library can put this value in a failure or a log.''' `toString` is the mask, so the generated
  * `toString` of [[MigrateRepository]] and [[CreatePushMirror]] is too; [[com.worxbend.codeberg4s.CallContext]] carries
  * a redacted URI and never a request body; and [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] snippets the
  * '''response''' body, which for these endpoints is a repository or a mirror description and never echoes what was
  * sent.
  *
  * '''There is no way back.''' Forgejo never returns a stored remote credential — [[PushMirror]] has no password field
  * at all — so this type is write-only by construction and no decoder produces one.
  *
  * Instances compare structurally on the underlying material, so a configuration value stays comparable in a test. The
  * comparison is not constant-time; this type guards against accidental disclosure, not against a timing oracle.
  */
final class RemoteCredential private (private val material: String):

  /** The raw credential.
    *
    * The only sanctioned uses are rendering the body of `POST /repos/migrate` and of
    * `POST /repos/{owner}/{repo}/push_mirrors`. Never log it, never interpolate it, never put it in an error payload.
    */
  def reveal: String = material

  /** The constant mask, safe to log and to embed anywhere. */
  def redacted: String = RemoteCredential.Redacted

  /** Always the mask — a `RemoteCredential` never renders its material. */
  override def toString: String = RemoteCredential.Redacted

  /** Structural equality on the underlying material; not constant-time, see the class note. */
  override def equals(other: Any): Boolean =
    other match
      case that: RemoteCredential => material.equals(that.material)
      case _                      => false

  override def hashCode(): Int = material.hashCode

object RemoteCredential:

  /** The mask returned by [[RemoteCredential.redacted]] and by `toString`. */
  val Redacted: String = "***"

  /** Parses a remote credential.
    *
    * '''Deliberately permissive, and deliberately not trimmed.''' A remote credential is routinely a token whose exact
    * bytes matter, and a password may legitimately begin or end with a space, so unlike
    * [[com.worxbend.codeberg4s.auth.ApiToken.from]] nothing is trimmed. Only an empty value is rejected: an empty
    * credential is far more likely to be an unset environment variable than an intention, and sending one turns an
    * authentication problem into a confusing `422` from the remote host.
    *
    * The returned [[ValidationError]] describes the failure on the `"remoteCredential"` field and never echoes the
    * rejected input.
    */
  def from(value: String): Either[ValidationError, RemoteCredential] =
    if value.isEmpty then Left(ValidationError("remoteCredential", "must not be empty"))
    else Right(new RemoteCredential(value))
