package com.worxbend.codeberg4s.repositories.actions

import com.worxbend.codeberg4s.ValidationError

/** A one-shot credential that lets a runner register itself against a repository.
  *
  * This is the value `forgejo-runner register --token …` consumes, and anyone holding it can attach a runner that will
  * execute workflow code with the repository's Actions permissions. It is a credential in every sense that matters, so
  * it carries the same redaction discipline as [[com.worxbend.codeberg4s.auth.ApiToken]] — a final class overriding
  * `toString`, because an opaque alias over `String` cannot stop interpolation from printing it, and
  * [[RunnerRegistrationToken.reveal]] as the single way to observe the material.
  *
  * Unlike [[SecretValue]] this one travels '''from''' the instance: it is decoded out of a response body, which means a
  * decoding failure could otherwise have carried it into an error message. It cannot — see the class note on
  * [[SecretValue]] for why — but the mask is what makes that true rather than merely likely.
  *
  * Instances compare structurally on the underlying material. The comparison is not constant-time.
  */
final class RunnerRegistrationToken private (private val material: String):

  /** The raw token. The only sanctioned use is handing it to the runner being registered. */
  def reveal: String = material

  /** The constant mask, safe to log and to embed anywhere. */
  def redacted: String = RunnerRegistrationToken.Redacted

  /** Always the mask — a `RunnerRegistrationToken` never renders its material. */
  override def toString: String = RunnerRegistrationToken.Redacted

  /** Structural equality on the underlying material; not constant-time, see the class note. */
  override def equals(other: Any): Boolean =
    other match
      case that: RunnerRegistrationToken => material.equals(that.material)
      case _                             => false

  override def hashCode(): Int = material.hashCode

object RunnerRegistrationToken:

  /** The mask returned by [[RunnerRegistrationToken.redacted]] and by `toString`. */
  val Redacted: String = "***"

  /** Parses a registration token.
    *
    * Trims surrounding whitespace, because the value is usually copied into a shell command. Rejects an empty or blank
    * value — a blank token registers nothing, so decoding one is a failure worth reporting rather than a credential
    * worth carrying.
    *
    * The returned [[ValidationError]] describes the failure on the `"runnerRegistrationToken"` field and never echoes
    * the rejected input.
    */
  def from(value: String): Either[ValidationError, RunnerRegistrationToken] =
    val trimmed = value.trim

    if trimmed.isEmpty then Left(ValidationError("runnerRegistrationToken", "must not be blank"))
    else Right(new RunnerRegistrationToken(trimmed))
