package com.worxbend.codeberg4s.users.account

import com.worxbend.codeberg4s.ValidationError

/** The secret half of an OAuth2 application's credentials, readable exactly once.
  *
  * ==This is the first credential in this library that travels '''out''' of the instance and cannot be re-read==
  *
  * `POST /user/applications/oauth2` answers an `OAuth2Application` whose `client_secret` is the only copy Forgejo will
  * ever show: the value is stored hashed, so [[com.worxbend.codeberg4s.users.account.UserApplicationApi.get]] and the
  * listing return the same application with no secret in it at all. A caller who does not persist the value at creation
  * time cannot obtain it again — the only recovery is to create another application, or to accept whatever a `PATCH`
  * re-issues, which is why [[com.worxbend.codeberg4s.users.account.UserApplicationApi.update]] is never retried.
  *
  * That is why [[OAuth2Application.clientSecret]] is an `Option` rather than a field that is always present: absence
  * there is not "the instance forgot to send it", it is "this response is a read, and a read never carries it".
  *
  * ==Redaction discipline==
  *
  * Same as [[com.worxbend.codeberg4s.repositories.actions.SecretValue]] and
  * [[com.worxbend.codeberg4s.repositories.hooks.HookSecret]], and a final class for the same reason: an `opaque type
  * ClientSecret = String` has `Any` as its visible upper bound, so outside its defining scope `value.toString` and
  * `s"$value"` both dispatch to `String`'s `toString` and print the credential. The only way to observe the material is
  * [[ClientSecret.reveal]].
  *
  * '''No `ClientSecret` can render its material.''' `toString` is the mask, so the generated `toString` of
  * [[OAuth2Application]] is too, and so is any interpolation of either. [[com.worxbend.codeberg4s.CallContext]] carries
  * a redacted URI and never a body, so no [[com.worxbend.codeberg4s.CodebergError]] built '''from a value''' can carry
  * the material. `AccountSecrecySuite` asserts every one of those paths.
  *
  * '''The raw body is closed off too, and that is the pipeline's doing rather than this type's.'''
  * [[com.worxbend.codeberg4s.CodebergError.DecodingFailed]] ordinarily carries a bounded snippet of the raw response
  * body, taken before any conversion — so a `201` whose payload carries a `client_secret` and fails to convert for some
  * other reason would report the credential in the clear, mask or no mask. The decoder the creation and the update pass
  * to the pipeline is therefore marked `Decode.sensitive`, and the pipeline substitutes a fixed placeholder naming the
  * size of the withheld body. The consequence for an application: a `DecodingFailed` from any call in this group is
  * safe to log, and so is every successful result. `UserApplicationApiSuite` asserts both, and asserts that a
  * '''read''' keeps its snippet — a read carries no secret to withhold.
  *
  * Instances compare structurally on the underlying material, so a value stays comparable in a test. The comparison is
  * not constant-time; this type guards against accidental disclosure, not against a timing oracle.
  */
final class ClientSecret private (private val material: String):

  /** The raw client secret.
    *
    * The only sanctioned use is handing it to whatever will authenticate as the application. Never log it, never
    * interpolate it, never put it in an error payload — and store it now, because no later read returns it.
    */
  def reveal: String = material

  /** The constant mask, safe to log and to embed anywhere. */
  def redacted: String = ClientSecret.Redacted

  /** Always the mask — a `ClientSecret` never renders its material. */
  override def toString: String = ClientSecret.Redacted

  /** Structural equality on the underlying material; not constant-time, see the class note. */
  override def equals(other: Any): Boolean =
    other match
      case that: ClientSecret => material.equals(that.material)
      case _                  => false

  override def hashCode(): Int = material.hashCode

object ClientSecret:

  /** The mask returned by [[ClientSecret.redacted]] and by `toString`. */
  val Redacted: String = "***"

  /** Parses a client secret out of a creation response.
    *
    * Trims surrounding whitespace, because the value is routinely copied into a configuration file or a shell command
    * and a stray newline would produce an authentication failure nobody can see. Rejects an empty or blank value: a
    * blank secret authenticates nothing, so a payload carrying one is better reported than carried.
    *
    * '''There is no constructor for a caller.''' Nothing in this library sends a client secret, so this is the only way
    * a `ClientSecret` comes into being and every one of them was decoded from a creation response.
    *
    * The returned [[ValidationError]] describes the failure on the `"clientSecret"` field and never echoes the rejected
    * input.
    */
  def from(value: String): Either[ValidationError, ClientSecret] =
    val trimmed = value.trim

    if trimmed.isEmpty then Left(ValidationError("clientSecret", "must not be blank"))
    else Right(new ClientSecret(trimmed))
